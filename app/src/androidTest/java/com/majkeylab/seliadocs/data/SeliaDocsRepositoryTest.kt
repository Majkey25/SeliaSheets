package com.majkeylab.seliadocs.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeliaDocsRepositoryTest {
    private lateinit var database: SeliaDocsDatabase
    private lateinit var repository: SeliaDocsRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var nextId = 0
        database =
            Room.inMemoryDatabaseBuilder(context, SeliaDocsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            SeliaDocsRepository(
                database = database,
                clock = { now },
                idFactory = { "id-${nextId++}" },
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createNotebookAlsoCreatesFirstPage() = runTest {
        val id = repository.createNotebook(request())

        assertEquals("Physics", repository.getNotebook(id).title)
        assertEquals(listOf(0), repository.getPages(id).map(PageEntity::pageIndex))
    }

    @Test
    fun pageTextThatCannotFitIsRejectedWithoutChangingData() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        val oversized = List(80) { "A full line" }.joinToString("\n")

        val failure = runCatching { repository.updatePageText(page.id, oversized) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(repository.getBlocks(page.id).isEmpty())
    }

    @Test
    fun pageMovesRemainContiguousAndOrdered() = runTest {
        val notebookId = repository.createNotebook(request())
        repository.addPage(notebookId)
        repository.addPage(notebookId)

        repository.movePage(notebookId, fromIndex = 2, toIndex = 0)

        val pages = repository.getPages(notebookId)
        assertEquals(listOf(0, 1, 2), pages.map(PageEntity::pageIndex))
        assertEquals(listOf("id-3", "id-1", "id-2"), pages.map(PageEntity::id))
    }

    @Test
    fun deletingOnlyPageIsRejected() = runTest {
        val notebookId = repository.createNotebook(request())
        val onlyPage = repository.getPages(notebookId).single()

        val error = runCatching { repository.deletePage(onlyPage.id) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun strokePayloadPersistsWithoutMetadataLoss() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        val payload =
            StrokePayload(
                brushKind = "PRESSURE_PEN",
                colorArgb = 0xFF202124.toInt(),
                size = 4f,
                epsilon = 0.1f,
                inputs = byteArrayOf(1, 3, 5, 7),
            )

        repository.addStroke(page.id, payload)

        val saved = repository.getStrokes(page.id).single()
        assertEquals(payload.brushKind, saved.brushKind)
        assertEquals(payload.colorArgb, saved.colorArgb)
        assertEquals(payload.size, saved.size)
        assertArrayEquals(payload.inputs, saved.inputs)
    }

    @Test
    fun pagePreviewRevisionsAdvanceOnlyForChangedContent() = runTest {
        val notebookId = repository.createNotebook(request())
        val first = repository.getPages(notebookId).single()
        val secondId = repository.addPage(notebookId)
        val initial = repository.getPages(notebookId).associateBy(PageEntity::id)
        now = 2_000L

        repository.addStroke(
            first.id,
            StrokePayload("PRESSURE_PEN", 0xFF202124.toInt(), 4f, 0.1f, byteArrayOf(1)),
        )
        val afterStroke = repository.getPages(notebookId).associateBy(PageEntity::id)
        assertTrue(afterStroke.getValue(first.id).updatedAt > initial.getValue(first.id).updatedAt)
        assertEquals(initial.getValue(secondId).updatedAt, afterStroke.getValue(secondId).updatedAt)

        repository.addElement(
            secondId,
            ElementDraft(ElementKind.TEXT, 10f, 20f, 100f, 60f, text = "Preview"),
        )
        val afterElement = repository.getPages(notebookId).associateBy(PageEntity::id)
        assertEquals(afterStroke.getValue(first.id).updatedAt, afterElement.getValue(first.id).updatedAt)
        assertTrue(afterElement.getValue(secondId).updatedAt > afterStroke.getValue(secondId).updatedAt)

        repository.updatePageText(first.id, "Preview text")
        val afterBlock = repository.getPages(notebookId).associateBy(PageEntity::id)
        assertTrue(afterBlock.getValue(first.id).updatedAt > afterStroke.getValue(first.id).updatedAt)
        assertEquals(afterElement.getValue(secondId).updatedAt, afterBlock.getValue(secondId).updatedAt)
        assertEquals(now, repository.getNotebook(notebookId).updatedAt)
    }

    @Test
    fun pagePreviewRevisionWrapsWithoutOverflowing() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        val siblingId = repository.addPage(notebookId)
        val siblingRevision = repository.getPages(notebookId).single { it.id == siblingId }.updatedAt
        database.notebookDao().updatePage(page.copy(updatedAt = Long.MAX_VALUE))
        now = 0L

        repository.addStroke(
            page.id,
            StrokePayload("PRESSURE_PEN", 0xFF202124.toInt(), 4f, 0.1f, byteArrayOf(1)),
        )
        assertEquals(0L, repository.getPages(notebookId).single { it.id == page.id }.updatedAt)

        repository.addStroke(
            page.id,
            StrokePayload("PRESSURE_PEN", 0xFF202124.toInt(), 4f, 0.1f, byteArrayOf(2)),
        )

        assertEquals(1L, repository.getPages(notebookId).single { it.id == page.id }.updatedAt)
        assertEquals(siblingRevision, repository.getPages(notebookId).single { it.id == siblingId }.updatedAt)
        assertEquals(now, repository.getNotebook(notebookId).updatedAt)
    }

    @Test
    fun strokeBatchCanBeDeletedAndRestored() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        val payload =
            StrokePayload("PRESSURE_PEN", 0xFF202124.toInt(), 4f, 0.1f, byteArrayOf(1))
        repository.addStroke(page.id, payload)
        repository.addStroke(page.id, payload.copy(inputs = byteArrayOf(2)))
        val snapshot = repository.getStrokes(page.id)

        repository.deleteStrokes(page.id, setOf(snapshot.first().id))
        assertEquals(listOf(snapshot.last().id), repository.getStrokes(page.id).map(StrokeEntity::id))

        repository.replaceStrokes(page.id, snapshot)
        assertEquals(snapshot.map(StrokeEntity::id), repository.getStrokes(page.id).map(StrokeEntity::id))
    }

    @Test
    fun textElementTransformPersists() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        val id =
            repository.addElement(
                page.id,
                ElementDraft(
                    kind = ElementKind.TEXT,
                    x = 80f,
                    y = 120f,
                    width = 220f,
                    height = 80f,
                    text = "Velocity = distance / time",
                ),
            )

        val element = repository.getElements(page.id).single()
        repository.updateElement(element.copy(x = 140f, rotation = 12f))

        val saved = repository.getElements(page.id).single()
        assertEquals(id, saved.id)
        assertEquals(140f, saved.x)
        assertEquals(12f, saved.rotation)
        assertEquals("Velocity = distance / time", saved.text)
    }

    @Test
    fun shapeReplacementRemovesSelectedStrokeAtomically() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        val strokeId =
            repository.addStroke(
                page.id,
                StrokePayload("PRESSURE_PEN", 0xFF202124.toInt(), 4f, 0.1f, byteArrayOf(1)),
            )

        repository.replaceStrokesWithElement(
            page.id,
            setOf(strokeId),
            ElementDraft(
                kind = ElementKind.SHAPE,
                x = 10f,
                y = 20f,
                width = 200f,
                height = 100f,
                shapeKind = "RECTANGLE",
            ),
        )

        assertTrue(repository.getStrokes(page.id).isEmpty())
        assertEquals("RECTANGLE", repository.getElements(page.id).single().shapeKind)
    }

    @Test
    fun duplicatedPageCopiesInkAndElementsWithNewIds() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        repository.addStroke(
            page.id,
            StrokePayload("PRESSURE_PEN", 0xFF202124.toInt(), 4f, 0.1f, byteArrayOf(1)),
        )
        repository.addElement(
            page.id,
            ElementDraft(ElementKind.TEXT, 10f, 20f, 100f, 60f, text = "Copied"),
        )

        val duplicateId = repository.duplicatePage(page.id)

        assertEquals(1, repository.getStrokes(duplicateId).size)
        assertEquals("Copied", repository.getElements(duplicateId).single().text)
        assertTrue(repository.getStrokes(duplicateId).single().id != repository.getStrokes(page.id).single().id)
        assertTrue(repository.getElements(duplicateId).single().id != repository.getElements(page.id).single().id)
    }

    @Test
    fun pageTextPersistsWhitespaceAndDuplicates() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        val text = "Lecture title\n\n  Indented detail"

        repository.updatePageText(page.id, text)
        val source = repository.getBlocks(page.id).single()
        val duplicateId = repository.duplicatePage(page.id)

        assertEquals(text, source.text)
        assertEquals(text, repository.getBlocks(duplicateId).single().text)
        assertTrue(source.id != repository.getBlocks(duplicateId).single().id)

        repository.updatePageText(page.id, "")
        assertTrue(repository.getBlocks(page.id).isEmpty())
    }

    @Test
    fun pageTextSearchReturnsExactPageAndTreatsWildcardsLiterally() = runTest {
        val notebookId = repository.createNotebook(request())
        val first = repository.getPages(notebookId).single()
        val secondId = repository.addPage(notebookId)
        val wildcardDecoyId = repository.addPage(notebookId)
        val backslashId = repository.addPage(notebookId)
        val globId = repository.addPage(notebookId)
        val globDecoyId = repository.addPage(notebookId)
        repository.updatePageText(first.id, "Ordinary lecture")
        repository.updatePageText(secondId, "Efficiency is 95%_measured")
        repository.updatePageText(wildcardDecoyId, "Efficiency is 95Xameasured")
        repository.updatePageText(backslashId, "Path C:\\Notes")
        repository.updatePageText(globId, "Symbols *?[]")
        repository.updatePageText(globDecoyId, "Symbols ABCD")

        assertEquals(secondId, repository.searchPageText(notebookId, "95%_").single().pageId)
        assertEquals(backslashId, repository.searchPageText(notebookId, "C:\\Notes").single().pageId)
        assertEquals(globId, repository.searchPageText(notebookId, "*?[]").single().pageId)
        assertTrue(repository.searchPageText(notebookId, "missing").isEmpty())
    }

    @Test
    fun notebookSearchMatchesCzechTextIgnoringCase() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        val titlecasePageId = repository.addPage(notebookId)
        repository.renamePage(page.id, "Žluťoučký kůň")
        repository.renamePage(titlecasePageId, "ǅungla")

        assertEquals(page.id, repository.searchPageText(notebookId, "žLUŤOUČKÝ").single().pageId)
        assertEquals(titlecasePageId, repository.searchPageText(notebookId, "ǄUNGLA").single().pageId)
        assertEquals(titlecasePageId, repository.searchPageText(notebookId, "ǅungla").single().pageId)
    }

    @Test
    fun notebookSearchReturnsFirstHundredMatchingPagesInOrder() = runTest {
        val notebookId = repository.createNotebook(request())
        repeat(100) { repository.addPage(notebookId) }
        repository.getPages(notebookId).forEach { page ->
            repository.renamePage(page.id, "Match ${page.pageIndex}")
        }

        val results = repository.searchPageText(notebookId, "match")

        assertEquals(100, results.size)
        assertEquals((0 until 100).toList(), results.map(PageTextMatch::pageIndex))
    }

    @Test
    fun notebookSearchFindsPageAndChapterTitles() = runTest {
        val notebookId = repository.createNotebook(request())
        val first = repository.getPages(notebookId).single()
        val secondId = repository.addPage(notebookId)
        val chapterId = repository.createChapter(notebookId, "Linear algebra", 0)
        repository.renamePage(first.id, "Thermodynamics")
        repository.assignPageToChapter(secondId, chapterId)

        assertEquals(first.id, repository.searchPageText(notebookId, "thermo").single().pageId)
        assertEquals(secondId, repository.searchPageText(notebookId, "algebra").single().pageId)
    }

    @Test
    fun notebookSearchFindsMovableTextElements() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        repository.addElement(
            page.id,
            ElementDraft(ElementKind.TEXT, 10f, 20f, 200f, 80f, text = "Feynman technique"),
        )

        val match = repository.searchPageText(notebookId, "feynman").single()

        assertEquals(page.id, match.pageId)
        assertEquals("Feynman technique", match.text)
    }

    @Test
    fun notebookSearchFindsMathExpressionsAndResults() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        repository.addElement(
            page.id,
            ElementDraft(
                kind = ElementKind.MATH,
                x = 10f,
                y = 20f,
                width = 200f,
                height = 80f,
                expression = "sqrt(81)",
                resultText = "9",
            ),
        )

        assertEquals("sqrt(81)", repository.searchPageText(notebookId, "sqrt").single().text)
        assertEquals("9", repository.searchPageText(notebookId, "9").single().text)
    }

    @Test
    fun notebookSearchReturnsEachMatchingPageOnce() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        repository.renamePage(page.id, "Momentum")
        repository.updatePageText(page.id, "Momentum notes")
        repository.addElement(
            page.id,
            ElementDraft(ElementKind.TEXT, 10f, 20f, 200f, 80f, text = "Momentum diagram"),
        )

        val results = repository.searchPageText(notebookId, "momentum")

        assertEquals(1, results.size)
        assertEquals(page.id, results.single().pageId)
    }

    @Test
    fun notebookSearchIgnoresStaleFieldsFromOtherElementKinds() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        database.pageDao().insertElement(
            ElementEntity(
                id = "stale-image",
                pageId = page.id,
                zIndex = 0,
                kind = ElementKind.IMAGE.name,
                x = 0f,
                y = 0f,
                width = 100f,
                height = 100f,
                rotation = 0f,
                text = "Invisible stale text",
                assetId = "image-id",
                shapeKind = null,
                expression = null,
                resultText = null,
            ),
        )
        database.pageDao().insertElement(
            ElementEntity(
                id = "stale-text",
                pageId = page.id,
                zIndex = 1,
                kind = ElementKind.TEXT.name,
                x = 0f,
                y = 0f,
                width = 100f,
                height = 100f,
                rotation = 0f,
                text = "Visible text",
                assetId = null,
                shapeKind = null,
                expression = "Invisible stale equation",
                resultText = "12345",
            ),
        )

        assertTrue(repository.searchPageText(notebookId, "Invisible stale").isEmpty())
        assertTrue(repository.searchPageText(notebookId, "12345").isEmpty())
        assertEquals(page.id, repository.searchPageText(notebookId, "Visible text").single().pageId)
    }

    @Test
    fun notebookSearchIgnoresForeignChapterLinkedByStaleData() = runTest {
        val notebookId = repository.createNotebook(request())
        val foreignNotebookId = repository.createNotebook(request())
        val foreignChapterId = repository.createChapter(foreignNotebookId, "Foreign secret", 0)
        val page = repository.getPages(notebookId).single()
        database.notebookDao().updatePage(page.copy(chapterId = foreignChapterId))

        assertTrue(repository.searchPageText(notebookId, "Foreign secret").isEmpty())
    }

    @Test
    fun notebookSearchFindsLateMatchInLargeNotebook() = runTest {
        val notebookId = repository.createNotebook(request())
        val firstPage = repository.getPages(notebookId).single()
        database.withTransaction {
            repeat(1_199) { offset ->
                val pageIndex = offset + 1
                database.notebookDao().insertPage(
                    firstPage.copy(
                        id = "large-page-$pageIndex",
                        pageIndex = pageIndex,
                        title = "Lecture $pageIndex".takeUnless { pageIndex == 1_199 },
                    ),
                )
            }
            database.notebookDao().updatePage(
                requireNotNull(database.notebookDao().getPage("large-page-1199"))
                    .copy(title = "Late quantum match"),
            )
        }

        assertEquals(
            "large-page-1199",
            repository.searchPageText(notebookId, "late QUANTUM").single().pageId,
        )
        assertTrue(repository.searchPageText(notebookId, "missing term").isEmpty())
    }

    @Test
    fun chapterAssignmentPageTitleAndBookmarkPersist() = runTest {
        val notebookId = repository.createNotebook(request())
        val page = repository.getPages(notebookId).single()
        val chapterId = repository.createChapter(notebookId, "  Mechanics  ", 0xFF3156D9.toInt())

        repository.assignPageToChapter(page.id, chapterId)
        repository.renamePage(page.id, "  Newton's laws  ")
        repository.setPageBookmarked(page.id, true)

        val updated = repository.getPages(notebookId).single()
        assertEquals("Mechanics", repository.getChapters(notebookId).single().title)
        assertEquals(chapterId, updated.chapterId)
        assertEquals("Newton's laws", updated.title)
        assertTrue(updated.bookmarked)

        repository.deleteChapter(chapterId)
        assertTrue(repository.getChapters(notebookId).isEmpty())
        assertEquals(null, repository.getPages(notebookId).single().chapterId)
    }

    @Test
    fun fingerDrawingUpdatePersistsAndTouchesNotebook() = runTest {
        val id = repository.createNotebook(request())
        now = 2_000L

        repository.setFingerDrawing(id, true)

        val updated = repository.getNotebook(id)
        assertTrue(updated.fingerDrawing)
        assertEquals(2_000L, updated.updatedAt)
    }

    @Test
    fun deletingChapterAtTemporaryOffsetBoundaryReindexesWithoutCollision() = runTest {
        val notebookId = repository.createNotebook(request())
        database.withTransaction {
            repeat(10_001) { index ->
                database.notebookDao().insertChapter(
                    ChapterEntity("chapter-$index", notebookId, "Chapter $index", 0, index),
                )
            }
        }

        repository.deleteChapter("chapter-5000")

        val chapters = repository.getChapters(notebookId)
        assertEquals(10_000, chapters.size)
        assertEquals((0 until 10_000).toList(), chapters.map(ChapterEntity::orderIndex))
    }

    @Test
    fun pageCannotUseChapterFromAnotherNotebook() = runTest {
        val firstNotebook = repository.createNotebook(request())
        val secondNotebook = repository.createNotebook(request())
        val page = repository.getPages(firstNotebook).single()
        val foreignChapter = repository.createChapter(secondNotebook, "Foreign", 0)

        val failure = runCatching { repository.assignPageToChapter(page.id, foreignChapter) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(null, repository.getPages(firstNotebook).single().chapterId)
    }

    @Test
    fun deletingLastPdfPageReturnsOrphanedSourceAsset() = runTest {
        val notebookId = repository.createNotebook(request())
        val imported =
            repository.importPdf(
                notebookId,
                "source.pdf",
                "Source.pdf",
                100,
                "0".repeat(64),
                listOf(PdfPageSpec(595, 842)),
            )

        val orphaned = repository.deletePage(imported.pageIds.single())

        assertEquals("source.pdf", orphaned)
        assertTrue(repository.getPdfSources(notebookId).isEmpty())
    }

    private fun request() =
        CreateNotebookRequest(
            title = "Physics",
            coverColor = CoverColor.PERIWINKLE,
            coverPattern = CoverPattern.SOLID,
            paper = PaperTemplate.GRID,
            orientation = PageOrientation.PORTRAIT,
            fingerDrawing = false,
        )
}
