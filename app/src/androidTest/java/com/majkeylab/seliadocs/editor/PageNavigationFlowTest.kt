package com.majkeylab.seliadocs.editor

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.SeliaDocsApp
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class PageNavigationFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun oneFingerSwipeTurnsExactlyOnePageWhenFingerDrawingIsOff() {
        val previous = AtomicInteger()
        val next = AtomicInteger()
        renderPage(previous, next, fingerDrawing = false)

        rule.onNodeWithTag("page-viewport").performTouchInput {
            swipe(Offset(width * 0.85f, centerY), Offset(width * 0.15f, centerY), 300)
        }

        assertEquals(0, previous.get())
        assertEquals(1, next.get())
    }

    @Test
    fun twoFingerSwipeCancelsFingerInkThenTurnsOnePage() {
        val next = AtomicInteger()
        val committed = AtomicInteger()
        renderPage(AtomicInteger(), next, fingerDrawing = true, committed = committed)

        rule.onNodeWithTag("page-viewport").performTouchInput {
            val start0 = Offset(width * 0.85f, centerY - 30f)
            val start1 = Offset(width * 0.85f, centerY + 30f)
            down(0, start0)
            advanceEventTime(16)
            down(1, start1)
            updatePointerTo(0, Offset(width * 0.15f, centerY - 30f))
            updatePointerTo(1, Offset(width * 0.15f, centerY + 30f))
            move(300)
            up(0)
            up(1)
        }
        rule.waitForIdle()

        assertEquals(0, committed.get())
        assertEquals(1, next.get())
    }

    @Test
    fun twoFingerSwipeWithSpacingJitterTurnsOnePage() {
        val next = AtomicInteger()
        renderPage(AtomicInteger(), next, fingerDrawing = true)

        rule.onNodeWithTag("page-viewport").performTouchInput {
            down(0, Offset(width * 0.85f, centerY - 50f))
            advanceEventTime(16)
            down(1, Offset(width * 0.85f, centerY + 50f))
            updatePointerTo(0, Offset(width * 0.15f, centerY - 51f))
            updatePointerTo(1, Offset(width * 0.15f, centerY + 51f))
            move(300)
            up(0)
            up(1)
        }
        rule.waitForIdle()

        assertEquals(1, next.get())
    }

    @Test
    fun twoFingerPinchWithHorizontalTravelDoesNotTurnPage() {
        val next = AtomicInteger()
        renderPage(AtomicInteger(), next, fingerDrawing = true)

        rule.onNodeWithTag("page-viewport").performTouchInput {
            down(0, Offset(width * 0.85f, centerY - 50f))
            advanceEventTime(16)
            down(1, Offset(width * 0.85f, centerY + 50f))
            updatePointerTo(0, Offset(width * 0.15f, centerY - 120f))
            updatePointerTo(1, Offset(width * 0.15f, centerY + 120f))
            move(300)
            up(0)
            up(1)
        }
        rule.waitForIdle()

        assertEquals(0, next.get())
    }

    @Test
    fun twoFingerSwipeCancelsFingerEraserThenTurnsOnePage() =
        assertTwoFingerSwipeTurnsFromFingerTool(EditorTool.ERASER)

    @Test
    fun twoFingerSwipeCancelsFingerLassoThenTurnsOnePage() =
        assertTwoFingerSwipeTurnsFromFingerTool(EditorTool.LASSO)

    @Test
    fun fingerEraserTurnsPageWhenFingerDrawingIsOff() = assertFingerToolTurnsPage(EditorTool.ERASER)

    @Test
    fun fingerLassoTurnsPageWhenFingerDrawingIsOff() = assertFingerToolTurnsPage(EditorTool.LASSO)

    @Test
    fun toolChangeRestartsGestureOwnership() {
        val next = AtomicInteger()
        val erased = AtomicInteger()
        val selected = AtomicInteger()
        var tool by mutableStateOf(EditorTool.PEN)
        renderPage(
            previous = AtomicInteger(),
            next = next,
            fingerDrawing = false,
            toolProvider = { tool },
            eraseFinished = erased,
            lassoFinished = selected,
        )
        val bounds = rule.onNodeWithTag("page-viewport").fetchSemanticsNode().boundsInRoot
        val downTime = android.os.SystemClock.uptimeMillis()
        rule.runOnUiThread {
            dispatchFingerEvent(downTime, 0, MotionEvent.ACTION_DOWN, bounds, 0.85f)
            dispatchFingerEvent(downTime, 16, MotionEvent.ACTION_MOVE, bounds, 0.15f)
        }

        rule.runOnUiThread { tool = EditorTool.ERASER }
        rule.waitForIdle()
        rule.runOnUiThread {
            dispatchFingerEvent(downTime, 32, MotionEvent.ACTION_UP, bounds, 0.15f)
        }
        rule.waitForIdle()
        assertEquals(0, next.get())
        assertEquals(0, erased.get())
        assertEquals(0, selected.get())

    }

    @Test
    fun reusedPointerIdReplacementAfterOwnershipDoesNotTurnPage() {
        val next = AtomicInteger()
        val committed = AtomicInteger()
        renderPage(AtomicInteger(), next, fingerDrawing = true, committed = committed)
        val bounds = rule.onNodeWithTag("page-viewport").fetchSemanticsNode().boundsInRoot
        val startX = bounds.left + bounds.width * 0.85f
        val endX = bounds.left + bounds.width * 0.15f
        val centerY = bounds.center.y
        val downTime = android.os.SystemClock.uptimeMillis()

        rule.runOnUiThread {
            rule.activity.dispatchTouchEvent(
                fingerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, startX, centerY - 30f),
            )
            rule.activity.dispatchTouchEvent(
                twoFingerEvent(downTime, downTime + 8, pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), startX, centerY),
            )
            rule.activity.dispatchTouchEvent(
                twoFingerEvent(downTime, downTime + 16, pointerAction(MotionEvent.ACTION_POINTER_UP, 1), startX, centerY),
            )
            rule.activity.dispatchTouchEvent(
                twoFingerEvent(downTime, downTime + 24, pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), startX, centerY),
            )
            rule.activity.dispatchTouchEvent(
                twoFingerEvent(downTime, downTime + 32, MotionEvent.ACTION_MOVE, endX, centerY),
            )
            rule.activity.dispatchTouchEvent(
                twoFingerEvent(downTime, downTime + 40, pointerAction(MotionEvent.ACTION_POINTER_UP, 1), endX, centerY),
            )
            rule.activity.dispatchTouchEvent(
                fingerEvent(downTime, downTime + 48, MotionEvent.ACTION_UP, endX, centerY - 30f),
            )
        }
        rule.waitForIdle()

        assertEquals(0, next.get())
        assertEquals(0, committed.get())
    }

    @Test
    fun pageChangeBeforeUpDoesNotTurnFromOutgoingCanvas() {
        val next = AtomicInteger()
        var page by mutableStateOf(PageEntity("page-a", "notebook", 1, PaperTemplate.RULED.name, 595, 842))
        renderPage(
            previous = AtomicInteger(),
            next = next,
            fingerDrawing = false,
            pageTransitionEnabled = true,
            pageProvider = { page },
        )
        val bounds = rule.onNodeWithTag("page-viewport").fetchSemanticsNode().boundsInRoot
        val downTime = android.os.SystemClock.uptimeMillis()
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnUiThread {
                dispatchFingerEvent(downTime, 0, MotionEvent.ACTION_DOWN, bounds, 0.85f)
                dispatchFingerEvent(downTime, 16, MotionEvent.ACTION_MOVE, bounds, 0.15f)
                page = PageEntity("page-b", "notebook", 2, PaperTemplate.RULED.name, 595, 842)
            }
            rule.mainClock.advanceTimeByFrame()
            rule.runOnUiThread {
                dispatchFingerEvent(downTime, 32, MotionEvent.ACTION_UP, bounds, 0.15f)
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()

        assertEquals(0, next.get())
    }

    @Test
    fun finalUpCoordinateCanCrossStrictSwipeThreshold() {
        val next = AtomicInteger()
        renderPage(AtomicInteger(), next, fingerDrawing = false)
        val bounds = rule.onNodeWithTag("page-viewport").fetchSemanticsNode().boundsInRoot
        val downTime = android.os.SystemClock.uptimeMillis()

        rule.runOnUiThread {
            dispatchFingerEvent(downTime, 0, MotionEvent.ACTION_DOWN, bounds, 0.8f)
            dispatchFingerEvent(downTime, 16, MotionEvent.ACTION_MOVE, bounds, 0.57f)
            dispatchFingerEvent(downTime, 32, MotionEvent.ACTION_UP, bounds, 0.54f)
        }
        rule.waitForIdle()

        assertEquals(1, next.get())
    }

    @Test
    fun actionCancelNeverTurnsPage() {
        val next = AtomicInteger()
        renderPage(AtomicInteger(), next, fingerDrawing = false)
        val bounds = rule.onNodeWithTag("page-viewport").fetchSemanticsNode().boundsInRoot
        val downTime = android.os.SystemClock.uptimeMillis()

        rule.runOnUiThread {
            dispatchFingerEvent(downTime, 0, MotionEvent.ACTION_DOWN, bounds, 0.85f)
            dispatchFingerEvent(downTime, 16, MotionEvent.ACTION_MOVE, bounds, 0.15f)
        }
        assertEquals("MOVE must not finish the gesture", 0, next.get())

        rule.runOnUiThread {
            dispatchFingerEvent(downTime, 32, MotionEvent.ACTION_CANCEL, bounds, 0.15f)
        }
        rule.waitForIdle()

        assertEquals(0, next.get())
    }

    @Test
    fun zoomedPageDoesNotTurn() {
        val next = AtomicInteger()
        renderPage(AtomicInteger(), next, fingerDrawing = false)
        val viewport = rule.onNodeWithTag("page-viewport")

        viewport.performTouchInput {
            pinch(
                start0 = center + Offset(-40f, 0f),
                end0 = center + Offset(-140f, 0f),
                start1 = center + Offset(40f, 0f),
                end1 = center + Offset(140f, 0f),
                durationMillis = 300,
            )
        }
        viewport.performTouchInput {
            swipe(Offset(width * 0.85f, centerY), Offset(width * 0.15f, centerY), 300)
        }

        assertEquals(0, next.get())
    }

    @Test
    fun typeToolKeepsFinishedInkLayerMounted() {
        var tool by mutableStateOf(EditorTool.PEN)
        renderPage(
            previous = AtomicInteger(),
            next = AtomicInteger(),
            fingerDrawing = false,
            toolProvider = { tool },
        )
        assertNotNull(rule.activity.window.decorView.findInkCanvas())

        rule.runOnUiThread { tool = EditorTool.TYPE }
        rule.waitForIdle()

        assertNotNull(rule.activity.window.decorView.findInkCanvas())
    }

    @Test
    fun textSelectionDragDoesNotTurnPage() {
        val next = AtomicInteger()
        renderPage(AtomicInteger(), next, fingerDrawing = false, tool = EditorTool.TYPE)

        dispatchHorizontalFingerGesture()

        assertEquals(0, next.get())
    }

    @Test
    fun stylusOwnershipBlocksFingerPageTurn() {
        val next = AtomicInteger()
        renderPage(AtomicInteger(), next, fingerDrawing = false)
        val bounds = rule.onNodeWithTag("page-viewport").fetchSemanticsNode().boundsInRoot
        val startX = bounds.left + bounds.width * 0.85f
        val endX = bounds.left + bounds.width * 0.15f
        val centerY = bounds.center.y
        val downTime = android.os.SystemClock.uptimeMillis()

        rule.runOnUiThread {
            rule.activity.dispatchTouchEvent(stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, startX, centerY))
            rule.activity.dispatchTouchEvent(
                stylusAndFingerEvent(
                    downTime,
                    downTime + 16,
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    startX,
                    centerY,
                ),
            )
            rule.activity.dispatchTouchEvent(
                stylusAndFingerEvent(downTime, downTime + 32, MotionEvent.ACTION_MOVE, endX, centerY),
            )
            rule.activity.dispatchTouchEvent(
                stylusAndFingerEvent(
                    downTime,
                    downTime + 48,
                    MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    endX,
                    centerY,
                ),
            )
            rule.activity.dispatchTouchEvent(stylusEvent(downTime, downTime + 64, MotionEvent.ACTION_UP, endX, centerY))
        }
        rule.waitForIdle()

        assertEquals(0, next.get())
    }

    @Test
    fun firstAndLastPageDoNotTurnPastBounds() {
        val previous = AtomicInteger()
        renderPage(previous, AtomicInteger(), fingerDrawing = false, pageNumber = 1, pageCount = 3)
        rule.onNodeWithTag("page-viewport").performTouchInput {
            swipe(Offset(width * 0.15f, centerY), Offset(width * 0.85f, centerY), 300)
        }
        assertEquals(0, previous.get())

        val next = AtomicInteger()
        renderPage(AtomicInteger(), next, fingerDrawing = false, pageNumber = 3, pageCount = 3)
        rule.onNodeWithTag("page-viewport").performTouchInput {
            swipe(Offset(width * 0.85f, centerY), Offset(width * 0.15f, centerY), 300)
        }
        assertEquals(0, next.get())
    }

    @Test
    fun drawWithFingerUpdatesCurrentNotebookAndSurvivesReopen() {
        val title = openCompactEditor()

        rule.onNodeWithTag("compact-more").performClick()
        rule.onNodeWithTag("compact-more-finger-drawing").assertIsOff().performClick()
        rule.onNodeWithTag("compact-more-finger-drawing").assertDoesNotExist()
        rule.onNodeWithTag("compact-more").performClick()
        rule.onNodeWithTag("compact-more-finger-drawing").assertIsOn()

        pressBack()
        rule.onNodeWithTag("compact-back").performClick()
        rule.onNodeWithContentDescription("Open $title").performClick()
        rule.onNodeWithTag("editor-top-bar").assertIsDisplayed()
        rule.onNodeWithTag("compact-more").performClick()
        rule.onNodeWithTag("compact-more-finger-drawing").assertIsOn()
    }

    private fun renderPage(
        previous: AtomicInteger,
        next: AtomicInteger,
        fingerDrawing: Boolean,
        pageNumber: Int = 2,
        pageCount: Int = 3,
        committed: AtomicInteger = AtomicInteger(),
        tool: EditorTool = EditorTool.PEN,
        toolProvider: () -> EditorTool = { tool },
        eraseFinished: AtomicInteger = AtomicInteger(),
        lassoFinished: AtomicInteger = AtomicInteger(),
        pageTransitionEnabled: Boolean = false,
        pageProvider: () -> PageEntity = {
            PageEntity("page", "notebook", pageNumber - 1, PaperTemplate.RULED.name, 595, 842)
        },
    ) {
        rule.activity.setContent {
            SeliaDocsTheme {
                val page = pageProvider()
                val selectedTool = toolProvider()
                PageCanvas(
                    page = page,
                    pageNumber = page.pageIndex + 1,
                    pageCount = pageCount,
                    strokes = emptyList(),
                    elements = emptyList(),
                    blocks = emptyList(),
                    selectedStrokeIds = emptySet(),
                    selectedElementId = null,
                    fingerDrawing = fingerDrawing,
                    tool = selectedTool,
                    penWidth = 4f,
                    highlighterWidth = 16f,
                    pageTransitionEnabled = pageTransitionEnabled,
                    onPreviousPage = { previous.incrementAndGet() },
                    onNextPage = { next.incrementAndGet() },
                    onStrokeFinished = { _, _ -> committed.incrementAndGet() },
                    onEraseFinished = { _, _ -> eraseFinished.incrementAndGet() },
                    onSelectContent = { _, _ -> lassoFinished.incrementAndGet() },
                    onMoveSelection = { _, _ -> },
                    onPageTextChanged = { _, _ -> },
                    onCommitElementTransform = {},
                    assetFile = { File(it) },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun assertFingerToolTurnsPage(tool: EditorTool) {
        val next = AtomicInteger()
        val erased = AtomicInteger()
        val selected = AtomicInteger()
        renderPage(
            previous = AtomicInteger(),
            next = next,
            fingerDrawing = false,
            tool = tool,
            eraseFinished = erased,
            lassoFinished = selected,
        )
        dispatchHorizontalFingerGesture()

        assertEquals("$tool must allow page navigation", 1, next.get())
        assertEquals("$tool must ignore palm erasing", 0, erased.get())
        assertEquals("$tool must ignore palm selection", 0, selected.get())
    }

    private fun assertTwoFingerSwipeTurnsFromFingerTool(tool: EditorTool) {
        val next = AtomicInteger()
        val erased = AtomicInteger()
        val selected = AtomicInteger()
        renderPage(
            previous = AtomicInteger(),
            next = next,
            fingerDrawing = true,
            tool = tool,
            eraseFinished = erased,
            lassoFinished = selected,
        )

        rule.onNodeWithTag("page-viewport").performTouchInput {
            down(0, Offset(width * 0.85f, centerY - 30f))
            advanceEventTime(16)
            down(1, Offset(width * 0.85f, centerY + 30f))
            updatePointerTo(0, Offset(width * 0.15f, centerY - 30f))
            updatePointerTo(1, Offset(width * 0.15f, centerY + 30f))
            move(300)
            up(0)
            up(1)
        }
        rule.waitForIdle()

        assertEquals(1, next.get())
        assertEquals(0, erased.get())
        assertEquals(0, selected.get())
    }

    private fun dispatchHorizontalFingerGesture() {
        val bounds = rule.onNodeWithTag("page-viewport").fetchSemanticsNode().boundsInRoot
        val downTime = android.os.SystemClock.uptimeMillis()
        rule.runOnUiThread {
            dispatchFingerEvent(downTime, 0, MotionEvent.ACTION_DOWN, bounds, 0.65f)
            dispatchFingerEvent(downTime, 16, MotionEvent.ACTION_MOVE, bounds, 0.35f)
            dispatchFingerEvent(downTime, 32, MotionEvent.ACTION_UP, bounds, 0.35f)
        }
        rule.waitForIdle()
    }

    private fun dispatchFingerEvent(
        downTime: Long,
        elapsedMillis: Long,
        action: Int,
        bounds: Rect,
        xFraction: Float,
    ) {
        rule.activity.dispatchTouchEvent(
            fingerEvent(
                downTime,
                downTime + elapsedMillis,
                action,
                bounds.left + bounds.width * xFraction,
                bounds.center.y,
            ),
        )
    }

    private fun openCompactEditor(): String {
        rule.activity.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 744.dp)),
            ) {
                SeliaDocsApp()
            }
        }
        val title = "Page navigation ${System.nanoTime()}"
        rule.onNodeWithContentDescription("New notebook").performClick()
        rule.onNodeWithContentDescription("Notebook name").performTextInput(title)
        rule.onNodeWithText("Create notebook").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Open $title").performClick()
        rule.onNodeWithTag("editor-top-bar").assertIsDisplayed()
        return title
    }

    private fun View.findInkCanvas(): InkCanvasView? {
        if (this is InkCanvasView) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index -> getChildAt(index).findInkCanvas()?.let { return it } }
        return null
    }

    private fun stylusEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent =
        MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(pointer(0, MotionEvent.TOOL_TYPE_STYLUS)),
            arrayOf(coordinates(x, y, 0.7f)),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_STYLUS,
            0,
        )

    private fun fingerEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent =
        MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(pointer(0, MotionEvent.TOOL_TYPE_FINGER)),
            arrayOf(coordinates(x, y, 0.8f)),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )

    private fun stylusAndFingerEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent =
        MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            2,
            arrayOf(
                pointer(0, MotionEvent.TOOL_TYPE_STYLUS),
                pointer(1, MotionEvent.TOOL_TYPE_FINGER),
            ),
            arrayOf(
                coordinates(x, y, 0.7f),
                coordinates(x, y + 40f, 0.8f),
            ),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_STYLUS,
            0,
        )

    private fun twoFingerEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent =
        MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            2,
            arrayOf(
                pointer(0, MotionEvent.TOOL_TYPE_FINGER),
                pointer(1, MotionEvent.TOOL_TYPE_FINGER),
            ),
            arrayOf(
                coordinates(x, y - 30f, 0.8f),
                coordinates(x, y + 30f, 0.8f),
            ),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )

    private fun pointerAction(action: Int, pointerIndex: Int): Int =
        action or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun pointer(id: Int, toolType: Int) =
        MotionEvent.PointerProperties().apply {
            this.id = id
            this.toolType = toolType
        }

    private fun coordinates(x: Float, y: Float, pressure: Float) =
        MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            size = 0.2f
        }
}
