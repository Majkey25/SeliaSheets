package com.majkeylab.seliadocs.editor

import android.net.Uri
import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.majkeylab.seliadocs.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.SeliaDocsApp
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorCompactUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun savingFirstHighlightDoesNotHideSecondPendingHighlight() = assertPendingHighlightGroup(eraseBetween = false)

    @Test
    fun erasingBetweenPendingHighlightsDoesNotLeaveGhostInk() = assertPendingHighlightGroup(eraseBetween = true)

    private fun assertPendingHighlightGroup(eraseBetween: Boolean) {
        openCompactEditor()
        selectTool("highlighter")
        val editor = rule.runOnIdle {
            val holder = ViewModelProvider(rule.activity)["editor-session-holder", EditorSessionHolder::class.java]
            ViewModelProvider(holder)["editor", EditorViewModel::class.java]
        }
        val firstAcquired = CountDownLatch(1)
        val secondAcquired = CountDownLatch(1)
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val scope = CoroutineScope(Dispatchers.IO)
        val firstOwner = scope.launch {
            LibraryMutationGate.withLock { firstAcquired.countDown(); releaseFirst.await() }
        }
        var secondOwner: kotlinx.coroutines.Job? = null
        var canvasView: InkCanvasView? = null
        fun drawAndHandOff(origin: Float, inputToolType: Int = MotionEvent.TOOL_TYPE_STYLUS) {
            val handedOff = AtomicBoolean(false)
            drawNativeStrokeThen(origin, EditorTool.HIGHLIGHTER, inputToolType) { canvas ->
                canvasView = canvas
                rule.activity.lifecycleScope.launch { canvas.awaitPendingCommits(); handedOff.set(true) }
            }
            rule.waitUntil(10_000) { handedOff.get() }
        }
        fun secondBlue(xFraction: Float = 0.7f, yFraction: Float = 0.65f): Int {
            val paper = rule.onNodeWithTag("page-paper").fetchSemanticsNode().boundsInRoot
            val offset = IntArray(2)
            rule.runOnUiThread { rule.activity.findViewById<View>(android.R.id.content).getLocationOnScreen(offset) }
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            return try {
                Color.blue(bitmap.getPixel((offset[0] + paper.left + paper.width * xFraction).toInt(),
                    (offset[1] + paper.top + paper.height * yFraction).toInt()))
            } finally { bitmap.recycle() }
        }
        val settings = SettingsRepository.create(rule.activity.application)
        val previous = runBlocking { settings.settings.first() }
        try {
            assertTrue(firstAcquired.await(5, TimeUnit.SECONDS))
            val before = secondBlue()
            val firstBefore = secondBlue(0.4f, 0.35f)
            drawAndHandOff(0.3f)
            if (eraseBetween) drawAndHandOff(0.3f, MotionEvent.TOOL_TYPE_ERASER)
            secondOwner = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                LibraryMutationGate.withLock { secondAcquired.countDown(); releaseSecond.await() }
            }
            drawAndHandOff(0.6f)
            rule.waitUntil(5_000) { secondBlue() < before - 15 }
            releaseFirst.complete(Unit)
            assertTrue(secondAcquired.await(5, TimeUnit.SECONDS))
            val originalBrush = rule.runOnIdle { requireNotNull(canvasView).brush }
            runBlocking { settings.update { it.copy(penWidth = if (it.penWidth == 4f) 5f else 4f) } }
            rule.waitUntil(5_000) { rule.runOnIdle { requireNotNull(canvasView).brush !== originalBrush } }
            rule.waitForIdle()
            assertTrue("Saving the first stroke hid another pending highlight", secondBlue() < before - 15)
            releaseSecond.complete(Unit)
            rule.waitUntil(10_000) { editor.state.value.strokes.size == if (eraseBetween) 1 else 2 }
            if (eraseBetween) rule.waitUntil(5_000) { secondBlue(0.4f, 0.35f) >= firstBefore - 5 }
            rule.waitUntil(5_000) { secondBlue() < before - 15 }
        } finally {
            releaseFirst.complete(Unit)
            releaseSecond.complete(Unit)
            runBlocking {
                firstOwner.join()
                secondOwner?.join()
                settings.update { it.copy(penWidth = previous.penWidth) }
            }
        }
    }

    @Test
    fun failedHighlighterSaveRemovesUnstoredInk() = assertHighlighterSaveOutcome(failSave = true)

    @Test
    fun queuedUndoDoesNotLeaveGhostHighlighter() = assertHighlighterSaveOutcome(failSave = false)

    private fun assertHighlighterSaveOutcome(failSave: Boolean) {
        openCompactEditor()
        selectTool("highlighter")
        val editor = rule.runOnIdle {
            val holder = ViewModelProvider(rule.activity)["editor-session-holder", EditorSessionHolder::class.java]
            ViewModelProvider(holder)["editor", EditorViewModel::class.java]
        }
        val pageId = requireNotNull(editor.state.value.selectedPage).id
        val database = SeliaDocsDatabase.get(rule.activity.application)
        if (failSave) runBlocking(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL(
                "CREATE TEMP TRIGGER qa_fail_highlight BEFORE INSERT ON strokes " +
                    "WHEN NEW.pageId = '${pageId.replace("'", "''")}' " +
                    "BEGIN SELECT RAISE(ABORT, 'Forced highlight save failure'); END",
            )
        }
        val acquired = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val owner = CoroutineScope(Dispatchers.IO).launch {
            LibraryMutationGate.withLock { acquired.countDown(); release.await() }
        }
        fun yellowPixels(): Int {
            val paper = rule.onNodeWithTag("page-paper").fetchSemanticsNode().boundsInRoot
            val offset = IntArray(2)
            rule.runOnUiThread {
                rule.activity.findViewById<View>(android.R.id.content).getLocationOnScreen(offset)
            }
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            return try {
                var count = 0
                for (y in (paper.top.toInt() + offset[1]).coerceAtLeast(0) until
                    (paper.bottom.toInt() + offset[1]).coerceAtMost(bitmap.height) step 2) {
                    for (x in (paper.left.toInt() + offset[0]).coerceAtLeast(0) until
                        (paper.right.toInt() + offset[0]).coerceAtMost(bitmap.width) step 2) {
                        val color = bitmap.getPixel(x, y)
                        if (Color.red(color) > 180 && Color.green(color) > 130 &&
                            Color.red(color) > Color.blue(color) + 30 && Color.green(color) > Color.blue(color) + 20) count++
                    }
                }
                count
            } finally { bitmap.recycle() }
        }
        try {
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            assertEquals(0, yellowPixels())
            val handedOff = AtomicBoolean(false)
            drawNativeStrokeThen(expectedTool = EditorTool.HIGHLIGHTER) { canvas ->
                rule.activity.lifecycleScope.launch {
                    canvas.awaitPendingCommits()
                    handedOff.set(true)
                }
            }
            rule.waitUntil(10_000) { handedOff.get() }
            rule.waitUntil(10_000) { yellowPixels() > 50 }
            if (!failSave) rule.runOnUiThread { editor.undo() }
            release.complete(Unit)
            rule.waitUntil(10_000) {
                val state = editor.state.value
                if (failSave) state.failed else state.canRedo
            }
            rule.waitUntil(5_000) { yellowPixels() == 0 }
            runBlocking { assertTrue(SeliaDocsRepository(database).getStrokes(pageId).isEmpty()) }
            rule.onNodeWithTag("compact-tool-highlighter").assertIsSelected()
        } finally {
            release.complete(Unit)
            runBlocking(Dispatchers.IO) {
                owner.join()
                if (failSave) database.openHelper.writableDatabase.execSQL("DROP TRIGGER qa_fail_highlight")
            }
        }
    }

    @Test
    fun highlighterSurvivesSettingsRefreshWhileSaving() {
        openCompactEditor()
        selectTool("type")
        rule.onNodeWithTag("page-text").performTextInput("Text and highlights stay together.\n".repeat(12))
        selectTool("highlighter")
        val paper = rule.onNodeWithTag("page-paper").fetchSemanticsNode().boundsInRoot
        val offset = IntArray(2)
        rule.runOnUiThread {
            rule.activity.findViewById<View>(android.R.id.content).getLocationOnScreen(offset)
        }
        val x = (offset[0] + paper.left + paper.width * 0.4f).toInt()
        val y = (offset[1] + paper.top + paper.height * 0.35f).toInt()
        fun blue(): Int {
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            return try { Color.blue(bitmap.getPixel(x, y)) } finally { bitmap.recycle() }
        }
        fun findCanvas(view: View): InkCanvasView? {
            if (view is InkCanvasView) return view
            if (view is ViewGroup) repeat(view.childCount) { index ->
                findCanvas(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        val canvas = rule.runOnIdle { requireNotNull(findCanvas(rule.activity.window.decorView)) }
        val settings = SettingsRepository.create(rule.activity.application)
        val previous = runBlocking { settings.settings.first() }
        val acquired = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val owner = CoroutineScope(Dispatchers.IO).launch {
            LibraryMutationGate.withLock {
                acquired.countDown()
                release.await()
            }
        }
        try {
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            val before = blue()
            drawNativeStrokeThen(expectedTool = EditorTool.HIGHLIGHTER) {}
            val handedOff = AtomicBoolean(false)
            rule.runOnUiThread {
                rule.activity.lifecycleScope.launch {
                    canvas.awaitPendingCommits()
                    handedOff.set(true)
                }
            }
            rule.waitUntil(10_000) { handedOff.get() }
            rule.waitUntil(5_000) { blue() < before - 15 }
            val originalBrush = rule.runOnIdle { canvas.brush }
            runBlocking { settings.update { it.copy(penWidth = if (it.penWidth == 4f) 5f else 4f) } }
            rule.waitUntil(5_000) { rule.runOnIdle { canvas.brush !== originalBrush } }
            rule.waitForIdle()
            assertTrue("A settings refresh hid unsaved highlighter ink", blue() < before - 15)
            rule.onNodeWithTag("compact-tool-highlighter").assertIsSelected()
        } finally {
            release.complete(Unit)
            runBlocking {
                owner.join()
                settings.update { it.copy(penWidth = previous.penWidth) }
            }
        }
    }

    @Test
    fun savedHighlighterStaysVisibleWithoutSwitchingTools() {
        val title = openCompactEditor()
        selectTool("highlighter")
        val paper = rule.onNodeWithTag("page-paper").fetchSemanticsNode().boundsInRoot
        val offset = IntArray(2)
        rule.runOnUiThread {
            rule.activity.findViewById<View>(android.R.id.content).getLocationOnScreen(offset)
        }
        val x = (offset[0] + paper.left + paper.width * 0.4f).toInt()
        val y = (offset[1] + paper.top + paper.height * 0.35f).toInt()
        fun blue(): Int {
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            return try { Color.blue(bitmap.getPixel(x, y)) } finally { bitmap.recycle() }
        }
        val before = blue()
        drawNativeStrokeThen(expectedTool = EditorTool.HIGHLIGHTER) {}
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithTag("compact-undo").assertIsEnabled() }.isSuccess
        }
        rule.onNodeWithTag("compact-tool-highlighter").assertIsSelected()
        rule.waitUntil(5_000) { blue() < before - 15 }
        runBlocking {
            val repository = SeliaDocsRepository(SeliaDocsDatabase.get(rule.activity.application))
            val notebook = repository.getAllNotebooks().single { it.title == title }
            val strokes = repository.getPages(notebook.id).flatMap { repository.getStrokes(it.id) }
            assertEquals(1, strokes.size)
            assertEquals(BrushKind.HIGHLIGHTER.name, strokes.single().brushKind)
        }
    }

    @Test
    fun immediateBackWaitsForNativeInkAndPersistsTheStroke() {
        val title = openCompactEditor()
        drawNativeStrokeThen { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(15_000) {
            runCatching { rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode() }.isSuccess
        }
        runBlocking {
            val repository = SeliaDocsRepository(SeliaDocsDatabase.get(rule.activity.application))
            val notebook = repository.getAllNotebooks().single { it.title == title }
            val strokes = repository.getPages(notebook.id).flatMap { repository.getStrokes(it.id) }
            assertEquals("Back discarded or duplicated pending ink", 1, strokes.size)
        }
    }

    @Test
    fun immediateUndoTargetsNewestPendingInk() {
        val title = openCompactEditor()
        drawNativeStrokeThen {}
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithTag("compact-undo").assertIsEnabled() }.isSuccess
        }
        val undo = requireNotNull(rule.onNodeWithTag("compact-undo").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        drawNativeStrokeThen(origin = 0.6f) { undo() }
        rule.waitForIdle()
        rule.onNodeWithTag("compact-back").performClick()
        rule.waitUntil(15_000) {
            runCatching { rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode() }.isSuccess
        }
        runBlocking {
            val repository = SeliaDocsRepository(SeliaDocsDatabase.get(rule.activity.application))
            val notebook = repository.getAllNotebooks().single { it.title == title }
            val page = repository.getPages(notebook.id).first()
            val strokes = repository.getStrokes(page.id)
            assertEquals(1, strokes.size)
            assertEquals("Undo removed the preceding stroke instead of pending ink", page.widthPoints * 0.3f,
                strokes.single().toInkStroke().inputs[0].x, 1f)
        }
    }

    private fun drawNativeStrokeThen(
        origin: Float = 0.3f,
        expectedTool: EditorTool = EditorTool.PEN,
        inputToolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        action: (InkCanvasView) -> Unit,
    ) {
        rule.runOnUiThread {
            fun findCanvas(view: View): InkCanvasView? {
                if (view is InkCanvasView) return view
                if (view is ViewGroup) {
                    repeat(view.childCount) { index ->
                        findCanvas(view.getChildAt(index))?.let { return it }
                    }
                }
                return null
            }
            val canvas = requireNotNull(findCanvas(rule.activity.window.decorView))
            assertEquals(expectedTool, canvas.tool)
            val time = android.os.SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val event = MotionEvent.obtain(
                    time, time + index * 16L, action, 1,
                    arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = inputToolType }),
                    arrayOf(MotionEvent.PointerCoords().apply {
                        x = canvas.width * (origin + index * 0.1f)
                        y = canvas.height * (origin + index * 0.05f)
                        pressure = 0.7f
                    }),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
                )
                try { canvas.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            action(canvas)
        }
    }

    @Test
    fun sessionHolderRetainsSameSessionAndResetsDifferentSession() {
        val holder = EditorSessionHolder()
        holder.prepare("0:notebook-a")
        assertTrue(holder.acceptDraft("page-a", TextFieldValue("Draft")))
        assertTrue(holder.beginClose(EditorCloseIntent.BACK))

        holder.prepare("0:notebook-a")
        assertFalse(holder.mutationsAllowed())
        assertEquals("Draft", holder.draftFor("page-a")?.text)

        holder.prepare("1:notebook-b")
        assertTrue(holder.mutationsAllowed())
        assertNull(holder.draftFor("page-a"))
    }

    @Test
    fun sessionHolderDropsDraftForDeletedPage() {
        val holder = EditorSessionHolder()
        holder.prepare("0:notebook")
        assertTrue(holder.acceptDraft("deleted-page", TextFieldValue("Draft")))
        assertTrue(holder.beginClose(EditorCloseIntent.BACK))

        assertNull(holder.latestDraft(setOf("remaining-page")))
        assertNull(holder.draftFor("deleted-page"))
    }

    @Test
    fun sessionHolderKeepsCloseIntentAndDraftUntilSaveCompletes() {
        val holder = EditorSessionHolder()
        holder.prepare("0:notebook")
        val draft = InlineTextDraft("page", null, CanvasPoint(20f, 30f), "Draft")
        assertTrue(holder.beginInlineText(draft))
        assertTrue(holder.acceptDraft("page", TextFieldValue("Page draft")))
        holder.requestAction(EditorAction.SelectTool(EditorTool.PENCIL))
        val epoch = requireNotNull(holder.beginActionSave())
        holder.prepare("0:notebook")
        assertNull(holder.beginActionSave())
        assertFalse(holder.beginInlineText(draft.copy(text = "Rejected")))
        assertFalse(holder.acceptDraft("page", TextFieldValue("Rejected")))
        holder.requestAction(EditorAction.Close(EditorCloseIntent.BACK))
        holder.requestAction(EditorAction.AddText)
        assertNull(holder.takeReadyAction())
        assertEquals(draft, holder.inlineTextDraft.value)

        holder.completeActionSave(epoch, false)
        assertNull(holder.takeReadyAction())
        assertEquals(draft, holder.inlineTextDraft.value)
        holder.requestAction(EditorAction.Close(EditorCloseIntent.BACK))
        assertEquals(epoch, holder.beginActionSave())
        holder.clearInlineText()
        holder.completeActionSave(epoch, true)
        assertEquals(EditorAction.Close(EditorCloseIntent.BACK), holder.takeReadyAction())
        assertNull(holder.takeReadyAction())
    }

    @Test
    fun callbacksFromPreviousSessionCannotClearTheNewDraftOrPendingAction() {
        val holder = EditorSessionHolder()
        holder.prepare("0:old-notebook")
        holder.requestAction(EditorAction.FinishText)
        val oldEpoch = requireNotNull(holder.beginActionSave())
        holder.prepare("1:new-notebook")
        val draft = InlineTextDraft("new-page", null, CanvasPoint(20f, 30f), "New draft")
        assertTrue(holder.beginInlineText(draft))
        holder.requestAction(EditorAction.Close(EditorCloseIntent.BACK))
        val newEpoch = requireNotNull(holder.beginActionSave())

        assertFalse(holder.clearInlineText(oldEpoch))
        holder.completeActionSave(oldEpoch, false)
        holder.completeActionSave(oldEpoch, true)
        assertEquals(draft, holder.inlineTextDraft.value)
        assertEquals(EditorAction.Close(EditorCloseIntent.BACK), holder.actionState.value.pending)
        assertTrue(holder.actionState.value.saving)
        assertNull(holder.takeReadyAction())
        holder.completeActionSave(newEpoch, true)
        assertEquals(EditorAction.Close(EditorCloseIntent.BACK), holder.takeReadyAction())
        assertTrue(holder.beginClose(EditorCloseIntent.BACK))
        holder.completeClose(true, oldEpoch)
        assertFalse(holder.closeState.value.completed)
    }

    @Test
    fun pickerActionsRetainTheirPayloadUntilDraftSaveCompletes() {
        val source = Uri.parse("content://test/document/source")
        val destination = Uri.parse("content://test/document/export")
        val actions = listOf(
            EditorAction.ImportPdf(source),
            EditorAction.ExportPdf(destination),
            EditorAction.ImportImage("image-page", source, ocr = true),
        )
        actions.forEach { action ->
            val holder = EditorSessionHolder()
            holder.prepare("0:notebook")
            val draft = InlineTextDraft("draft-page", null, CanvasPoint(20f, 30f), "Draft before picker")
            assertTrue(holder.beginInlineText(draft))
            assertTrue(holder.acceptDraft("draft-page", TextFieldValue("Page text before picker")))
            holder.requestAction(action)
            val epoch = requireNotNull(holder.beginActionSave())
            holder.prepare("0:notebook")
            assertEquals(action, holder.actionState.value.pending)
            assertEquals(draft, holder.inlineTextDraft.value)
            assertNull(holder.takeReadyAction())
            assertFalse(holder.acceptDraft("draft-page", TextFieldValue("Rejected during save")))
            assertTrue(holder.clearInlineText(epoch))
            holder.completeActionSave(epoch, true)
            assertEquals(action, holder.takeReadyAction())
            assertNull(holder.takeReadyAction())
            holder.completeExecutingAction(epoch, action)
            assertFalse(holder.actionState.value.busy)
        }
    }

    @Test
    fun executingImportSurvivesRecreationAndKeepsBackQueuedUntilCompletion() {
        listOf(
            EditorAction.ImportPdf(Uri.parse("content://test/import.pdf")),
            EditorAction.ImportImage("page", Uri.parse("content://test/image.png"), ocr = false),
        ).forEach { action ->
            val holder = EditorSessionHolder()
            holder.prepare("0:notebook")
            holder.requestAction(action)
            val epoch = requireNotNull(holder.beginActionSave())
            holder.completeActionSave(epoch, true)
            assertEquals(action, holder.takeReadyAction())
            holder.prepare("0:notebook")
            assertEquals(action, holder.actionState.value.executing)
            assertTrue(holder.actionState.value.busy)
            assertFalse(holder.acceptDraft("page", TextFieldValue("Rejected during import")))
            assertFalse(holder.beginInlineText(InlineTextDraft("page", null, CanvasPoint(20f, 30f), "Rejected")))
            holder.requestAction(EditorAction.AddText)
            holder.requestAction(EditorAction.Close(EditorCloseIntent.BACK))
            holder.requestAction(EditorAction.AddText)
            assertNull(holder.beginActionSave())
            assertNull(holder.takeReadyAction())
            holder.completeExecutingAction(epoch - 1, action)
            assertEquals(action, holder.actionState.value.executing)

            holder.completeExecutingAction(epoch, action)
            assertEquals(EditorAction.Close(EditorCloseIntent.BACK), holder.actionState.value.pending)
            assertEquals(epoch, holder.beginActionSave())
            holder.completeActionSave(epoch, true)
            assertEquals(EditorAction.Close(EditorCloseIntent.BACK), holder.takeReadyAction())
            assertFalse(holder.actionState.value.busy)
        }
    }

    @Test
    fun compactEditorKeepsPrimaryActionsVisibleAndOneToolSelected() {
        val title = openCompactEditor()

        listOf("compact-undo", "compact-redo", "compact-more", "compact-page-location").forEach {
            rule.onNodeWithTag(it).assertIsDisplayed().assertHasClickAction()
        }
        val tools = listOf("type", "pen", "pencil", "highlighter", "eraser", "lasso")
        tools.forEach { rule.onNodeWithTag("compact-tool-$it").assertIsDisplayed() }
        rule.onNodeWithTag("compact-insert").assertIsDisplayed()
        rule.onNodeWithTag("compact-tool-pen").assertIsSelected()
        assertEquals(
            "Compact palette must expose exactly one selected tool",
            1,
            tools.count {
                rule.onNodeWithTag("compact-tool-$it")
                    .fetchSemanticsNode().config[SemanticsProperties.Selected]
            },
        )
        rule.onNodeWithTag("compact-page-location").assertTextContains(title)
        rule.onNodeWithTag("compact-page-location").assertTextContains("Page 1 of 1")
    }

    @Test
    fun compactMenusExposeSecondaryActions() {
        openCompactEditor()

        rule.onNodeWithTag("compact-more").performClick()
        listOf("add-page", "search", "export", "settings").forEach {
            rule.onNodeWithTag("compact-more-$it").assertIsDisplayed()
        }
        pressBack()

        rule.onNodeWithTag("compact-insert").performClick()
        listOf("text", "image", "pdf").forEach {
            rule.onNodeWithTag("compact-insert-$it").assertIsDisplayed()
        }
        rule.onNodeWithTag("compact-insert-shape").assertDoesNotExist()
        rule.onNodeWithTag("compact-insert-math").assertDoesNotExist()
    }

    @Test
    fun compactTextObjectWaitsForPageTapThenSavesInline() {
        assertInlineTextPlacementAndSave(openCompactEditor(), "compact")
    }

    @Test
    fun nativeWindowTextObjectKeepsItsAnchorAndSavesWithTheKeyboard() {
        val title = createAndOpenNotebook()
        val toolbar = if (hasTag("compact-insert")) "compact" else "toolbar"
        assertInlineTextPlacementAndSave(title, toolbar, requireVisibleIme = true)
    }

    private fun assertInlineTextPlacementAndSave(title: String, toolbar: String, requireVisibleIme: Boolean = false) {
        rule.onNodeWithTag("$toolbar-insert").performClick()
        rule.onNodeWithTag("$toolbar-insert-text").performClick()

        rule.onNodeWithTag("inline-text-editor").assertDoesNotExist()
        rule.onNodeWithText("Add text").assertDoesNotExist()
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("inline-text-placement").assertIsDisplayed() }.isSuccess
        }
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        val draft = "Inline ${System.nanoTime()}"
        val editor = rule.onNodeWithTag("inline-text-editor").assertIsDisplayed()
        if (requireVisibleIme) {
            try {
                rule.waitUntil(5_000) {
                    rule.runOnIdle {
                        ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                }
            } catch (failure: ComposeTimeoutException) {
                throw AssertionError("Inline editor did not open the native keyboard. ${nativeWindowDiagnostics()}", failure)
            }
            editor.assertIsDisplayed().assertIsEnabled()
        }
        editor.performTextInput(draft)
        val (editorBounds, editorPageBounds) = settledPageGeometry(hasTestTag("inline-text-editor"))
        val editorX = (editorBounds.left - editorPageBounds.left) / editorPageBounds.width
        val editorY = (editorBounds.top - editorPageBounds.top) / editorPageBounds.height
        rule.onNodeWithTag("inline-text-editor").performImeAction()
        rule.waitUntil(5_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "inline-text-editor"))
                .fetchSemanticsNodes().isEmpty() &&
                runCatching { rule.onNodeWithText(draft).fetchSemanticsNode() }.isSuccess
        }

        rule.onNodeWithTag("inline-text-editor").assertDoesNotExist()
        val (savedBounds, savedPageBounds) = settledPageGeometry(hasText(draft))
        val savedX = (savedBounds.left - savedPageBounds.left) / savedPageBounds.width
        val savedY = (savedBounds.top - savedPageBounds.top) / savedPageBounds.height
        assertTrue("Text x changed: $editorX -> $savedX; editor=$editorBounds/$editorPageBounds saved=$savedBounds/$savedPageBounds",
            kotlin.math.abs(savedX - editorX) <= 0.03f)
        assertTrue("Text y changed: $editorY -> $savedY; editor=$editorBounds/$editorPageBounds saved=$savedBounds/$savedPageBounds",
            kotlin.math.abs(savedY - editorY) <= 0.03f)
        rule.onNodeWithTag("element-selection").assertIsDisplayed()
        assertStoredInlineTexts(title, listOf(draft))
    }

    @Test
    fun selectedTextCanBeEditedInline() {
        openCompactEditor()
        val original = "Editable ${System.nanoTime()}"
        val updated = "$original updated"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(original)
        rule.onNodeWithTag("inline-text-editor").performImeAction()
        rule.waitUntil(5_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "inline-text-editor"))
                .fetchSemanticsNodes().isEmpty() &&
                runCatching { rule.onNodeWithTag("element-context-bar").fetchSemanticsNode() }.isSuccess
        }

        rule.onNodeWithText("Edit text", useUnmergedTree = true).performClick()
        val editor = rule.onNodeWithTag("inline-text-editor").assertTextContains(original)
        rule.onNodeWithTag("element-context-bar").assertDoesNotExist()
        rule.onNodeWithText("Delete").assertDoesNotExist()
        editor.performTextReplacement(updated)
        editor.performImeAction()
        rule.waitUntil(5_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "inline-text-editor"))
                .fetchSemanticsNodes().isEmpty() &&
                runCatching { rule.onNodeWithText(updated).fetchSemanticsNode() }.isSuccess
        }

        rule.onNodeWithText(updated).assertIsDisplayed()
        rule.onNodeWithText(original).assertDoesNotExist()
    }

    @Test
    fun clearingExistingInlineTextDeletesItAndUndoRestoresIt() {
        val title = openCompactEditor()
        val original = "Clear and undo ${System.nanoTime()}"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(original)
        rule.onNodeWithTag("inline-text-editor").performImeAction()
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("element-context-bar").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Edit text").performClick()
        rule.onNodeWithTag("inline-text-editor").performTextReplacement("")
        val gateAcquired = CountDownLatch(1)
        val releaseGate = CompletableDeferred<Unit>()
        val gateOwner = CoroutineScope(Dispatchers.IO).launch {
            LibraryMutationGate.withLock {
                gateAcquired.countDown()
                releaseGate.await()
            }
        }
        try {
            assertTrue(gateAcquired.await(5, TimeUnit.SECONDS))
            rule.onNodeWithTag("inline-text-editor").performImeAction()
            rule.onNodeWithTag("inline-text-editor").assertIsNotEnabled()
            rule.onNodeWithTag("element-context-bar").assertDoesNotExist()
            rule.onNodeWithTag("ink-context-bar").assertDoesNotExist()
            rule.onNodeWithText("Delete").assertDoesNotExist()
            releaseGate.complete(Unit)
            rule.waitUntil(5_000) {
                runCatching { rule.onNodeWithTag("compact-undo").assertIsEnabled() }.isSuccess
            }
            rule.onNodeWithTag("inline-text-editor").assertDoesNotExist()
            rule.onNodeWithText(original).assertDoesNotExist()
            assertStoredInlineTexts(title, emptyList())
            rule.onNodeWithTag("compact-undo").performClick()
            rule.waitUntil(5_000) {
                runCatching { rule.onNodeWithText(original).assertIsDisplayed() }.isSuccess
            }
            assertStoredInlineTexts(title, listOf(original))
        } finally {
            releaseGate.complete(Unit)
            runBlocking { gateOwner.join() }
        }
    }

    @Test
    fun recreationKeepsOrSavesInlineText() {
        createAndOpenNotebook()
        val draft = "Recreated inline ${System.nanoTime()}"
        val toolbar = if (hasTag("compact-insert")) "compact" else "toolbar"
        rule.onNodeWithTag("$toolbar-insert").performClick()
        rule.onNodeWithTag("$toolbar-insert-text").performClick()
        rule.waitUntil(5_000) { hasTag("inline-text-placement") }
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(draft)

        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithTag("editor-top-bar").fetchSemanticsNode() }.isSuccess
        }
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithText(draft).assertIsDisplayed() }.isSuccess
        }
    }

    @Test
    fun toolSwitchSavesInlineTextBeforeChangingTool() {
        openCompactEditor()
        val draft = "Saved before Pencil ${System.nanoTime()}"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(draft)

        rule.onNodeWithTag("compact-tool-pencil").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "inline-text-editor"))
                .fetchSemanticsNodes().isEmpty()
        }

        rule.onNodeWithTag("inline-text-editor").assertDoesNotExist()
        rule.onNodeWithTag("compact-tool-pencil").assertIsSelected()
        rule.onNodeWithText(draft).assertIsDisplayed()
    }

    @Test
    fun pageHistoryActionsAreDisabledWhileEditingPageOrInlineText() {
        openCompactEditor()
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput("History ${System.nanoTime()}")
        rule.onNodeWithTag("inline-text-editor").performImeAction()
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("compact-undo").assertIsEnabled() }.isSuccess
        }
        rule.onNodeWithTag("compact-undo").performClick()
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("compact-redo").assertIsEnabled() }.isSuccess
        }
        rule.onNodeWithTag("compact-redo").assertIsEnabled()
        selectTool("type")
        rule.onNodeWithTag("compact-undo").assertIsNotEnabled()
        rule.onNodeWithTag("compact-redo").assertIsNotEnabled()

        selectTool("pencil")
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("compact-redo").assertIsEnabled() }.isSuccess
        }
        rule.onNodeWithTag("compact-redo").assertIsEnabled()
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("compact-undo").assertIsNotEnabled()
        rule.onNodeWithTag("compact-redo").assertIsNotEnabled()
    }

    @Test
    fun emptyInlineTextDraftCreatesNoElement() {
        openCompactEditor()

        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").assertIsDisplayed().performImeAction()
        rule.waitUntil(5_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "inline-text-editor"))
                .fetchSemanticsNodes().isEmpty()
        }

        rule.onNodeWithTag("inline-text-editor").assertDoesNotExist()
        rule.onNodeWithTag("element-selection").assertDoesNotExist()
    }

    @Test
    fun backSavesInlineTextBeforeLeavingTheEditor() {
        val title = openCompactEditor()
        val draft = "Saved on close ${System.nanoTime()}"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(draft)

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithContentDescription("Open $title").performClick()

        assertStoredInlineTexts(title, listOf(draft))
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithText(draft).assertIsDisplayed() }.isSuccess
        }
        rule.onNodeWithText(draft).assertIsDisplayed()
    }

    @Test
    fun doneThenBackWaitsForTheInlineWrite() {
        val title = openCompactEditor()
        val draft = "Queued inline ${System.nanoTime()}"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(draft)
        val gateAcquired = CountDownLatch(1)
        val releaseGate = CompletableDeferred<Unit>()
        val gateOwner =
            CoroutineScope(Dispatchers.IO).launch {
                LibraryMutationGate.withLock {
                    gateAcquired.countDown()
                    releaseGate.await()
                }
            }
        try {
            assertTrue(gateAcquired.await(5, TimeUnit.SECONDS))
            rule.onNodeWithTag("inline-text-editor").performImeAction()
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            releaseGate.complete(Unit)
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode() }.isSuccess
            }
            rule.onNodeWithContentDescription("Open $title").performClick()

            assertStoredInlineTexts(title, listOf(draft))
            rule.waitUntil(5_000) {
                runCatching { rule.onNodeWithText(draft).assertIsDisplayed() }.isSuccess
            }
            rule.onNodeWithText(draft).assertIsDisplayed()
        } finally {
            releaseGate.complete(Unit)
            runBlocking { gateOwner.join() }
        }
    }

    @Test
    fun recreationDuringToolSaveKeepsInputDisabledAndBackSavesOnce() {
        val title = createAndOpenNotebook()
        val draft = "Retained save ${System.nanoTime()}"
        val toolbar = if (hasTag("compact-insert")) "compact" else "toolbar"
        rule.onNodeWithTag("$toolbar-insert").performClick()
        rule.onNodeWithTag("$toolbar-insert-text").performClick()
        rule.waitUntil(5_000) { hasTag("inline-text-placement") }
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(draft)
        val gateAcquired = CountDownLatch(1)
        val releaseGate = CompletableDeferred<Unit>()
        val gateOwner = CoroutineScope(Dispatchers.IO).launch {
            LibraryMutationGate.withLock {
                gateAcquired.countDown()
                releaseGate.await()
            }
        }
        try {
            assertTrue(gateAcquired.await(5, TimeUnit.SECONDS))
            rule.onNodeWithTag("$toolbar-tool-pencil").performClick()
            rule.onNodeWithTag("inline-text-editor").assertIsNotEnabled().assertTextContains(draft)
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithTag("inline-text-editor").fetchSemanticsNode() }.isSuccess
            }
            rule.onNodeWithTag("inline-text-editor").assertIsNotEnabled().assertTextContains(draft)
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            releaseGate.complete(Unit)
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode() }.isSuccess
            }
            assertStoredInlineTexts(title, listOf(draft))
            rule.onNodeWithContentDescription("Open $title").performClick()
            rule.waitUntil(5_000) {
                runCatching { rule.onNodeWithText(draft).assertIsDisplayed() }.isSuccess
            }
            rule.onNodeWithText(draft).assertIsDisplayed()
            assertEquals(1, rule.onAllNodes(hasText(draft)).fetchSemanticsNodes().size)
        } finally {
            releaseGate.complete(Unit)
            runBlocking { gateOwner.join() }
        }
    }

    @Test
    fun addTextDuringSaveKeepsOriginalAndStartsAnEmptyPlacement() {
        val title = openCompactEditor()
        val original = "First text ${System.nanoTime()}"
        val next = "Second text ${System.nanoTime()}"
        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-text").performClick()
        rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
        rule.onNodeWithTag("inline-text-editor").performTextInput(original)
        val gateAcquired = CountDownLatch(1)
        val releaseGate = CompletableDeferred<Unit>()
        val gateOwner = CoroutineScope(Dispatchers.IO).launch {
            LibraryMutationGate.withLock {
                gateAcquired.countDown()
                releaseGate.await()
            }
        }
        try {
            assertTrue(gateAcquired.await(5, TimeUnit.SECONDS))
            rule.onNodeWithTag("inline-text-editor").performImeAction()
            rule.onNodeWithTag("compact-insert").performClick()
            rule.onNodeWithTag("compact-insert-text").performClick()
            releaseGate.complete(Unit)
            rule.waitUntil(10_000) {
                runCatching { rule.onNodeWithTag("inline-text-placement").fetchSemanticsNode() }.isSuccess
            }
            assertStoredInlineTexts(title, listOf(original))
            rule.onNodeWithTag("inline-text-editor").assertDoesNotExist()
            rule.onNodeWithTag("page-paper").performTouchInput { click(center) }
            rule.onNodeWithTag("inline-text-editor").performTextInput(next)
            rule.onNodeWithTag("inline-text-editor").performImeAction()
            rule.waitUntil(5_000) {
                rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "inline-text-editor"))
                    .fetchSemanticsNodes().isEmpty()
            }
            assertStoredInlineTexts(title, listOf(original, next))
        } finally {
            releaseGate.complete(Unit)
            runBlocking { gateOwner.join() }
        }
    }

    @Test
    fun expandedTextObjectAlsoStartsInlineOnThePage() {
        openEditor(widthDp = 1280)

        rule.onNodeWithTag("toolbar-insert").performClick()
        var menuTap = Offset.Zero
        rule.onNodeWithTag("toolbar-insert-text").performTouchInput {
            menuTap = center
            click(center)
        }
        try {
            rule.waitUntil(5_000) {
                runCatching { rule.onNodeWithTag("inline-text-placement").assertIsDisplayed() }.isSuccess
            }
        } catch (failure: ComposeTimeoutException) {
            val state = rule.runOnIdle {
                val holder = if ("editor-session-holder" in rule.activity.viewModelStore.keys()) {
                    ViewModelProvider(rule.activity)["editor-session-holder", EditorSessionHolder::class.java]
                } else null
                val editor = holder?.takeIf { "editor" in it.viewModelStore.keys() }?.let {
                    ViewModelProvider(it)["editor", EditorViewModel::class.java]
                }
                "action=${holder?.actionState?.value}; close=${holder?.closeState?.value}; " +
                    "draftPage=${holder?.inlineTextDraft?.value?.pageId}; " +
                    "selectedPage=${editor?.state?.value?.selectedPage?.id}; failed=${editor?.state?.value?.failed}"
            }
            val placement = runCatching { rule.onNodeWithTag("inline-text-placement").printToString() }
            val menu = runCatching { rule.onNodeWithTag("toolbar-insert-text").printToString() }
            throw AssertionError("Text placement missing: $state\nMenu tap: $menuTap\nMenu: $menu\nPlacement: $placement\n${nativeWindowDiagnostics()}", failure)
        }
        rule.onNodeWithTag("inline-text-placement").performTouchInput { click(center) }
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("inline-text-editor").assertIsDisplayed() }.isSuccess
        }

        rule.onNodeWithTag("inline-text-editor").assertIsDisplayed()
    }

    @Test
    fun selectedInkExposesTextConversion() {
        rule.activity.setContent {
            SeliaDocsTheme {
                CompactEditorPalette(
                    state = EditorUiState(selectedStrokeIds = setOf("stroke")),
                    onSelectTool = {},
                    onEraserMode = {},
                    onAddText = {},
                    onAddImage = {},
                    onImportPdf = {},
                    onCleanShape = {},
                )
            }
        }

        rule.onNodeWithTag("compact-insert").performClick()
        rule.onNodeWithTag("compact-insert-convert").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun dismissingSearchBeforeDebounceDoesNotRestoreQuery() {
        openCompactEditor()
        rule.onNodeWithTag("compact-more").performClick()
        rule.onNodeWithTag("compact-more-search").performClick()
        rule.onNodeWithTag("search-query").assertIsDisplayed()

        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag("search-query").performTextInput("stale-query")
            rule.onNodeWithText("Close").performClick()
            rule.mainClock.advanceTimeBy(300)
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()

        rule.onNodeWithTag("compact-more").performClick()
        rule.onNodeWithTag("compact-more-search").performClick()
        rule.onNodeWithTag("search-query")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun searchFlushesPendingFullPageText() {
        val text = "PendingSearch${System.nanoTime()}"
        openCompactEditor()
        rule.onNodeWithTag("compact-tool-type").performClick()
        rule.onNodeWithTag("page-text").assertIsDisplayed().performTextInput(text)

        rule.onNodeWithTag("compact-more").performClick()
        rule.onNodeWithTag("compact-more-search").performClick()
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("search-query").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithTag("search-query").performTextInput(text)
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag("search-result-0").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithTag("search-result-0").assertIsDisplayed()
    }

    @Test
    fun typeFieldKeepsPageNavigationKeys() {
        openCompactEditor()
        addPageFromEditor()
        rule.waitUntil(5_000) {
            runCatching {
                rule.onNodeWithTag("compact-page-location").assertTextContains("Page 2 of 2")
            }.isSuccess
        }
        rule.onNodeWithTag("compact-page-location").assertTextContains("Page 2 of 2")
        selectTool("type")

        rule.onNodeWithTag("page-text").performKeyInput { pressKey(Key.PageUp) }

        rule.onNodeWithTag("compact-page-location").assertTextContains("Page 2 of 2")
    }

    @Test
    fun compactActionsKeepFortyEightDpTouchTargets() {
        openCompactEditor()
        val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val minimumHeight = 48f * rootBounds.width / 360f
        val tags =
            listOf("back", "undo", "redo", "more", "page-location").map { "compact-$it" } +
                listOf("type", "pen", "pencil", "highlighter", "eraser", "lasso")
                    .map { "compact-tool-$it" } +
                "compact-insert"

        tags.forEach { tag ->
            val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag width ${bounds.width} must be at least $minimumHeight px", bounds.width >= minimumHeight)
            assertTrue("$tag height ${bounds.height} must be at least $minimumHeight px", bounds.height >= minimumHeight)
            assertTrue("$tag starts outside root: $bounds vs $rootBounds", bounds.left >= rootBounds.left)
            assertTrue("$tag ends outside root: $bounds vs $rootBounds", bounds.right <= rootBounds.right)
            assertTrue("$tag top exceeds root: $bounds vs $rootBounds", bounds.top >= rootBounds.top)
            assertTrue("$tag bottom exceeds root: $bounds vs $rootBounds", bounds.bottom <= rootBounds.bottom)
        }
    }
    @Test
    fun compactTopBarAtTwoHundredPercentKeepsActionsReachable() {
        val title = openEditor(widthDp = 360, fontScale = 2f)
        val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val minimum = 48f * rootBounds.width / 360f
        listOf("compact-back", "compact-page-location", "compact-more").forEach { tag ->
            val node = rule.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$tag is narrower than 48 dp: $bounds", bounds.width >= minimum)
            assertTrue("$tag is shorter than 48 dp: $bounds", bounds.height >= minimum)
            assertTrue("$tag starts outside root: $bounds", bounds.left >= rootBounds.left)
            assertTrue("$tag ends outside root: $bounds", bounds.right <= rootBounds.right)
            assertTrue("$tag starts above root: $bounds", bounds.top >= rootBounds.top)
            assertTrue("$tag ends below root: $bounds", bounds.bottom <= rootBounds.bottom)
        }
        val titleLayouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("editor-top-bar-title", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertEquals(1, titleLayouts.single().lineCount)
        assertFalse(titleLayouts.single().isLineEllipsized(0))
        rule.onNodeWithTag("editor-top-bar-title", useUnmergedTree = true).assertTextContains("Page 1 of 1")
        assertEquals(
            listOf("$title, Page 1 of 1"),
            rule.onNodeWithTag("compact-page-location")
                .fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        rule.onNodeWithTag("compact-undo").assertDoesNotExist()
        rule.onNodeWithTag("compact-redo").assertDoesNotExist()
        rule.onNodeWithTag("compact-more").performClick()
        rule.onNodeWithTag("compact-more-undo").assertIsDisplayed()
        rule.onNodeWithTag("compact-more-redo").assertIsDisplayed()
    }
    @Test
    fun activityRetainsOneSessionHolderAndClearsChildBetweenEditors() {
        rule.waitForIdle()
        val initialKeys = rule.runOnIdle { rule.activity.viewModelStore.keys().toSet() }
        var title = openCompactEditor()
        val expectedKeys = initialKeys + setOf(
            "editor-session-holder", "secondary-editor-session-holder", "editor-workspace",
        )
        val holders = rule.runOnIdle {
            assertEquals(3, expectedKeys.size - initialKeys.size)
            assertEquals(expectedKeys, rule.activity.viewModelStore.keys())
            val provider = ViewModelProvider(rule.activity)
            Triple(
                provider["editor-session-holder", EditorSessionHolder::class.java],
                provider["secondary-editor-session-holder", EditorSessionHolder::class.java],
                provider["editor-workspace", EditorWorkspaceHolder::class.java],
            )
        }
        var previousEditor = rule.runOnIdle {
            assertEquals(setOf("editor"), holders.first.viewModelStore.keys())
            ViewModelProvider(holders.first)["editor", EditorViewModel::class.java]
        }
        repeat(2) {
            rule.onNodeWithTag("compact-back").performClick()
            rule.waitUntil(5_000) {
                runCatching {
                    rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode()
                }.isSuccess
            }
            rule.runOnIdle {
                assertEquals(expectedKeys, rule.activity.viewModelStore.keys())
                assertTrue("Primary editor must be cleared after Back", holders.first.viewModelStore.keys().isEmpty())
                assertTrue("Secondary editor must be cleared after Back", holders.second.viewModelStore.keys().isEmpty())
            }
            title = createAndOpenNotebook()
            rule.onNodeWithTag("editor-top-bar-title", useUnmergedTree = true).assertTextContains(title)
            previousEditor = rule.runOnIdle {
                assertEquals(expectedKeys, rule.activity.viewModelStore.keys())
                val provider = ViewModelProvider(rule.activity)
                assertTrue(holders.first === provider["editor-session-holder", EditorSessionHolder::class.java])
                assertTrue(holders.second === provider["secondary-editor-session-holder", EditorSessionHolder::class.java])
                assertTrue(holders.third === provider["editor-workspace", EditorWorkspaceHolder::class.java])
                assertEquals(setOf("editor"), holders.first.viewModelStore.keys())
                val editor = ViewModelProvider(holders.first)["editor", EditorViewModel::class.java]
                assertTrue("Each notebook needs a new child editor", editor !== previousEditor)
                editor
            }
        }
    }
    @Test
    fun recreationRetainsDraftAndSystemBackWaitsForFlush() {
        val title = createAndOpenNotebook()
        val draft = "Draft ${System.nanoTime()}"
        selectTool("type")
        rule.waitUntil(5_000) {
            runCatching {
                rule.onNodeWithTag("page-text").assertIsDisplayed().fetchSemanticsNode()
            }.isSuccess
        }
        val gateAcquired = CountDownLatch(1)
        val releaseGate = CompletableDeferred<Unit>()
        val gateOwner =
            CoroutineScope(Dispatchers.IO).launch {
                LibraryMutationGate.withLock {
                    gateAcquired.countDown()
                    releaseGate.await()
                }
            }
        try {
            assertTrue(gateAcquired.await(5, TimeUnit.SECONDS))
            rule.mainClock.autoAdvance = false
            rule.onNodeWithTag("page-text").performTextInput(draft)
            rule.mainClock.advanceTimeByFrame()
            addPageFromEditor()
            rule.mainClock.advanceTimeByFrame()
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("editor-top-bar").assertIsDisplayed()
            rule.onNodeWithTag("page-text").assertIsNotEnabled()
            addPageFromEditor()
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.autoAdvance = true
            rule.activityRule.scenario.recreate()

            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithTag("editor-top-bar").fetchSemanticsNode() }.isSuccess ||
                    runCatching {
                        rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode()
                    }.isSuccess
            }
            assertFalse(
                "Recreation restored the library route instead of the active editor",
                runCatching {
                    rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode()
                }.isSuccess,
            )
            rule.onNodeWithTag("editor-top-bar").assertIsDisplayed()
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithTag("page-text").assertIsDisplayed() }.isSuccess
            }
            rule.onNodeWithTag("page-text").assertTextContains(draft)
            rule.onNodeWithTag("page-text").assertIsNotEnabled()

            releaseGate.complete(Unit)
            rule.mainClock.autoAdvance = true
            rule.waitUntil(15_000) {
                runCatching {
                    rule.onNodeWithContentDescription("Open $title").fetchSemanticsNode()
                }.isSuccess
            }
            rule.onNodeWithContentDescription("Open $title").performClick()
            selectTool("type")
            rule.waitUntil(15_000) {
                runCatching { rule.onNodeWithTag("page-text").fetchSemanticsNode() }.isSuccess
            }
            rule.onNodeWithTag("page-text").assertTextContains(draft)
        } finally {
            rule.mainClock.autoAdvance = true
            releaseGate.complete(Unit)
            runBlocking { gateOwner.join() }
        }
    }

    private fun selectTool(tool: String) {
        val tag = if (hasTag("compact-tool-$tool")) "compact-tool-$tool" else "toolbar-tool-$tool"
        rule.onNodeWithTag(tag).performClick()
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag(tag).assertIsSelected() }.isSuccess
        }
        rule.onNodeWithTag(tag).assertIsSelected()
    }

    private fun addPageFromEditor() {
        if (hasTag("compact-more")) {
            rule.onNodeWithTag("compact-more").performClick()
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("compact-more-add-page").performClick()
        } else {
            rule.onNodeWithContentDescription("Add page").performClick()
        }
        rule.mainClock.advanceTimeByFrame()
        if (hasTag("insert-note-page")) rule.onNodeWithTag("insert-note-page").performClick()
    }

    private fun hasTag(tag: String): Boolean =
        runCatching { rule.onNodeWithTag(tag).fetchSemanticsNode() }.isSuccess

    @Test
    fun compactPaletteAppliesInjectedNavigationInsets() {
        rule.activity.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 744.dp)),
            ) {
                SeliaDocsTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CompactEditorPalette(
                            state = EditorUiState(),
                            onSelectTool = {},
                            onEraserMode = {},
                            onAddText = {},
                            onAddImage = {},
                            onImportPdf = {},
                            onCleanShape = {},
                            contentInsets = WindowInsets(16.dp, 0.dp, 20.dp, 24.dp),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val density = rootBounds.width / 360f
        val safeLeft = (16f * density).toInt()
        val safeRight = (20f * density).toInt()
        val safeBottom = (24f * density).toInt()

        listOf("type", "pen", "pencil", "highlighter", "eraser", "lasso")
            .map { "compact-tool-$it" }
            .plus("compact-insert")
            .forEach { tag ->
                val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue("$tag width ${bounds.width} must stay at least 48 dp", bounds.width >= 48f * density)
                assertTrue("$tag height ${bounds.height} must stay at least 48 dp", bounds.height >= 48f * density)
                assertTrue("$tag overlaps left inset: $bounds", bounds.left >= rootBounds.left + safeLeft)
                assertTrue("$tag overlaps right inset: $bounds", bounds.right <= rootBounds.right - safeRight)
                assertTrue("$tag overlaps bottom inset: $bounds", bounds.bottom <= rootBounds.bottom - safeBottom)
            }
    }

    @Test
    fun compactToolsExposeMaterialIconDescriptions() {
        openCompactEditor()

        listOf("Back", "Undo", "Redo", "More options", "Type", "Pen", "Pencil", "Highlighter", "Eraser", "Lasso", "Insert")
            .forEach { description ->
                rule.onNodeWithContentDescription(description).assertIsDisplayed()
            }
    }

    @Test
    fun compactPenExposesWidthsAndColorsWithoutLeavingEditor() {
        openCompactEditor()

        rule.onNodeWithTag("brush-width-slider").assertDoesNotExist()
        rule.onNodeWithTag("compact-tool-pen").performClick()

        val penRange =
            rule.onNodeWithTag("brush-width-slider")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1f, penRange.range.start)
        assertEquals(32f, penRange.range.endInclusive)
        rule.onNodeWithTag("brush-width-slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
        rule.waitForIdle()
        assertEquals(
            32f,
            rule.onNodeWithTag("brush-width-slider")
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
                .current,
        )

        listOf(
            "brush-color-black",
            "brush-color-blue",
            "brush-color-red",
        )
            .forEach { tag -> rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHasClickAction() }
        rule.onNodeWithTag("brush-shape-assist").assertExists().assertHasClickAction()

        rule.onNodeWithTag("compact-tool-highlighter").performClick()
        rule.onNodeWithTag("brush-width-slider").assertDoesNotExist()
        rule.onNodeWithTag("compact-tool-highlighter").performClick()
        val highlighterRange =
            rule.onNodeWithTag("brush-width-slider")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(4f, highlighterRange.range.start)
        assertEquals(64f, highlighterRange.range.endInclusive)
    }

    @Test
    fun compactPencilUsesItsOwnVisibleSelectedTool() {
        openCompactEditor()

        rule.onNodeWithTag("compact-tool-pencil").performClick()

        rule.onNodeWithTag("compact-tool-pencil").assertIsSelected()
        rule.onNodeWithTag("compact-tool-pen").assertIsNotSelected()
        val tools = listOf("type", "pen", "pencil", "highlighter", "eraser", "lasso")
        assertEquals(
            1,
            tools.count {
                rule.onNodeWithTag("compact-tool-$it")
                    .fetchSemanticsNode().config[SemanticsProperties.Selected]
            },
        )
    }

    @Test
    fun compactEraserMenuSelectsBothModesAndDismisses() {
        openCompactEditor()

        rule.onNodeWithTag("compact-tool-eraser").performClick()
        rule.onNodeWithTag("compact-eraser-segment")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        rule.onNodeWithTag("compact-eraser-stroke")
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))
            .performClick()
        rule.onNodeWithTag("compact-eraser-stroke").assertDoesNotExist()
        rule.onNodeWithTag("compact-tool-eraser")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Whole stroke"))

        rule.onNodeWithTag("compact-tool-eraser").performClick()
        rule.onNodeWithTag("compact-eraser-stroke").assertIsSelected()
        rule.onNodeWithTag("compact-eraser-segment").performClick()
        rule.onNodeWithTag("compact-eraser-segment").assertDoesNotExist()
        rule.onNodeWithTag("compact-tool-eraser")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Segment"))
    }

    @Test
    fun mediumEditorKeepsExistingToolbarAndPageLocation() {
        openEditor(widthDp = 600)
        rule.onNodeWithTag("compact-page-location").assertDoesNotExist()
        rule.onNodeWithTag("compact-insert").assertDoesNotExist()
        rule.onNodeWithText("Contents").assertIsDisplayed()
        rule.onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    @Test
    fun expandedEditorKeepsExistingToolbarAndContentsPane() {
        openEditor(widthDp = 1280)
        rule.onNodeWithTag("compact-page-location").assertDoesNotExist()
        rule.onNodeWithTag("compact-insert").assertDoesNotExist()
        rule.onNodeWithText("Add chapter").assertExists()
        rule.onNodeWithContentDescription("Search").assertExists()
    }

    private fun openCompactEditor(): String {
        return openEditor(widthDp = 360)
    }

    private fun settledPageGeometry(content: SemanticsMatcher): Pair<Rect, Rect> {
        var previous: Pair<Rect, Rect>? = null
        var stableSince = 0L
        try {
            rule.waitUntil(5_000) {
                val contentNode = rule.onNode(content).fetchSemanticsNode()
                val pageNode = rule.onNodeWithTag("page-paper").fetchSemanticsNode()
                // Read both coordinates on the UI thread so an IME layout cannot split the sample.
                val sample = rule.runOnIdle { contentNode.boundsInRoot to pageNode.boundsInRoot }
                val now = android.os.SystemClock.uptimeMillis()
                if (sample != previous) { previous = sample; stableSince = now }
                sample.first.width > 0f && sample.first.height > 0f &&
                    sample.second.width > 0f && sample.second.height > 0f && now - stableSince >= 250L
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Page geometry did not settle: $previous\n${nativeWindowDiagnostics()}", failure)
        }
        return requireNotNull(previous)
    }

    private fun nativeWindowDiagnostics(): String = rule.runOnIdle {
        val decor = rule.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(decor)
        val visible = android.graphics.Rect().also(decor::getWindowVisibleDisplayFrame)
        "Native window=${decor.width}x${decor.height}, density=${decor.resources.displayMetrics.density}, " +
            "visible=$visible, imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())}, " +
            "ime=${insets?.getInsets(WindowInsetsCompat.Type.ime())}, " +
            "systemBars=${insets?.getInsets(WindowInsetsCompat.Type.systemBars())}"
    }

    private fun assertStoredInlineTexts(title: String, expected: List<String>) = runBlocking {
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(rule.activity.application))
        val notebook = repository.getAllNotebooks().single { it.title == title }
        val pages = repository.getPages(notebook.id)
        val elements = pages.flatMap { repository.getElements(it.id) }.filter { it.kind == ElementKind.TEXT.name }
        assertEquals("Stored text elements: $elements", expected.sorted(), elements.map { it.text }.sortedBy { it })
        elements.forEach { element ->
            val page = pages.single { it.id == element.pageId }
            assertTrue(
                "Text must fit page ${page.widthPoints}x${page.heightPoints}: $element",
                element.x >= 0f && element.y >= 0f && element.width > 0f && element.height > 0f &&
                    element.x + element.width <= page.widthPoints && element.y + element.height <= page.heightPoints,
            )
        }
    }

    private fun openEditor(widthDp: Int, fontScale: Float = 1f): String {
        rule.activity.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(widthDp.dp, 744.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    SeliaDocsApp()
                }
            }
        }
        return createAndOpenNotebook(dismissCreationKeyboard = true)
    }

    private fun createAndOpenNotebook(dismissCreationKeyboard: Boolean = false): String {
        val title = "Compact editor ${System.nanoTime()}"
        rule.onNodeWithContentDescription("New notebook").performClick()
        rule.onNodeWithContentDescription("Notebook name").assertIsDisplayed().performTextReplacement(title)
        rule.onNodeWithText("Create notebook").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
        }
        if (dismissCreationKeyboard) {
            closeSoftKeyboard()
            try {
                rule.waitUntil(5_000) {
                    rule.runOnIdle {
                        ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == false
                    }
                }
            } catch (failure: ComposeTimeoutException) {
                throw AssertionError("Notebook dialog keyboard remained visible before opening the editor. ${nativeWindowDiagnostics()}", failure)
            }
        }
        rule.onNodeWithContentDescription("Open $title").performClick()
        rule.onNodeWithTag("editor-top-bar").assertIsDisplayed()
        return title
    }
}
