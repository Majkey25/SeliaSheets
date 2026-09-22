package com.majkeylab.seliadocs.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.PageMode
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.data.decodeAnnotationRects
import com.majkeylab.seliadocs.settings.SettingsRepository
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfStudyEditorFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()
    private var notebookId: String? = null
    private val additionalNotebookIds = mutableListOf<String>()
    private val capturedAssetIds = mutableSetOf<String>()
    private var source: File? = null
    private var previousImageOcr: Boolean? = null
    private var previousHighlighterColor: Int? = null
    private lateinit var title: String
    private lateinit var editor: EditorViewModel

    @After
    fun cleanUp() {
        if (::editor.isInitialized) rule.runOnUiThread {
            editor.dismissPdfSelection()
            ViewModelProvider(rule.activity)["editor-session-holder", EditorSessionHolder::class.java]
                .viewModelStore.clear()
        }
        (listOfNotNull(notebookId) + additionalNotebookIds).forEach { id -> runBlocking {
            val repository = repository()
            val assets = repository.getPdfSources(id).map { it.assetId } +
                repository.getPages(id).flatMap { repository.getElements(it.id) }.mapNotNull { it.assetId }
            repository.deleteNotebook(id)
            val store = AssetStore(File(rule.activity.filesDir, "assets"))
            assets.forEach { store.file(it).delete() }
        } }
        val assetStore = AssetStore(File(rule.activity.filesDir, "assets"))
        capturedAssetIds.forEach { assetStore.file(it).delete() }
        source?.delete()
        previousImageOcr?.let { previous -> runBlocking {
            SettingsRepository.create(rule.activity.application).update {
                it.copy(imageOcr = previous, highlighterColorArgb = previousHighlighterColor ?: it.highlighterColorArgb)
            }
        } }
    }

    @Test
    fun notebookSearchFindsUnannotatedPdfTextAndKeepsTypedDraft() {
        openImportedPdf(imageOnly = false)
        val pdfPage = requireNotNull(editor.state.value.selectedPage)
        val writingPage = editor.state.value.pages.first { it.pageMode != PageMode.PDF.name }
        rule.runOnUiThread { editor.selectPage(writingPage.id) }
        rule.waitUntil(10_000) { editor.state.value.selectedPage?.id == writingPage.id }
        selectTool("type")
        rule.onNodeWithTag("page-text").performTextInput("Saved before PDF search")
        if (hasTag("compact-more")) {
            rule.onNodeWithTag("compact-more").performClick()
            rule.onNodeWithTag("compact-more-search").performClick()
        } else rule.onNodeWithContentDescription("Search").performClick()
        rule.waitUntil(10_000) { hasTag("search-query") }
        rule.onNodeWithTag("search-query").performTextInput("Alpha")
        rule.waitUntil(30_000) {
            editor.state.value.searchQuery == "Alpha" && !editor.state.value.searching &&
                editor.state.value.searchResults.any { it.pdfMatch != null }
        }
        rule.onNodeWithTag("search-result-${pdfPage.pageIndex}").performClick()
        rule.waitUntil(10_000) {
            editor.state.value.selectedPage?.id == pdfPage.id && editor.state.value.pdfSearchHighlight != null
        }
        rule.onNodeWithTag("pdf-search-highlight").assertIsDisplayed()
        assertNull("Search must not create an editable annotation", editor.state.value.pdfSelection)
        runBlocking {
            assertEquals("Saved before PDF search", repository().getBlocks(writingPage.id).single().text)
            assertTrue(repository().getElements(pdfPage.id).isEmpty())
        }
    }

    @Test
    fun capturedDiagramCanBeMovedResizedUndoneAndOpenedAtItsSource() {
        openImportedPdf(imageOnly = false)
        val sourcePageId = requireNotNull(editor.state.value.selectedPage).id
        val destination = editor.state.value.pages.first { it.pageMode != PageMode.PDF.name }
        selectTool("lasso")
        lassoPdfText(0.55f, 0.54f, 0.88f, 0.72f)
        rule.waitUntil(30_000) { editor.state.value.pdfRegion != null && !editor.state.value.pdfSelecting }
        assertNull("Diagram capture must work without selectable text", editor.state.value.pdfSelection)
        copySelectionTo(title, destination.pageIndex + 1, asImage = true)
        rule.runOnUiThread { editor.selectPage(destination.id) }
        rule.waitUntil(10_000) {
            editor.state.value.selectedPage?.id == destination.id && editor.state.value.elements.any { it.kind == ElementKind.IMAGE.name }
        }
        val captured = editor.state.value.elements.single { it.kind == ElementKind.IMAGE.name }
        assertEquals(sourcePageId, captured.sourcePageId)
        assertEquals(1, decodeAnnotationRects(captured.sourceRect).size)
        val assetId = requireNotNull(captured.assetId)
        capturedAssetIds += assetId
        val file = AssetStore(File(rule.activity.filesDir, "assets")).requireFile(assetId)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
        try {
            var blue = 0
            for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                val color = bitmap.getPixel(x, y)
                if (Color.blue(color) > 160 && Color.red(color) < 80 && Color.green(color) < 140) blue++
            }
            assertTrue("Captured image must contain the blue source diagram", blue > 20)
        } finally { bitmap.recycle() }
        tapElement(captured.id)
        repeat(2) {
            val before = editor.state.value.elements.single { it.id == captured.id }
            rule.onNodeWithTag("element-move-handle").assertIsDisplayed().performTouchInput {
                swipe(center, center + Offset(50f, 30f), 160)
            }
            rule.waitUntil(10_000) {
                editor.state.value.elements.firstOrNull { it.id == captured.id }?.let { it.x > before.x + 1f && it.y > before.y + 1f } == true
            }
        }
        var beforeLastResize = editor.state.value.elements.single { it.id == captured.id }
        repeat(2) {
            val before = editor.state.value.elements.single { it.id == captured.id }
            beforeLastResize = before
            rule.onNodeWithTag("element-resize-handle").assertIsDisplayed().performTouchInput {
                swipe(center, center + Offset(-60f, -40f), 160)
            }
            rule.waitUntil(10_000) {
                editor.state.value.elements.firstOrNull { it.id == captured.id }?.let { it.width < before.width - 1f && it.height < before.height - 1f } == true
            }
        }
        val resized = editor.state.value.elements.single { it.id == captured.id }
        assertEquals(captured.assetId, resized.assetId)
        assertEquals(captured.sourceRect, resized.sourceRect)
        historyAction("undo")
        rule.waitUntil(10_000) { editor.state.value.elements.firstOrNull { it.id == captured.id } == beforeLastResize }
        historyAction("redo")
        rule.waitUntil(10_000) { editor.state.value.elements.firstOrNull { it.id == captured.id } == resized }
        rule.onNodeWithTag("element-${captured.id}").assertIsDisplayed()
        assertTrue("Undo/Redo must retain the captured image asset", file.isFile)
        runBlocking { assertEquals(resized, repository().getElements(destination.id).single()) }
        rule.onNodeWithText("Open source page").performScrollTo().performClick()
        rule.waitUntil(10_000) { editor.state.value.selectedPage?.id == sourcePageId }
        rule.waitUntil(30_000) { runCatching { rule.onNodeWithTag("pdf-rendered-page").assertIsDisplayed() }.isSuccess }
        assertEquals(sourcePageId, editor.state.value.selectedPage?.id)
    }

    @Test
    fun samePageExcerptUsesTheUiDestinationAndSupportsUndoRedo() {
        openImportedPdf(imageOnly = false)
        val page = requireNotNull(editor.state.value.selectedPage)
        selectTool("lasso")
        lassoPdfText()
        rule.waitUntil(30_000) { editor.state.value.pdfSelection != null }
        val selectedText = requireNotNull(editor.state.value.pdfSelection).text
        copySelectionTo(title, page.pageIndex + 1)
        rule.waitUntil(10_000) { editor.state.value.elements.any { it.kind == ElementKind.TEXT.name && it.sourcePageId == page.id } }
        val excerpt = editor.state.value.elements.single { it.kind == ElementKind.TEXT.name }
        assertEquals(selectedText, excerpt.text)
        assertEquals(1, decodeAnnotationRects(excerpt.sourceRect).size)
        rule.onNodeWithTag("element-${excerpt.id}").assertIsDisplayed().assertTextContains(selectedText)
        historyAction("undo")
        rule.waitUntil(10_000) { editor.state.value.elements.isEmpty() && editor.state.value.canRedo }
        rule.onNodeWithTag("element-${excerpt.id}").assertDoesNotExist()
        historyAction("redo")
        rule.waitUntil(10_000) { editor.state.value.elements.any { it.id == excerpt.id } }
        rule.onNodeWithTag("element-${excerpt.id}").assertIsDisplayed().assertTextContains(selectedText)
        runBlocking { assertEquals(excerpt, repository().getElements(page.id).single()) }
    }

    @Test
    fun sourceLinkNavigatesAcrossNotebooksAndFlushesRetainedDraftBeforeLeaving() {
        openImportedPdf(imageOnly = false)
        val sourceNotebookId = requireNotNull(notebookId)
        val sourcePageId = requireNotNull(editor.state.value.selectedPage).id
        val destinationTitle = "Study excerpts ${System.nanoTime()}"
        val destinationId = runBlocking { repository().createNotebook(CreateNotebookRequest(
            destinationTitle, CoverColor.PERIWINKLE, CoverPattern.SOLID,
            PaperTemplate.BLANK, PageOrientation.PORTRAIT, false)) }
        additionalNotebookIds += destinationId
        val destinationPageId = runBlocking { repository().getPages(destinationId).single().id }
        selectTool("lasso")
        lassoPdfText()
        rule.waitUntil(30_000) { editor.state.value.pdfSelection != null }
        copySelectionTo(destinationTitle, 1)
        rule.waitUntil(10_000) { runBlocking { repository().getElements(destinationPageId).size == 1 } }
        val excerpt = runBlocking { repository().getElements(destinationPageId).single() }
        assertEquals(sourcePageId, excerpt.sourcePageId)

        returnToLibrary()
        openNotebook(destinationTitle)
        selectTool("lasso")
        tapElement(excerpt.id)
        rule.onNodeWithText("Open source page").performScrollTo().assertIsDisplayed().performClick()
        rule.waitUntil(30_000) { runCatching { rule.onNodeWithTag("pdf-rendered-page").assertIsDisplayed() }.isSuccess }
        editor = currentEditor()
        assertEquals(sourceNotebookId, editor.state.value.notebook?.id)
        assertEquals(sourcePageId, editor.state.value.selectedPage?.id)

        returnToLibrary()
        openNotebook(destinationTitle)
        selectTool("type")
        val acquired = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val owner = CoroutineScope(Dispatchers.IO).launch {
            LibraryMutationGate.withLock { acquired.countDown(); release.await() }
        }
        val draft = "Keep this draft before opening the PDF source"
        try {
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            rule.onNodeWithTag("page-text").assertIsDisplayed().performTextInput(draft)
            rule.runOnUiThread {
                ViewModelProvider(rule.activity)["editor-session-holder", EditorSessionHolder::class.java]
                    .requestAction(EditorAction.OpenSource(excerpt.id))
            }
            rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("page-text").assertIsNotEnabled() }.isSuccess }
            rule.onNodeWithTag("page-text").assertTextContains(draft)
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithTag("page-text").assertIsDisplayed().assertIsNotEnabled().assertTextContains(draft) }.isSuccess
            }
            release.complete(Unit)
            rule.waitUntil(30_000) { runCatching { rule.onNodeWithTag("pdf-rendered-page").assertIsDisplayed() }.isSuccess }
            editor = currentEditor()
            assertEquals(sourceNotebookId, editor.state.value.notebook?.id)
            assertEquals(sourcePageId, editor.state.value.selectedPage?.id)
            runBlocking {
                assertEquals(draft, repository().getBlocks(destinationPageId).single().text)
                assertEquals(excerpt, repository().getElements(destinationPageId).single())
            }
        } finally {
            release.complete(Unit)
            runBlocking { owner.join() }
        }
    }

    private fun copySelectionTo(notebookTitle: String, pageNumber: Int, asImage: Boolean = false) {
        val action = if (asImage) "Capture to notebook" else "Copy to notebook"
        val inDialog = hasAnyAncestor(hasTestTag("excerpt-destination"))
        var stage = "opening $action"
        try {
            rule.onNode(hasText(action) and hasClickAction()).performScrollTo().performClick()
            stage = "waiting for notebook $notebookTitle"
            rule.waitUntil(10_000) {
                runCatching { rule.onNode(hasText(notebookTitle) and hasClickAction() and inDialog).fetchSemanticsNode() }.isSuccess
            }
            rule.onNode(hasText(notebookTitle) and hasClickAction() and inDialog).performScrollTo().performClick()
            stage = "waiting for Page $pageNumber"
            rule.waitUntil(10_000) {
                runCatching { rule.onNode(hasText("Page $pageNumber") and hasClickAction() and inDialog).fetchSemanticsNode() }.isSuccess
            }
            rule.onNode(hasText("Page $pageNumber") and hasClickAction() and inDialog).performScrollTo().performClick()
            stage = "waiting for excerpt save"
            rule.waitUntil(10_000) {
                editor.state.value.pdfSelection == null && editor.state.value.pdfRegion == null && !editor.state.value.pdfSelecting
            }
        } catch (failure: Throwable) {
            runCatching { savePageScreenshot("pdf-excerpt-destination.png") }.exceptionOrNull()?.let(failure::addSuppressed)
            val tree = runCatching {
                rule.onAllNodes(isRoot()).printToLog("PdfExcerptQA", 12)
                rule.onAllNodes(isRoot(), useUnmergedTree = true).printToLog("PdfExcerptQA", 12)
                "Merged:\n${rule.onAllNodes(isRoot()).printToString(12)}\nUnmerged:\n" +
                    rule.onAllNodes(isRoot(), useUnmergedTree = true).printToString(12)
            }.getOrElse { "Semantics unavailable: ${it.message}" }
            runCatching {
                File(rule.activity.getExternalFilesDir(null), "pdf-excerpt-destination.txt").writeText("$stage\n$tree")
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw AssertionError("Excerpt UI failed while $stage\n$tree", failure)
        }
    }

    private fun returnToLibrary() {
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(10_000) { !hasTag("editor-top-bar") }
    }

    private fun openNotebook(notebookTitle: String) {
        rule.onNodeWithContentDescription("Open $notebookTitle").assertIsDisplayed().performClick()
        rule.waitUntil(10_000) { hasTag("editor-top-bar") }
        editor = currentEditor()
        rule.waitUntil(10_000) { editor.state.value.selectedPage != null }
    }

    @Test
    fun lassoHighlightSurvivesUndoRedoReopenAndNearbyTyping() {
        openImportedPdf(imageOnly = false)
        val pdfPageId = requireNotNull(editor.state.value.selectedPage).id
        val blankPageId = editor.state.value.pages.first { it.pageMode != PageMode.PDF.name }.id
        rule.waitUntil(10_000) { markupPixels().second > 20 }
        val baseline = markupPixels()
        if (baseline.first != 0) savePageScreenshot("pdf-study-before-highlight.png")
        assertEquals("Unmarked PDF pixels=$baseline; screenshot=pdf-study-before-highlight.png", 0, baseline.first)
        selectTool("lasso")
        lassoPdfText()
        rule.waitUntil(30_000) { editor.state.value.pdfSelection != null }
        val selection = requireNotNull(editor.state.value.pdfSelection)
        assertTrue(selection.text.contains("Alpha") && selection.text.contains("Gamma"))
        assertEquals(Build.VERSION.SDK_INT < 35, selection.isOcr)
        rule.onNodeWithTag("pdf-text-selection-preview").assertIsDisplayed()
        rule.onNodeWithTag("pdf-apply-highlight").performScrollTo().assertIsDisplayed().performClick()
        rule.waitUntil(10_000) { editor.state.value.elements.any { it.kind == ElementKind.HIGHLIGHT.name } }
        val highlight = editor.state.value.elements.single { it.kind == ElementKind.HIGHLIGHT.name }
        assertEquals(selection.text, highlight.text)
        assertEquals(pdfPageId, highlight.sourcePageId)
        val rectangles = decodeAnnotationRects(highlight.annotationRects)
        assertEquals(selection.bounds.size, rectangles.size)
        assertTrue(rectangles.all { it.left >= 0f && it.top >= 0f && it.right <= 1f && it.bottom <= 1f })
        assertEquals(1, decodeAnnotationRects(highlight.sourceRect).size)
        rule.onNodeWithTag("element-${highlight.id}").assertIsDisplayed()
        assertVisibleHighlightAndReadableText()

        historyAction("undo")
        rule.waitUntil(10_000) { editor.state.value.elements.isEmpty() && editor.state.value.canRedo }
        rule.onNodeWithTag("element-${highlight.id}").assertDoesNotExist()
        rule.waitUntil(5_000) { markupPixels().first == 0 }
        historyAction("redo")
        rule.waitUntil(10_000) { editor.state.value.elements.any { it.id == highlight.id } }
        rule.onNodeWithTag("element-${highlight.id}").assertIsDisplayed()
        assertVisibleHighlightAndReadableText()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(10_000) { hasTag("editor-top-bar").not() }
        rule.onNodeWithContentDescription("Open $title").assertIsDisplayed().performClick()
        rule.waitUntil(10_000) { hasTag("editor-top-bar") }
        editor = currentEditor()
        rule.waitUntil(10_000) { editor.state.value.pages.size == 2 }
        rule.runOnUiThread { editor.selectPage(pdfPageId) }
        rule.waitUntil(10_000) { editor.state.value.selectedPage?.id == pdfPageId && editor.state.value.elements.any { it.id == highlight.id } }
        rule.onNodeWithTag("element-${highlight.id}").assertIsDisplayed()
        assertVisibleHighlightAndReadableText()
        runBlocking { assertEquals(highlight, repository().getElements(pdfPageId).single()) }

        rule.runOnUiThread { editor.selectPage(blankPageId) }
        rule.waitUntil(10_000) { editor.state.value.selectedPage?.id == blankPageId }
        selectTool("type")
        rule.onNodeWithTag("page-text").assertIsDisplayed().performTextInput("Nearby lecture notes")
        selectTool("pen")
        runBlocking {
            assertEquals("Nearby lecture notes", repository().getBlocks(blankPageId).single().text)
            assertEquals(highlight, repository().getElements(pdfPageId).single())
        }
    }

    @Test
    fun ocrSelectionIsClearedByToolAndPageChanges() {
        openImportedPdf(imageOnly = true)
        val page = requireNotNull(editor.state.value.selectedPage)
        val blankPageId = editor.state.value.pages.first { it.id != page.id }.id
        selectTool("lasso")
        lassoPdfText()
        rule.waitUntil(30_000) { editor.state.value.pdfSelection != null }
        assertTrue(requireNotNull(editor.state.value.pdfSelection).isOcr)
        selectTool("pen")
        assertNull(editor.state.value.pdfSelection)
        assertFalse(editor.state.value.pdfSelecting)
        rule.onNodeWithTag("pdf-selection-bar").assertDoesNotExist()

        rule.runOnUiThread {
            editor.selectTool(EditorTool.LASSO)
            editor.selectContent(page.id, listOf(CanvasPoint(48f, 152f), CanvasPoint(516f, 224f)))
            editor.selectPage(blankPageId)
        }
        rule.waitUntil(10_000) { editor.state.value.selectedPage?.id == blankPageId }
        assertNull(editor.state.value.pdfSelection)
        assertFalse(editor.state.value.pdfSelecting)
        rule.onNodeWithTag("pdf-text-selection-preview").assertDoesNotExist()
        rule.runOnUiThread { editor.selectPage(page.id) }
        rule.waitUntil(10_000) { editor.state.value.selectedPage?.id == page.id }
        lassoPdfText()
        rule.waitUntil(30_000) { editor.state.value.pdfSelection != null }
        assertEquals("Alpha Beta Gamma", editor.state.value.pdfSelection?.text)
        rule.onNodeWithTag("pdf-text-selection-preview").assertIsDisplayed()
    }

    private fun openImportedPdf(imageOnly: Boolean) {
        val settings = SettingsRepository.create(rule.activity.application)
        runBlocking {
            val previous = settings.settings.first()
            previousImageOcr = previous.imageOcr
            previousHighlighterColor = previous.highlighterColorArgb
            settings.update { it.copy(imageOcr = true, highlighterColorArgb = 0x66FFD54F) }
        }
        title = "PDF study ${System.nanoTime()}"
        rule.onNodeWithContentDescription("New notebook").performClick()
        rule.onNodeWithContentDescription("Notebook name").performTextReplacement(title)
        rule.onNodeWithText("Create notebook").performClick()
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithContentDescription("Open $title").assertIsDisplayed() }.isSuccess
        }
        rule.onNodeWithContentDescription("Open $title").performClick()
        rule.waitUntil(10_000) { hasTag("editor-top-bar") }
        editor = currentEditor()
        rule.waitUntil(10_000) { editor.state.value.selectedPage != null }
        notebookId = requireNotNull(editor.state.value.notebook).id
        val file = File(rule.activity.cacheDir, "pdf-study-${System.nanoTime()}.pdf")
        source = file
        createPdf(file, imageOnly)
        rule.runOnUiThread { editor.importPdf(Uri.fromFile(file)) }
        rule.waitUntil(30_000) { editor.state.value.pages.size == 2 && editor.state.value.selectedPage?.pageMode == PageMode.PDF.name }
        rule.waitUntil(30_000) { runCatching { rule.onNodeWithTag("pdf-rendered-page").assertIsDisplayed() }.isSuccess }
    }

    private fun lassoPdfText(left: Float = 0.08f, top: Float = 0.19f, right: Float = 0.86f, bottom: Float = 0.28f) {
        rule.waitUntil(10_000) { rule.runOnIdle { rule.activity.window.decorView.hasWindowFocus() } }
        val points = rule.runOnIdle {
            fun find(view: View): InkCanvasView? {
                if (view is InkCanvasView && view.isEnabled) return view
                if (view is ViewGroup) repeat(view.childCount) { index -> find(view.getChildAt(index))?.let { return it } }
                return null
            }
            val canvas = requireNotNull(find(rule.activity.window.decorView))
            assertEquals(EditorTool.LASSO, canvas.tool)
            val location = IntArray(2)
            canvas.getLocationOnScreen(location)
            listOf(left to top, right to top, right to bottom, left to bottom, left to top)
                .map { (x, y) -> (location[0] + canvas.width * x) to (location[1] + canvas.height * y) }
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val time = SystemClock.uptimeMillis()
        fun send(action: Int, point: Pair<Float, Float>, offset: Long) {
            val event = MotionEvent.obtain(time, time + offset, action, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }),
                arrayOf(MotionEvent.PointerCoords().apply { x = point.first; y = point.second; pressure = 0.7f }),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { assertTrue("Native lasso input rejected", automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, points.first(), 0)
        try { points.drop(1).forEachIndexed { index, point -> send(MotionEvent.ACTION_MOVE, point, (index + 1) * 16L) } }
        finally { send(MotionEvent.ACTION_UP, points.last(), 80) }
    }

    private fun selectTool(name: String) {
        val tag = if (hasTag("compact-tool-$name")) "compact-tool-$name" else "toolbar-tool-$name"
        rule.onNodeWithTag(tag).performClick()
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(tag).assertIsSelected() }.isSuccess }
    }

    private fun historyAction(name: String) {
        val tag = if (hasTag("compact-$name")) "compact-$name" else "toolbar-$name"
        rule.onNodeWithTag(tag).assertIsEnabled().performClick()
    }

    private fun tapElement(id: String) {
        rule.onNodeWithTag("element-$id").assertIsDisplayed().performClick()
        try {
            rule.waitUntil(5_000) { editor.state.value.selectedElementId == id }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            runCatching { savePageScreenshot("pdf-excerpt-selection.png") }.exceptionOrNull()?.let(failure::addSuppressed)
            val state = editor.state.value
            val details = "wanted=$id selected=${state.selectedElementId} page=${state.selectedPage?.id} tool=${state.tool} " +
                "elements=${state.elements.map { listOf(it.id, it.x, it.y, it.width, it.height) }}"
            val tree = runCatching {
                rule.onAllNodes(isRoot()).printToLog("PdfExcerptQA", 12)
                rule.onAllNodes(isRoot()).printToString(12)
            }.getOrElse { "Semantics unavailable: ${it.message}" }
            runCatching {
                File(rule.activity.getExternalFilesDir(null), "pdf-excerpt-selection.txt").writeText("$details\n$tree")
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw AssertionError("Element tap did not select its target: $details\n$tree", failure)
        }
        assertEquals(id, editor.state.value.selectedElementId)
    }

    private fun assertVisibleHighlightAndReadableText() {
        try {
            rule.waitUntil(10_000) { markupPixels().let { (yellow, black) -> yellow > 30 && black > 20 } }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            savePageScreenshot("pdf-study-highlight-failed.png")
            throw AssertionError("PDF markup pixels=${markupPixels()}; screenshot=pdf-study-highlight-failed.png", failure)
        }
        val (yellow, black) = markupPixels()
        assertTrue("Selected highlight must be visible in the rendered page", yellow > 30)
        assertTrue("Highlight must preserve black PDF source text", black > 20)
        savePageScreenshot("pdf-study-highlight-visible.png")
    }

    private fun savePageScreenshot(name: String) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(rule.activity.getExternalFilesDir(null), name).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun markupPixels(): Pair<Int, Int> {
        val paper = rule.onNodeWithTag("page-paper").fetchSemanticsNode().boundsInRoot
        val offset = IntArray(2)
        rule.runOnUiThread { rule.activity.findViewById<View>(android.R.id.content).getLocationOnScreen(offset) }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        return try {
            var yellow = 0
            var black = 0
            val left = (offset[0] + paper.left + paper.width * 0.09f).toInt().coerceIn(0, bitmap.width)
            val right = (offset[0] + paper.left + paper.width * 0.75f).toInt().coerceIn(0, bitmap.width)
            val top = (offset[1] + paper.top + paper.height * 0.19f).toInt().coerceIn(0, bitmap.height)
            val bottom = (offset[1] + paper.top + paper.height * 0.28f).toInt().coerceIn(0, bitmap.height)
            for (y in top until bottom) for (x in left until right) {
                val color = bitmap.getPixel(x, y)
                if (Color.red(color) > 200 && Color.green(color) > 150 && Color.blue(color) < 210 &&
                    Color.red(color) - Color.blue(color) > 30 && Color.green(color) - Color.blue(color) > 15) yellow++
                if (Color.red(color) < 70 && Color.green(color) < 70 && Color.blue(color) < 70) black++
            }
            yellow to black
        } finally { bitmap.recycle() }
    }

    private fun hasTag(tag: String): Boolean = runCatching { rule.onNodeWithTag(tag).fetchSemanticsNode() }.isSuccess
    private fun currentEditor(): EditorViewModel = rule.runOnIdle {
        val holder = ViewModelProvider(rule.activity)["editor-session-holder", EditorSessionHolder::class.java]
        ViewModelProvider(holder)["editor", EditorViewModel::class.java]
    }
    private fun repository() = SeliaDocsRepository(SeliaDocsDatabase.get(rule.activity.application))

    private fun createPdf(file: File, imageOnly: Boolean) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 36f }
            if (imageOnly) {
                val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    Canvas(bitmap).drawText("Alpha Beta Gamma", 60f, 200f, paint)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                } finally { bitmap.recycle() }
            } else page.canvas.drawText("Alpha Beta Gamma", 60f, 200f, paint)
            page.canvas.drawRect(350f, 450f, 500f, 550f, Paint().apply { color = Color.BLUE })
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
    }
}
