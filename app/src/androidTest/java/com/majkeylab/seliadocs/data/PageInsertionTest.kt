package com.majkeylab.seliadocs.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageInsertionTest {
    private lateinit var database: SeliaDocsDatabase
    private lateinit var repository: SeliaDocsRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), SeliaDocsDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = SeliaDocsRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun notePageFollowsSlideAndKeepsItsSizeAndChapterWithoutCopyingContent() = runTest {
        val book = notebook()
        val slides = importSlides(book)
        val chapter = repository.createChapter(book, "Lecture", 0)
        repository.assignPageToChapter(slides.first(), chapter)
        repository.updatePageText(slides.first(), "Existing notes")

        val inserted = repository.addPage(book, afterPageId = slides.first())

        val pages = repository.getPages(book)
        assertEquals(listOf(slides.first(), inserted, slides.last()), pages.drop(1).map { it.id })
        assertEquals(listOf(0, 1, 2, 3), pages.map { it.pageIndex })
        val note = requireNotNull(repository.getPage(inserted))
        assertEquals(1280, note.widthPoints)
        assertEquals(720, note.heightPoints)
        assertEquals(chapter, note.chapterId)
        assertEquals(PageMode.PAPER.name, note.pageMode)
        assertEquals(PaperTemplate.BLANK.name, note.paper)
        assertNull(note.pdfSourceId)
        assertNull(note.pdfPageIndex)
        assertNull(note.title)
        assertTrue(repository.getBlocks(inserted).isEmpty())
        assertEquals("Existing notes", repository.getBlocks(slides.first()).single().text)
    }

    @Test fun insertedPdfSlidesKeepSourceOrderBetweenExistingPages() = runTest {
        val book = notebook()
        val first = repository.getPages(book).single().id
        val last = repository.addPage(book)
        val chapter = repository.createChapter(book, "Lecture", 0)
        repository.assignPageToChapter(first, chapter)

        val inserted = importSlides(book, afterPageId = first)

        val pages = repository.getPages(book)
        assertEquals(listOf(first) + inserted + last, pages.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), pages.map { it.pageIndex })
        assertEquals(listOf(0, 1), pages.filter { it.id in inserted }.map { it.pdfPageIndex })
        assertTrue(pages.filter { it.id in inserted }.all { it.chapterId == chapter })
        assertEquals(480, requireNotNull(repository.getPage(inserted.last())).widthPoints)
        assertEquals(800, requireNotNull(repository.getPage(inserted.last())).heightPoints)
    }

    @Test fun missingOrForeignAnchorDoesNotChangePagesOrInstallPdfSource() = runTest {
        val book = notebook()
        val foreign = repository.getPages(notebook()).single().id
        val original = repository.getPages(book)

        for (anchor in listOf("missing-page", foreign)) {
            assertTrue(runCatching { repository.addPage(book, anchor) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { importSlides(book, anchor) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(original, repository.getPages(book))
            assertTrue(repository.getPdfSources(book).isEmpty())
        }
    }

    @Test fun appendWithoutAnchorKeepsNotebookDefaults() = runTest {
        val book = notebook()
        importSlides(book)
        val inserted = repository.addPage(book)

        val last = repository.getPages(book).last()
        assertEquals(inserted, last.id)
        assertTrue(last.widthPoints < last.heightPoints)
        assertEquals(PaperTemplate.GRID.name, last.paper)
        assertNull(last.chapterId)
    }

    @Test fun failedInsertRollsBackShiftedPageIndexes() = runTest {
        val book = notebook()
        val first = repository.getPages(book).single().id
        repository.addPage(book)
        val original = repository.getPages(book)
        val duplicateIds = SeliaDocsRepository(database, idFactory = { first })

        assertTrue(runCatching { duplicateIds.addPage(book, first) }.isFailure)

        assertEquals(original, repository.getPages(book))
    }

    private suspend fun importSlides(book: String, afterPageId: String? = null): List<String> =
        repository.importPdf(
            notebookId = book, assetId = "lecture.pdf", displayName = "Lecture.pdf",
            byteSize = 100, sha256 = "0".repeat(64),
            pages = listOf(PdfPageSpec(1280, 720), PdfPageSpec(480, 800)),
            afterPageId = afterPageId,
        ).pageIds

    private suspend fun notebook(): String = repository.createNotebook(
        CreateNotebookRequest(
            "Physics", CoverColor.PERIWINKLE, CoverPattern.SOLID,
            PaperTemplate.GRID, PageOrientation.PORTRAIT, false,
        ),
    )
}
