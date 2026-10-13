package com.majkeylab.seliadocs.editor

import android.graphics.Color
import android.graphics.Point
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.data.BlockEntity
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.PAGE_TEXT_MARGIN
import com.majkeylab.seliadocs.data.PAGE_TEXT_TOP
import com.majkeylab.seliadocs.data.StrokeEntity
import com.majkeylab.seliadocs.data.pageTextFits
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PageTextFlowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun staleStoredEchoDoesNotReplaceNewerDraft() {
        val saved = AtomicReference<Pair<String, String>>()
        var blocks by mutableStateOf(emptyList<BlockEntity>())
        compose.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                pageCount = 1,
                strokes = emptyList(),
                elements = emptyList(),
                blocks = blocks,
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.TYPE,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onPreviousPage = {},
                onNextPage = {},
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, _ -> },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { pageId, text -> saved.set(pageId to text) },
                onCommitElementTransform = {},
                assetFile = { File(it) },
            )
        }

        compose.onNodeWithTag("page-text").performTextInput("A")
        compose.waitUntil(3_000) { saved.get()?.second == "A" }
        compose.onNodeWithTag("page-text").performTextInput("B")
        compose.waitUntil(3_000) { saved.get()?.second == "AB" }
        compose.runOnUiThread {
            blocks = listOf(BlockEntity("block", "page", 0, "PARAGRAPH", "A", false, 0, "START", null))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertEquals("AB", compose.onNodeWithTag("page-text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)

        compose.runOnUiThread {
            blocks = listOf(BlockEntity("block", "page", 0, "PARAGRAPH", "AB", false, 0, "START", null))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertEquals("AB", compose.onNodeWithTag("page-text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("page-text").performTextInput("C")
        assertEquals("ABC", compose.onNodeWithTag("page-text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun cleanExternalTextUpdateClampsTheExistingCursor() {
        val saved = AtomicReference<Pair<String, String>>()
        val draft = AtomicReference<Pair<String, String>>()
        var blocks by mutableStateOf(emptyList<BlockEntity>())
        compose.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                pageCount = 1,
                strokes = emptyList(),
                elements = emptyList(),
                blocks = blocks,
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.TYPE,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onPreviousPage = {},
                onNextPage = {},
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, _ -> },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { pageId, text -> saved.set(pageId to text) },
                onCommitElementTransform = {},
                assetFile = { File(it) },
                onPageTextDraftChanged = { pageId, value ->
                    draft.set(pageId to value.text)
                    true
                },
            )
        }

        compose.onNodeWithTag("page-text").performTextInput("ABCDE")
        compose.waitUntil(3_000) { saved.get()?.second == "ABCDE" }
        compose.runOnUiThread {
            blocks = listOf(BlockEntity("block", "page", 0, "PARAGRAPH", "ABCDE", false, 0, "START", null))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnUiThread {
            blocks = listOf(BlockEntity("block", "page", 0, "PARAGRAPH", "A", false, 0, "START", null))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertEquals("page" to "A", draft.get())
        compose.onNodeWithTag("page-text").performTextInput("B")
        assertEquals("AB", compose.onNodeWithTag("page-text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun draftGateRejectsInputBeforeDisabledStateRecomposes() {
        var inputOpen = true
        compose.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                pageCount = 1,
                strokes = emptyList(),
                elements = emptyList(),
                blocks = emptyList(),
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.TYPE,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onPreviousPage = {},
                onNextPage = {},
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, _ -> },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { _, _ -> },
                onCommitElementTransform = {},
                assetFile = { File(it) },
                onPageTextDraftChanged = { _, _ -> inputOpen },
            )
        }

        compose.onNodeWithTag("page-text").performTextInput("A")
        compose.runOnUiThread { inputOpen = false }
        compose.onNodeWithTag("page-text").performTextInput("B")

        assertEquals("A", compose.onNodeWithTag("page-text").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun typeToolWritesDirectlyToPage() {
        val saved = AtomicReference<Pair<String, String>>()
        var blocks by mutableStateOf(emptyList<BlockEntity>())
        compose.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                pageCount = 1,
                strokes = emptyList(),
                elements = emptyList(),
                blocks = blocks,
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.TYPE,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onPreviousPage = {},
                onNextPage = {},
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, _ -> },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { pageId, text ->
                    saved.set(pageId to text)
                    blocks =
                        listOf(
                            BlockEntity("block", pageId, 0, "PARAGRAPH", text, false, 0, "START", null),
                        )
                },
                onCommitElementTransform = {},
                assetFile = { File(it) },
            )
        }

        compose.onNodeWithTag("page-text").performTextInput("Lecture notes")
        compose.waitUntil(3_000) { saved.get()?.second == "Lecture notes" }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertEquals("page" to "Lecture notes", saved.get())

        compose.runOnUiThread { blocks = emptyList() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil(3_000) {
            val node = compose.onNodeWithTag("page-text").fetchSemanticsNode()
            runCatching { node.config[SemanticsProperties.EditableText].text }
                .getOrNull()
                .isNullOrEmpty()
        }
    }

    @Test
    fun typedEquationCompletesInsideNormalPageText() {
        val saved = AtomicReference<Pair<String, String>>()
        compose.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                pageCount = 1,
                strokes = emptyList(),
                elements = emptyList(),
                blocks = emptyList(),
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.TYPE,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onPreviousPage = {},
                onNextPage = {},
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, _ -> },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { pageId, text -> saved.set(pageId to text) },
                onCommitElementTransform = {},
                assetFile = { File(it) },
            )
        }

        compose.onNodeWithTag("page-text").performTextInput("2+3=")
        compose.waitUntil(3_000) {
            compose.onNodeWithTag("page-text")
                .fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "2+3=5"
        }
        compose.waitUntil(3_000) { saved.get()?.second == "2+3=5" }
    }

    @Test
    fun imeCompositionCommitCompletesAnAlreadySavedEquation() {
        val expression = "2+3="
        val connectionRef = AtomicReference<InputConnection>()
        val drafts = CopyOnWriteArrayList<TextFieldValue>()
        val saves = CopyOnWriteArrayList<Pair<String, String>>()
        fun waitForSavedText(expected: String) {
            try {
                compose.waitUntil(3_000) { saves.lastOrNull()?.second == expected }
            } catch (failure: ComposeTimeoutException) {
                throw AssertionError("Expected saved '$expected'; drafts=$drafts; saves=$saves", failure)
            }
        }
        renderEditablePage(connection = connectionRef, onDraftChanged = { drafts += it }) { pageId, text ->
            saves += pageId to text
        }
        val field = compose.onNodeWithTag("page-text").assertIsFocused()
        compose.waitUntil(3_000) { connectionRef.get() != null }
        val connection = requireNotNull(connectionRef.get())
        compose.runOnIdle { assertTrue(connection.setComposingText(expression, 1)) }
        compose.waitForIdle()
        assertEquals("Drafts=$drafts; saves=$saves", TextRange(0, expression.length), drafts.lastOrNull()?.composition)
        compose.mainClock.advanceTimeBy(500)
        waitForSavedText(expression)
        assertEquals("Drafts=$drafts; saves=$saves", TextRange(0, expression.length), drafts.lastOrNull()?.composition)
        assertEquals(expression, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)

        compose.runOnIdle { assertTrue(connection.finishComposingText()) }

        waitForSavedText("2+3=5")
        assertEquals(
            "Drafts=$drafts; saves=$saves", "2+3=5",
            field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )
    }

    @Test
    fun oversizedAutomaticMathResultKeepsOriginalInputSaveable() {
        val page = PageEntity("page", "notebook", 0, PaperTemplate.BLANK.name, 200, 160)
        val input = "10^100="
        assertTrue(pageTextFits(input, page.widthPoints, page.heightPoints))
        assertFalse(pageTextFits(input + "1" + "0".repeat(100), page.widthPoints, page.heightPoints))
        val connection = AtomicReference<InputConnection>()
        val saved = AtomicReference<Pair<String, String>>()
        renderEditablePage(page, connection) { pageId, text -> saved.set(pageId to text) }
        val field = compose.onNodeWithTag("page-text").assertIsFocused()
        compose.waitUntil(3_000) { connection.get() != null }
        compose.runOnIdle { assertTrue(requireNotNull(connection.get()).commitText(input, 1)) }

        compose.waitUntil(3_000) { saved.get()?.second == input }

        assertEquals(input, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("page" to input, saved.get())
        compose.onNodeWithText("Page full · Add a page to continue").assertIsDisplayed()
    }

    @Test
    fun storedInkStaysAboveTextWhileTypeAcceptsNativeFingerAndKeyInput() {
        val text = "MMMMMMMM"
        val page = PageEntity("page", "notebook", 0, PaperTemplate.BLANK.name, 595, 842)
        val tool = mutableStateOf(EditorTool.PEN)
        val strokes = mutableStateOf(emptyList<StrokeEntity>())
        val connection = AtomicReference<InputConnection>()
        val saved = AtomicReference<Pair<String, String>>()
        renderEditablePage(page, connection, initialText = text, tool = tool, strokes = strokes) { pageId, value ->
            saved.set(pageId to value)
        }
        val field = compose.onNodeWithTag("page-text")
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val bounds = field.fetchSemanticsNode().boundsInRoot
        val root = requireNotNull(field.fetchSemanticsNode().root as? android.view.View)
        val location = IntArray(2)
        compose.runOnIdle { root.getLocationOnScreen(location) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(100, 2_000)
        val screenshot = requireNotNull(automation.takeScreenshot())
        val samples = mutableListOf<Point>()
        try {
            val left = (location[0] + bounds.left).roundToInt()
            val top = (location[1] + bounds.top).roundToInt()
            for (y in top until top + layout.getLineBottom(0).roundToInt()) {
                for (x in left until left + layout.getLineRight(0).roundToInt()) {
                    val pixel = screenshot.getPixel(x, y)
                    if (Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) {
                        samples += Point(x, y)
                    }
                }
            }
        } finally {
            screenshot.recycle()
        }
        assertTrue("Stored page text must appear in the native window", samples.size >= 12)
        fun bluePixels(): Int {
            val bitmap = requireNotNull(automation.takeScreenshot())
            return try {
                samples.count { point ->
                    val pixel = bitmap.getPixel(point.x, point.y)
                    Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) > 180
                }
            } finally {
                bitmap.recycle()
            }
        }
        val paper = compose.onNodeWithTag("page-paper").fetchSemanticsNode().boundsInRoot
        val scale = paper.height / page.heightPoints
        val glyph = layout.getBoundingBox(0)
        val ink = textOverlapStroke(
            PAGE_TEXT_MARGIN + layout.getLineRight(0) / scale + 30f,
            PAGE_TEXT_TOP + glyph.center.y / scale,
        )
        compose.runOnIdle { strokes.value = listOf(ink) }
        compose.waitUntil(5_000) { bluePixels() >= samples.size * 0.95f }
        val penPixels = bluePixels()

        compose.runOnIdle { tool.value = EditorTool.TYPE }
        compose.waitUntil(3_000) { connection.get() != null }
        field.assertIsFocused()
        automation.waitForIdle(100, 2_000)
        assertTrue("Type must keep stored ink above page text", bluePixels() >= penPixels * 0.95f)
        compose.runOnIdle { assertTrue(requireNotNull(connection.get()).setSelection(text.length, text.length)) }
        assertEquals(TextRange(text.length), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        val activeBounds = field.fetchSemanticsNode().boundsInRoot
        injectFingerTap(location[0] + activeBounds.left + 1f, location[1] + activeBounds.top + glyph.center.y)
        compose.waitForIdle()
        assertEquals("A native finger tap must move the caret through stored ink", TextRange(0),
            field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_X)
        compose.waitUntil(3_000) { saved.get()?.second == "x$text" }
        assertEquals("x$text", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)

        compose.runOnIdle { tool.value = EditorTool.PEN }
        compose.waitForIdle()
        automation.waitForIdle(100, 2_000)
        assertTrue("Returning to Pen must preserve ink above typed text", bluePixels() >= penPixels * 0.95f)
        assertEquals("page" to "x$text", saved.get())
    }

    private fun textOverlapStroke(endX: Float, y: Float): StrokeEntity {
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.STYLUS, PAGE_TEXT_MARGIN - 10f, y, 0L, 0.01f, 0.5f, 0f, 0f)
            add(InputToolType.STYLUS, endX, y, 16L, 0.01f, 0.5f, 0f, 0f)
        }
        val ink = InkCodec.encode(Stroke(InkCodec.createBrush(BrushKind.MARKER, Color.BLUE, 36f), inputs))
        return StrokeEntity("overlap", "page", 0, ink.brushKind.name, ink.colorArgb, ink.size, ink.epsilon, ink.inputs)
    }

    private fun injectFingerTap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 0.8f
            size = 0.8f
        }
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(
                time, time + index * 16L, action, 1, arrayOf(properties), arrayOf(coordinates),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            try {
                assertTrue("Native finger input was rejected",
                    InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }

    private fun renderEditablePage(
        page: PageEntity = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
        connection: AtomicReference<InputConnection>,
        onDraftChanged: (TextFieldValue) -> Unit = {},
        initialText: String = "",
        tool: State<EditorTool> = mutableStateOf(EditorTool.TYPE),
        strokes: State<List<StrokeEntity>> = mutableStateOf(emptyList()),
        onTextChanged: (String, String) -> Unit,
    ) {
        var blocks by mutableStateOf(
            if (initialText.isEmpty()) emptyList()
            else listOf(BlockEntity("block", page.id, 0, "PARAGRAPH", initialText, false, 0, "START", null)),
        )
        val interceptor = PlatformTextInputInterceptor { request, _ ->
            connection.set(request.createInputConnection(EditorInfo()))
            awaitCancellation()
        }
        compose.setContent {
            InterceptPlatformTextInput(interceptor) {
                PageCanvas(
                    page = page,
                    pageNumber = 1,
                    pageCount = 1,
                    strokes = strokes.value,
                    elements = emptyList(),
                    blocks = blocks,
                    selectedStrokeIds = emptySet(),
                    selectedElementId = null,
                    fingerDrawing = false,
                    tool = tool.value,
                    penWidth = 4f,
                    highlighterWidth = 16f,
                    pageTransitionEnabled = false,
                    onPreviousPage = {},
                    onNextPage = {},
                    onStrokeFinished = { _, _ -> },
                    onEraseFinished = { _, _ -> },
                    onSelectContent = { _, _ -> },
                    onMoveSelection = { _, _ -> },
                    onPageTextChanged = { pageId, text ->
                        blocks = listOf(BlockEntity("block", pageId, 0, "PARAGRAPH", text, false, 0, "START", null))
                        onTextChanged(pageId, text)
                    },
                    onPageTextDraftChanged = { _, value ->
                        onDraftChanged(value)
                        true
                    },
                    onCommitElementTransform = {},
                    assetFile = { File(it) },
                )
            }
        }
    }

    @Test
    fun typeToolKeepsOverflowOffThePage() {
        val saved = AtomicReference<Pair<String, String>>()
        compose.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                pageCount = 1,
                strokes = emptyList(),
                elements = emptyList(),
                blocks = emptyList(),
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.TYPE,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onPreviousPage = {},
                onNextPage = {},
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, _ -> },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { pageId, text -> saved.set(pageId to text) },
                onCommitElementTransform = {},
                assetFile = { File(it) },
            )
        }

        compose.onNodeWithTag("page-text").performTextInput(List(80) { "A full line" }.joinToString("\n"))

        compose.onNodeWithText("Page full · Add a page to continue").assertIsDisplayed()
        assertEquals(null, saved.get())
    }
}
