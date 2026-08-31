package com.majkeylab.seliadocs.editor

import android.graphics.Matrix
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.ink.strokes.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PaperTemplate
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PageViewportFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun externalTabletStylusPreservesPressureAtZoom() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("externalTabletStylus") == "true",
        )
        val finished = AtomicReference<Stroke>()
        renderPage(
            EditorTool.PEN,
            initialViewport = PageViewport(zoom = 2f),
            onStrokeFinished = finished::set,
        )
        Log.i("SeliaSheetsStylusQA", "READY")

        compose.waitUntil(30_000) { finished.get() != null }

        val stroke = requireNotNull(finished.get())
        val pressures = (0 until stroke.inputs.size).map { stroke.inputs[it].pressure }
        assertTrue(pressures.min() <= 0.25f)
        assertTrue(pressures.max() >= 0.75f)
        val first = stroke.inputs[0]
        assertEquals(595f * 0.5f, first.x, 12f)
        assertEquals(842f * 0.5f, first.y, 12f)
    }

    @Test
    fun externalTabletStylusDrawsAfterLivePinch() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("externalTabletStylus") == "true",
        )
        val finished = AtomicReference<Stroke>()
        renderPage(EditorTool.PEN, onStrokeFinished = finished::set)
        val viewport = compose.onNodeWithTag("page-viewport")
        Log.i("SeliaSheetsStylusQA", "READY_PINCH")

        compose.waitUntil(30_000) { finished.get() != null }

        assertTrue(zoomDescription(viewport).removePrefix("Zoom ").removeSuffix("%").toInt() > 100)
        val stroke = requireNotNull(finished.get())
        val pressures = (0 until stroke.inputs.size).map { stroke.inputs[it].pressure }
        assertTrue(pressures.min() <= 0.25f)
        assertTrue(pressures.max() >= 0.75f)
        val first = stroke.inputs[0]
        assertEquals(595f * 0.5f, first.x, 12f)
        assertEquals(842f * 0.5f, first.y, 12f)
    }

    @Test
    fun twoFingerPinchZoomsPage() {
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
            )
        }
        val viewport = compose.onNodeWithTag("page-viewport")

        viewport.performTouchInput {
            pinch(
                start0 = center + Offset(-40f, 0f),
                end0 = center + Offset(-140f, 0f),
                start1 = center + Offset(40f, 0f),
                end1 = center + Offset(140f, 0f),
                durationMillis = 300,
            )
        }
        compose.waitUntil(3_000) { zoomDescription(viewport) != "Zoom 100%" }

        assertTrue(zoomDescription(viewport).removePrefix("Zoom ").removeSuffix("%").toInt() > 100)
    }

    @Test
    fun rootStylusWithKnownZoomAndPanCommitsAtVisiblePaperPoint() {
        assumeTrue(android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.Q)
        val finished = AtomicReference<Stroke>()
        renderPage(
            EditorTool.PEN,
            initialViewport = PageViewport(zoom = 2f, panX = 120f, panY = -80f),
            onStrokeFinished = finished::set,
        )
        val viewport = compose.onNodeWithTag("page-viewport")
        assertEquals("Zoom 200%", zoomDescription(viewport))
        val rootPoint = visiblePaperPoint(xFraction = 0.5f, yFraction = 0.5f)
        val downTime = android.os.SystemClock.uptimeMillis()
        compose.runOnUiThread {
            val target: View
            val point: Offset
            if (android.os.Build.VERSION.SDK_INT == android.os.Build.VERSION_CODES.Q) {
                target = requireNotNull(findInkCanvas(compose.activity.window.decorView))
                point = Offset(target.width * 0.5f, target.height * 0.5f)
            } else {
                target = compose.activity.window.decorView
                point = rootPoint
            }
            target.dispatchTouchEvent(
                stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, point.x, point.y),
            )
            target.dispatchTouchEvent(
                stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_UP, point.x, point.y),
            )
        }
        compose.waitUntil(10_000) { finished.get() != null }

        val first = requireNotNull(finished.get()).inputs[0]
        assertEquals(595f * 0.5f, first.x, 4f)
        assertEquals(842f * 0.5f, first.y, 4f)
    }

    @Test
    fun rootStylusAfterPinchCommitsAtVisiblePaperPoint() {
        val finished = AtomicReference<Stroke>()
        renderPage(EditorTool.PEN, onStrokeFinished = finished::set)
        pinchToMaximumZoom()
        val rootPoint = visiblePaperPoint(xFraction = 0.58f, yFraction = 0.46f)
        val downTime = android.os.SystemClock.uptimeMillis()
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            decor.dispatchTouchEvent(
                stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, rootPoint.x, rootPoint.y),
            )
            decor.dispatchTouchEvent(
                stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_UP, rootPoint.x, rootPoint.y),
            )
        }
        compose.waitUntil(10_000) { finished.get() != null }

        val first = requireNotNull(finished.get()).inputs[0]
        assertEquals(595f * 0.58f, first.x, 4f)
        assertEquals(842f * 0.46f, first.y, 4f)
    }

    @Test
    fun rootLassoAfterPinchReturnsVisiblePaperPoint() {
        val selected = AtomicReference<List<CanvasPoint>>()
        renderPage(EditorTool.LASSO, onLassoFinished = selected::set)
        pinchToMaximumZoom()
        val rootPoint = visiblePaperPoint(xFraction = 0.55f, yFraction = 0.52f)
        val downTime = android.os.SystemClock.uptimeMillis()
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            decor.dispatchTouchEvent(
                stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, rootPoint.x, rootPoint.y),
            )
            decor.dispatchTouchEvent(
                stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_UP, rootPoint.x, rootPoint.y),
            )
        }
        compose.waitUntil(10_000) { selected.get() != null }

        val last = requireNotNull(selected.get()).last()
        assertEquals(595f * 0.55f, last.x, 4f)
        assertEquals(842f * 0.52f, last.y, 4f)
    }

    @Test
    fun rootEraserAfterPinchReturnsVisiblePaperPoint() {
        val erased = AtomicReference<List<CanvasPoint>>()
        renderPage(EditorTool.ERASER, onEraseFinished = erased::set)
        pinchToMaximumZoom()
        val rootPoint = visiblePaperPoint(xFraction = 0.45f, yFraction = 0.48f)
        val downTime = android.os.SystemClock.uptimeMillis()
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            decor.dispatchTouchEvent(
                stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, rootPoint.x, rootPoint.y),
            )
            decor.dispatchTouchEvent(
                stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_UP, rootPoint.x, rootPoint.y),
            )
        }
        compose.waitUntil(10_000) { erased.get() != null }

        val last = requireNotNull(erased.get()).last()
        assertEquals(595f * 0.45f, last.x, 4f)
        assertEquals(842f * 0.48f, last.y, 4f)
    }

    @Test
    fun rootLassoWithKnownZoomAndPanReturnsVisiblePaperPoint() {
        val selected = AtomicReference<List<CanvasPoint>>()
        renderPage(
            EditorTool.LASSO,
            initialViewport = PageViewport(zoom = 2f, panX = 120f, panY = -80f),
            onLassoFinished = selected::set,
        )
        val viewport = compose.onNodeWithTag("page-viewport")
        assertEquals("Zoom 200%", zoomDescription(viewport))
        val rootPoint = visiblePaperPoint(xFraction = 0.5f, yFraction = 0.5f)
        val downTime = android.os.SystemClock.uptimeMillis()
        compose.runOnUiThread {
            compose.activity.window.decorView.dispatchTouchEvent(
                stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, rootPoint.x, rootPoint.y),
            )
            compose.activity.window.decorView.dispatchTouchEvent(
                stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_UP, rootPoint.x, rootPoint.y),
            )
        }
        compose.waitUntil(10_000) { selected.get() != null }

        val last = requireNotNull(selected.get()).last()
        assertEquals(595f * 0.5f, last.x, 4f)
        assertEquals(842f * 0.5f, last.y, 4f)
    }

    @Test
    fun selectedElementRootDragDoesNotPanViewportOrTurnPage() {
        val committed = AtomicReference<ElementTransform>()
        val turns = AtomicInteger()
        val element = element()
        compose.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 1, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 2,
                pageCount = 3,
                strokes = emptyList(),
                elements = listOf(element),
                blocks = emptyList(),
                selectedStrokeIds = emptySet(),
                selectedElementId = element.id,
                fingerDrawing = false,
                tool = EditorTool.LASSO,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onPreviousPage = turns::incrementAndGet,
                onNextPage = turns::incrementAndGet,
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, _ -> },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { _, _ -> },
                onCommitElementTransform = committed::set,
                assetFile = { File(it) },
                initialViewport = PageViewport(zoom = 2f),
            )
        }
        compose.waitForIdle()
        val handleCenter =
            compose.onNodeWithTag("element-move-handle").fetchSemanticsNode().boundsInRoot.center
        val down = rootPoint(handleCenter)
        val move = down + Offset(300f, 0f)
        val before = visiblePaperPoint(xFraction = 0.5f, yFraction = 0.5f)
        val downTime = android.os.SystemClock.uptimeMillis()

        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            decor.dispatchTouchEvent(fingerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, down.x, down.y))
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            decor.dispatchTouchEvent(fingerEvent(downTime, downTime + 16, MotionEvent.ACTION_MOVE, move.x, move.y))
            decor.dispatchTouchEvent(fingerEvent(downTime, downTime + 32, MotionEvent.ACTION_UP, move.x, move.y))
        }
        compose.waitUntil(3_000) { committed.get() != null }
        val after = visiblePaperPoint(xFraction = 0.5f, yFraction = 0.5f)

        assertTrue(requireNotNull(committed.get()).x > element.x)
        assertEquals(0, turns.get())
        assertEquals(before.x, after.x, 0.5f)
        assertEquals(before.y, after.y, 0.5f)
    }

    private fun zoomDescription(viewport: androidx.compose.ui.test.SemanticsNodeInteraction): String =
        viewport.fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    private fun visiblePaperPoint(xFraction: Float, yFraction: Float): Offset {
        val result = AtomicReference<Offset>()
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            val canvas = requireNotNull(findInkCanvas(decor))
            val transform = Matrix()
            canvas.transformMatrixToGlobal(transform)
            decor.transformMatrixToLocal(transform)
            val point = floatArrayOf(canvas.width * xFraction, canvas.height * yFraction)
            transform.mapPoints(point)
            result.set(Offset(point[0], point[1]))
        }
        return requireNotNull(result.get())
    }

    private fun rootPoint(point: Offset): Offset {
        val result = AtomicReference<Offset>()
        compose.runOnUiThread {
            val location = IntArray(2)
            compose.activity.findViewById<View>(android.R.id.content).getLocationInWindow(location)
            result.set(point + Offset(location[0].toFloat(), location[1].toFloat()))
        }
        return requireNotNull(result.get())
    }

    private fun pinchToMaximumZoom() {
        val viewport = compose.onNodeWithTag("page-viewport")
        viewport.performTouchInput {
            pinch(
                start0 = center + Offset(-40f, 0f),
                end0 = center + Offset(-160f, 0f),
                start1 = center + Offset(40f, 0f),
                end1 = center + Offset(160f, 0f),
                durationMillis = 300,
            )
        }
        compose.waitUntil(3_000) {
            zoomDescription(viewport).removePrefix("Zoom ").removeSuffix("%").toInt() >= 300
        }
    }

    private fun findInkCanvas(view: View): InkCanvasView? {
        if (view is InkCanvasView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findInkCanvas(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun renderPage(
        tool: EditorTool,
        initialViewport: PageViewport = PageViewport(),
        onStrokeFinished: (Stroke) -> Unit = {},
        onLassoFinished: (List<CanvasPoint>) -> Unit = {},
        onEraseFinished: (List<CanvasPoint>) -> Unit = {},
    ) {
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
                tool = tool,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onPreviousPage = {},
                onNextPage = {},
                onStrokeFinished = { _, stroke -> onStrokeFinished(stroke) },
                onEraseFinished = { _, points -> onEraseFinished(points) },
                onSelectContent = { _, points -> onLassoFinished(points) },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { _, _ -> },
                onCommitElementTransform = {},
                assetFile = { File(it) },
                initialViewport = initialViewport,
            )
        }
        compose.waitForIdle()
        val readyAt = android.os.SystemClock.uptimeMillis() + 250L
        compose.waitUntil(1_000) { android.os.SystemClock.uptimeMillis() >= readyAt }
        if (android.os.Build.VERSION.SDK_INT == android.os.Build.VERSION_CODES.Q) {
            compose.runOnUiThread {
                val canvas = requireNotNull(findInkCanvas(compose.activity.window.decorView))
                val eventTime = android.os.SystemClock.uptimeMillis()
                canvas.onHoverEvent(
                    stylusEvent(eventTime, eventTime, MotionEvent.ACTION_HOVER_ENTER, 1f, 1f),
                )
            }
            val initializedAt = android.os.SystemClock.uptimeMillis() + 1_000L
            compose.waitUntil(2_000) { android.os.SystemClock.uptimeMillis() >= initializedAt }
        }
    }

    private fun stylusEvent(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                },
            ),
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = 0.7f
                },
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

    private fun fingerEvent(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            ),
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = 0.8f
                    size = 0.8f
                },
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

    private fun element() =
        ElementEntity(
            id = "element",
            pageId = "page",
            zIndex = 0,
            kind = "TEXT",
            x = 180f,
            y = 220f,
            width = 100f,
            height = 60f,
            rotation = 0f,
            text = "Physics",
            assetId = null,
            shapeKind = null,
            expression = null,
            resultText = null,
        )
}
