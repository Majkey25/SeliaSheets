package com.majkeylab.seliadocs.documents

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.ElementDraft
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.PageMode
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.data.pageTextFits
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WordTextFlowTest {
    private lateinit var application: Application
    private lateinit var database: SeliaDocsDatabase
    private lateinit var repository: SeliaDocsRepository
    private lateinit var directory: File
    private var ids = 0
    private var failAtId: Int? = null

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(application, SeliaDocsDatabase::class.java).build()
        repository = SeliaDocsRepository(database, idFactory = {
            if (ids == failAtId) throw IOException("Injected insertion failure")
            "word-${ids++}"
        })
        directory = File(application.cacheDir, "word-flow-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun importEditAndExportCreatesTextCopyAndKeepsOriginal() = runBlocking {
        val book = createNotebook()
        val anchor = repository.getPages(book).single()
        val original = File(directory, "original.docx")
        val paragraphs = listOf("Příliš žluťoučký 🌍\n  second line", "\tLast paragraph")
        original.outputStream().use { WordTextCodec.write(paragraphs, it) }
        val originalBytes = original.readBytes()

        val pageId = importer().import(book, Uri.fromFile(original), anchor.id).single()

        assertEquals(paragraphs.joinToString("\n"), repository.getBlocks(pageId).single().text)
        val edited = "Edited 🌍\n\tStill here"
        repository.updatePageText(pageId, edited)
        repository.addElement(pageId, ElementDraft(ElementKind.TEXT, 48f, 120f, 200f, 60f, text = "Text box"))
        repository.addElement(pageId, ElementDraft(ElementKind.IMAGE, 48f, 200f, 100f, 100f, assetId = "image.png"))
        val destination = File(directory, "edited.docx")
        WordTextExporter.export(repository.loadNotebook(book), directory, application.contentResolver, Uri.fromFile(destination))

        assertEquals(listOf("", "\u000C$edited\nText box"), WordTextCodec.read(destination).paragraphs)
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals(emptyList<String>(), repository.getPdfSources(book).map { it.assetId })
        assertEquals(listOf("image.png"), repository.getReferencedAssetIds().toList())
        assertNoTemporaryFiles()
    }

    @Test
    fun paginationPreservesEveryUnicodeCodePointAndNewline() = runBlocking {
        val text = "  Příliš 🌍 e\u0301\t終\n\n".repeat(6_000) + "last\n"
        assertTrue(text.length > 100_000)
        val pages = paginateWordText(listOf(text), 595, 842)
        assertTrue(pages.size > 1)
        assertEquals(text, pages.joinToString(""))
        assertTrue(pages.all { pageTextFits(it, 595, 842) })
        assertTrue(pages.none { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) })
    }

    @Test
    fun explicitBreaksAndEmptyParagraphsRemainInTheText() = runBlocking {
        val paragraphs = listOf("", "\u000Cfirst\n\u000C\u000Clast", "")
        val pages = paginateWordText(paragraphs, 595, 842)
        assertEquals(paragraphs.joinToString("\n"), pages.joinToString(""))
        assertEquals(4, pages.size)
        assertTrue(pages.dropLast(1).all { it.endsWith('\u000C') })
    }

    @Test
    fun exportDoesNotDuplicateAnImportedPageBreak() = runBlocking {
        val book = createNotebook()
        repository.importWordText(book, listOf("First\u000C", "Second"), 595, 842)
        val destination = File(directory, "breaks.docx")
        destination.outputStream().use { WordTextExporter.write(repository.loadNotebook(book), it) }
        assertEquals(listOf("", "\u000CFirst\u000C", "Second"), WordTextCodec.read(destination).paragraphs)
    }

    @Test
    fun insertsAfterAnchorAndPreservesChapterSizeAndOrder() = runBlocking {
        val book = createNotebook(PageOrientation.LANDSCAPE)
        val anchor = repository.getPages(book).single()
        val chapter = repository.createChapter(book, "Lecture", 0xFF123456.toInt())
        repository.assignPageToChapter(anchor.id, chapter)
        val following = repository.addPage(book, anchor.id)
        val ids = repository.importWordText(book, listOf("One", "Two"), 842, 595, anchor.id)

        val pages = repository.getPages(book)
        assertEquals(listOf(anchor.id) + ids + following, pages.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), pages.map { it.pageIndex })
        assertTrue(pages.filter { it.id in ids }.all {
            it.chapterId == chapter && it.widthPoints == 842 && it.heightPoints == 595 && it.pageMode == PageMode.PAPER.name
        })
        assertEquals(listOf("One", "Two"), ids.map { repository.getBlocks(it).single().text })
    }

    @Test
    fun insertionFailureRollsBackPagesBlocksAndShiftedOrder() = runBlocking {
        val book = createNotebook()
        val anchor = repository.getPages(book).single()
        repository.addPage(book, anchor.id)
        val before = repository.loadNotebook(book)
        failAtId = ids + 2

        val failure = runCatching { repository.importWordText(book, listOf("First", "Second"), 595, 842, anchor.id) }

        assertTrue(failure.exceptionOrNull() is IOException)
        assertEquals(before, repository.loadNotebook(book))
    }

    @Test
    fun invalidAnchorAndDimensionsLeaveNotebookUnchanged() = runBlocking {
        val book = createNotebook()
        val anchor = repository.getPages(book).single()
        repository.addPage(book, anchor.id)
        val other = repository.getPages(createNotebook()).single()
        val before = repository.loadNotebook(book)
        for ((anchorId, width) in listOf("missing" to 595, other.id to 595, anchor.id to 842)) {
            assertTrue(runCatching { repository.importWordText(book, listOf("Text"), width, 842, anchorId) }.isFailure)
            assertEquals(before, repository.loadNotebook(book))
        }
    }

    @Test
    fun malformedAndOversizedFilesSaveNothingAndRemoveTemporaryCopies() = runBlocking {
        val book = createNotebook()
        val anchor = repository.getPages(book).single()
        val before = repository.loadNotebook(book)
        val source = File(directory, "bad.docx")
        source.writeText("not a DOCX")
        assertTrue(runCatching { importer().import(book, Uri.fromFile(source), anchor.id) }.isFailure)
        assertEquals(before, repository.loadNotebook(book))
        assertNoTemporaryFiles()
        source.outputStream().use { output -> repeat(257) { output.write(ByteArray(65_536)) } }
        assertTrue(runCatching { importer().import(book, Uri.fromFile(source), anchor.id) }.isFailure)
        assertEquals(before, repository.loadNotebook(book))
        assertNoTemporaryFiles()
    }

    @Test
    fun pageCountAndUnprintablePageFailuresDoNotTruncate() = runBlocking {
        assertTrue(runCatching { paginateWordText(listOf("x\u000C".repeat(MAX_WORD_TEXT_PAGES + 1)), 595, 842) }.isFailure)
        assertTrue(runCatching { paginateWordText(listOf("Text"), 100, 100) }.isFailure)
        val book = createNotebook()
        val before = repository.loadNotebook(book)
        assertTrue(runCatching { repository.importWordText(book, List(MAX_WORD_TEXT_PAGES + 1) { "" }, 595, 842) }.isFailure)
        assertEquals(before, repository.loadNotebook(book))
    }

    @Test
    fun exportFailureBeforeOpeningDestinationDoesNotOverwriteIt() = runBlocking {
        val book = createNotebook()
        val page = repository.getPages(book).single()
        repository.updatePageText(page.id, "text")
        val content = repository.loadNotebook(book)
        val oversized = content.copy(blocks = content.blocks.map { it.copy(text = "x".repeat(1_000_001)) })
        val destination = File(directory, "destination.docx").apply { writeText("existing") }

        assertTrue(runCatching {
            WordTextExporter.export(oversized, directory, application.contentResolver, Uri.fromFile(destination))
        }.isFailure)

        assertEquals("existing", destination.readText())
        assertNoTemporaryFiles()
    }

    private fun importer() = WordTextImporter(application.contentResolver, directory, repository)

    private suspend fun createNotebook(orientation: PageOrientation = PageOrientation.PORTRAIT): String =
        repository.createNotebook(CreateNotebookRequest("Word", CoverColor.SAGE, CoverPattern.SOLID, PaperTemplate.RULED, orientation, false))

    private fun assertNoTemporaryFiles() {
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("word-import-") || it.name.startsWith("word-export-") })
    }
}
