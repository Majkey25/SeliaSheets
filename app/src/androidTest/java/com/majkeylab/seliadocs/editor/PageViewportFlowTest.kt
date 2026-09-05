package com.majkeylab.seliadocs.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.StrokeEntity
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
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
        logInputMarker(
            "READY_PRESSURE",
            "pen",
            listOf(
                screenPaperPoint(0.5f, 0.5f),
                screenPaperPoint(0.55f, 0.5f),
                screenPaperPoint(0.6f, 0.5f),
            ),
        )

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
        logInputMarker(
            "READY_PINCH",
            "touch",
            listOf(
                screenPaperPoint(0.46f, 0.5f),
                screenPaperPoint(0.54f, 0.5f),
                screenPaperPoint(0.3f, 0.5f),
                screenPaperPoint(0.7f, 0.5f),
            ),
        )
        compose.waitUntil(30_000) {
            zoomDescription(viewport).removePrefix("Zoom ").removeSuffix("%").toInt() > 100
        }
        logInputMarker(
            "READY_AFTER_PINCH",
            "pen",
            listOf(
                screenPaperPoint(0.5f, 0.5f),
                screenPaperPoint(0.55f, 0.5f),
                screenPaperPoint(0.6f, 0.5f),
            ),
        )

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
    fun failedPdfRenderRetriesWithSameRequest() {
        val calls = AtomicInteger()
        val firstRequest = AtomicReference<Triple<String, Int, Int>>()
        val retryRequest = AtomicReference<Triple<String, Int, Int>>()
        val page =
            PageEntity(
                "pdf-page",
                "notebook",
                0,
                PaperTemplate.BLANK.name,
                595,
                842,
                pdfSourceId = "source",
                pdfPageIndex = 0,
            )
        renderPage(
            EditorTool.TYPE,
            page = page,
            loadPdfPage = { pageId, width, height ->
                val request = Triple(pageId, width, height)
                if (calls.getAndIncrement() == 0) {
                    firstRequest.set(request)
                    null
                } else {
                    retryRequest.set(request)
                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
            },
        )

        compose.onNodeWithTag("pdf-render-error").assertExists()
        assertEquals(1, calls.get())
        compose.onNodeWithTag("pdf-render-retry").performClick()
        compose.waitUntil(3_000) { calls.get() == 2 }

        compose.onNodeWithTag("pdf-rendered-page").assertExists()
        assertEquals(firstRequest.get(), retryRequest.get())
    }

    @Test
    fun pinchingPdfDoesNotRenderAgain() {
        val calls = AtomicInteger()
        val page =
            PageEntity(
                "pdf-page",
                "notebook",
                0,
                PaperTemplate.BLANK.name,
                595,
                842,
                pdfSourceId = "source",
                pdfPageIndex = 0,
            )
        renderPage(
            EditorTool.TYPE,
            page = page,
            loadPdfPage = { _, _, _ ->
                calls.incrementAndGet()
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
            },
        )
        compose.onNodeWithTag("pdf-rendered-page").assertExists()
        assertEquals(1, calls.get())

        pinchToMaximumZoom()
        compose.waitForIdle()

        assertEquals(1, calls.get())
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

    @Test
    fun selectedElementDoesNotBlockStylusErasers() {
        val erased = AtomicInteger()
        val lastErase = AtomicReference<List<CanvasPoint>>()
        val committed = AtomicReference<ElementTransform>()
        val element = element()
        renderSelectedElement(
            element = element,
            initialViewport = PageViewport(zoom = 2f, panX = 120f, panY = -80f),
            onEraseFinished = { points ->
                if (points.isNotEmpty()) {
                    lastErase.set(points)
                    erased.incrementAndGet()
                }
            },
            onCommit = committed::set,
        )
        val handle = compose.onNodeWithTag("element-move-handle").fetchSemanticsNode().boundsInRoot.center
        val rootPoint = rootPoint(handle)
        val movedPoint = rootPoint + Offset(80f, 0f)
        listOf(
            MotionEvent.TOOL_TYPE_ERASER to 0,
            MotionEvent.TOOL_TYPE_STYLUS to MotionEvent.BUTTON_STYLUS_PRIMARY,
            MotionEvent.TOOL_TYPE_STYLUS to MotionEvent.BUTTON_STYLUS_SECONDARY,
        ).forEach { (toolType, buttonState) ->
            dispatchStylusGesture(rootPoint, movedPoint, toolType, buttonState)
        }
        compose.waitForIdle()
        assertEquals("eraser callbacks; transform=$committed", 3, erased.get())

        val first = requireNotNull(lastErase.get()).first()
        assertEquals(element.x + element.width / 2f, first.x, 4f)
        assertEquals(element.y + element.height / 2f, first.y, 4f)
        assertEquals(null, committed.get())
    }

    @Test
    fun selectedInkHandlesDoNotBlockStylusErasers() {
        val erased = AtomicInteger()
        val committed = AtomicReference<InkSelectionTransform>()
        renderSelectedInk(
            onEraseFinished = { if (it.isNotEmpty()) erased.incrementAndGet() },
            onCommit = committed::set,
        )
        val handle = compose.onNodeWithTag("ink-resize-handle").fetchSemanticsNode().boundsInRoot.center
        val start = rootPoint(handle)

        listOf(
            MotionEvent.TOOL_TYPE_ERASER to 0,
            MotionEvent.TOOL_TYPE_STYLUS to MotionEvent.BUTTON_STYLUS_PRIMARY,
            MotionEvent.TOOL_TYPE_STYLUS to MotionEvent.BUTTON_STYLUS_SECONDARY,
        ).forEach { (toolType, buttonState) ->
            dispatchStylusGesture(start, start + Offset(80f, 0f), toolType, buttonState)
        }
        compose.waitForIdle()

        assertEquals(3, erased.get())
        assertEquals(null, committed.get())
    }

    @Test
    fun selectedInkCanStillResizeWithARegularStylus() {
        val committed = AtomicReference<InkSelectionTransform>()
        renderSelectedInk(onCommit = committed::set)
        val handle = compose.onNodeWithTag("ink-resize-handle").fetchSemanticsNode().boundsInRoot.center
        val start = rootPoint(handle)

        dispatchStylusGesture(start, start + Offset(80f, 80f))
        compose.waitUntil(3_000) { committed.get() != null }

        assertTrue(requireNotNull(committed.get()).scale > 1f)
    }

    @Test
    fun selectedInkBodyMovesWithARegularStylus() {
        val moved = AtomicReference<CanvasPoint>()
        renderSelectedInk(onMoveSelection = moved::set)
        val body = compose.onNodeWithTag("ink-move-handle").fetchSemanticsNode().boundsInRoot.center
        val start = rootPoint(body)

        dispatchStylusGesture(start, start + Offset(80f, 0f))
        compose.waitUntil(3_000) { moved.get() != null }

        assertTrue(requireNotNull(moved.get()).x > 0f)
        assertEquals(0f, requireNotNull(moved.get()).y, 1f)
    }

    @Test
    fun selectedElementCanStillMoveWithStylus() {
        val committed = AtomicReference<ElementTransform>()
        val selections = AtomicInteger()
        val element = element()
        renderSelectedElement(
            element = element,
            onSelectContent = { selections.incrementAndGet() },
            onCommit = committed::set,
        )
        val handle = compose.onNodeWithTag("element-move-handle").fetchSemanticsNode().boundsInRoot.center
        val start = rootPoint(handle)
        val end = start + Offset(120f, 0f)
        dispatchStylusGesture(start, end)
        compose.waitUntil(1_000) { committed.get() != null }

        assertTrue(requireNotNull(committed.get()).x > element.x)
        assertEquals(0, selections.get())
    }

    @Test
    fun reusedPointerIdDoesNotClearNextEraserGesture() {
        val erased = AtomicInteger()
        val element = element()
        renderSelectedElement(element, onEraseFinished = { erased.incrementAndGet() })
        val handle = compose.onNodeWithTag("element-move-handle").fetchSemanticsNode().boundsInRoot.center
        val point = rootPoint(handle)
        val moved = point + Offset(60f, 0f)
        val firstDown = android.os.SystemClock.uptimeMillis()
        val secondDown = firstDown + 32

        dispatchEvents(
            stylusEvent(firstDown, firstDown, MotionEvent.ACTION_DOWN, point.x, point.y, MotionEvent.TOOL_TYPE_ERASER),
            stylusEvent(firstDown, firstDown + 16, MotionEvent.ACTION_UP, point.x, point.y, MotionEvent.TOOL_TYPE_ERASER),
            stylusEvent(secondDown, secondDown, MotionEvent.ACTION_DOWN, point.x, point.y, MotionEvent.TOOL_TYPE_ERASER),
        )
        compose.waitForIdle()
        dispatchEvents(
            stylusEvent(secondDown, secondDown + 16, MotionEvent.ACTION_MOVE, moved.x, moved.y, MotionEvent.TOOL_TYPE_ERASER),
            stylusEvent(secondDown, secondDown + 32, MotionEvent.ACTION_UP, moved.x, moved.y, MotionEvent.TOOL_TYPE_ERASER),
        )
        compose.waitForIdle()

        assertEquals(2, erased.get())
    }

    @Test
    fun normalStylusCanStartBeforeEraserClearRuns() {
        val erased = AtomicInteger()
        val selected = AtomicInteger()
        val committed = AtomicReference<ElementTransform>()
        val finished = AtomicReference<Stroke>()
        val element = element()
        var tool by mutableStateOf(EditorTool.LASSO)
        var selectedElementId by mutableStateOf<String?>(element.id)
        renderSelectedElement(
            element,
            tool = { tool },
            selectedElementId = { selectedElementId },
            onStrokeFinished = finished::set,
            onEraseFinished = { erased.incrementAndGet() },
            onSelectContent = { selected.incrementAndGet() },
            onCommit = committed::set,
        )
        val handle = compose.onNodeWithTag("element-move-handle").fetchSemanticsNode().boundsInRoot.center
        val point = rootPoint(handle)
        val moved = point + Offset(80f, 0f)
        val eraserDown = android.os.SystemClock.uptimeMillis()
        val stylusDown = eraserDown + 32

        dispatchEvents(
            stylusEvent(eraserDown, eraserDown, MotionEvent.ACTION_DOWN, point.x, point.y, MotionEvent.TOOL_TYPE_ERASER),
            stylusEvent(eraserDown, eraserDown + 16, MotionEvent.ACTION_UP, point.x, point.y, MotionEvent.TOOL_TYPE_ERASER),
            stylusEvent(stylusDown, stylusDown, MotionEvent.ACTION_DOWN, point.x, point.y),
        )
        compose.waitForIdle()
        dispatchEvents(
            stylusEvent(stylusDown, stylusDown + 16, MotionEvent.ACTION_MOVE, moved.x, moved.y),
            stylusEvent(stylusDown, stylusDown + 32, MotionEvent.ACTION_UP, moved.x, moved.y),
        )
        compose.waitUntil(1_000) { committed.get() != null }

        assertEquals(1, erased.get())
        assertEquals(0, selected.get())
        assertTrue(requireNotNull(committed.get()).x > element.x)

        compose.runOnUiThread {
            tool = EditorTool.PEN
            selectedElementId = null
        }
        compose.waitForIdle()
        val inkStart = visiblePaperPoint(0.2f, 0.2f)
        dispatchStylusGesture(inkStart, inkStart + Offset(60f, 40f))
        compose.waitUntil(1_000) { finished.get() != null }

        assertEquals(0, selected.get())
    }

    @Test
    fun delayedSecondFingerPinchesSelectedElement() {
        val commits = AtomicInteger()
        renderSelectedElement(element(), onCommit = { commits.incrementAndGet() })
        val viewport = compose.onNodeWithTag("page-viewport")

        compose.onNodeWithTag("element-selection").performTouchInput {
            val start0 = center + Offset(-20f, 0f)
            val start1 = center + Offset(20f, 0f)
            down(0, start0)
            advanceEventTime(100)
            down(1, start1)
            updatePointerTo(0, center + Offset(-90f, 0f))
            updatePointerTo(1, center + Offset(90f, 0f))
            move(300)
            up(0)
            up(1)
        }
        compose.waitForIdle()

        assertTrue(zoomDescription(viewport).removePrefix("Zoom ").removeSuffix("%").toInt() > 100)
        assertEquals(0, commits.get())
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

    private fun screenPaperPoint(xFraction: Float, yFraction: Float): Offset {
        val result = AtomicReference<Offset>()
        compose.runOnUiThread {
            val canvas = requireNotNull(findInkCanvas(compose.activity.window.decorView))
            val transform = Matrix()
            canvas.transformMatrixToGlobal(transform)
            val point = floatArrayOf(canvas.width * xFraction, canvas.height * yFraction)
            transform.mapPoints(point)
            result.set(Offset(point[0], point[1]))
        }
        return requireNotNull(result.get())
    }

    private fun logInputMarker(name: String, kind: String, points: List<Offset>) {
        val coordinates = points.joinToString(";") { "${it.x.roundToInt()},${it.y.roundToInt()}" }
        Log.i("SeliaSheetsStylusQA", "$name $kind=$coordinates")
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

    private fun renderSelectedElement(
        element: ElementEntity,
        tool: () -> EditorTool = { EditorTool.LASSO },
        selectedElementId: () -> String? = { element.id },
        initialViewport: PageViewport = PageViewport(),
        onStrokeFinished: (Stroke) -> Unit = {},
        onEraseFinished: (List<CanvasPoint>) -> Unit = {},
        onSelectContent: (List<CanvasPoint>) -> Unit = {},
        onCommit: (ElementTransform) -> Unit = {},
    ) {
        compose.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                pageCount = 1,
                strokes = emptyList(),
                elements = listOf(element),
                blocks = emptyList(),
                selectedStrokeIds = emptySet(),
                selectedElementId = selectedElementId(),
                fingerDrawing = false,
                tool = tool(),
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onPreviousPage = {},
                onNextPage = {},
                onStrokeFinished = { _, stroke -> onStrokeFinished(stroke) },
                onEraseFinished = { _, points -> onEraseFinished(points) },
                onSelectContent = { _, points -> onSelectContent(points) },
                onMoveSelection = { _, _ -> },
                onPageTextChanged = { _, _ -> },
                onCommitElementTransform = onCommit,
                assetFile = { File(it) },
                initialViewport = initialViewport,
            )
        }
        awaitInkReady()
    }

    private fun renderSelectedInk(
        onEraseFinished: (List<CanvasPoint>) -> Unit = {},
        onCommit: (InkSelectionTransform) -> Unit = {},
        onMoveSelection: (CanvasPoint) -> Unit = {},
    ) {
        val stroke = strokeEntity()
        compose.setContent {
            PageCanvas(
                page = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
                pageNumber = 1,
                pageCount = 1,
                strokes = listOf(stroke),
                elements = emptyList(),
                blocks = emptyList(),
                selectedStrokeIds = setOf(stroke.id),
                selectedElementId = null,
                fingerDrawing = false,
                tool = EditorTool.LASSO,
                penWidth = 4f,
                highlighterWidth = 16f,
                pageTransitionEnabled = false,
                onPreviousPage = {},
                onNextPage = {},
                onStrokeFinished = { _, _ -> },
                onEraseFinished = { _, points -> onEraseFinished(points) },
                onSelectContent = { _, _ -> },
                onMoveSelection = { _, delta -> onMoveSelection(delta) },
                onPageTextChanged = { _, _ -> },
                onCommitElementTransform = {},
                onCommitInkTransform = onCommit,
                assetFile = { File(it) },
            )
        }
        awaitInkReady()
    }

    private fun dispatchStylusGesture(
        start: Offset,
        end: Offset,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        buttonState: Int = 0,
    ) {
        val downTime = android.os.SystemClock.uptimeMillis()
        dispatchEvents(
            stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, start.x, start.y, toolType, buttonState),
            stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_MOVE, end.x, end.y, toolType, buttonState),
            stylusEvent(downTime, downTime + 32, MotionEvent.ACTION_UP, end.x, end.y, toolType, buttonState),
        )
    }

    private fun dispatchEvents(vararg events: MotionEvent) {
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            events.forEach { event ->
                try {
                    decor.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
    }

    private fun awaitInkReady() {
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

    private fun renderPage(
        tool: EditorTool,
        page: PageEntity = PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842),
        loadPdfPage: suspend (String, Int, Int) -> androidx.compose.ui.graphics.ImageBitmap? = { _, _, _ -> null },
        initialViewport: PageViewport = PageViewport(),
        onStrokeFinished: (Stroke) -> Unit = {},
        onLassoFinished: (List<CanvasPoint>) -> Unit = {},
        onEraseFinished: (List<CanvasPoint>) -> Unit = {},
    ) {
        compose.setContent {
            PageCanvas(
                page = page,
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
                loadPdfPage = loadPdfPage,
                initialViewport = initialViewport,
            )
        }
        awaitInkReady()
    }

    private fun stylusEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        buttonState: Int = 0,
    ): MotionEvent =
        MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    this.toolType = toolType
                },
            ),
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = 0.7f
                    orientation = 0.3f
                    setAxisValue(MotionEvent.AXIS_TILT, 0.4f)
                },
            ),
            0,
            buttonState,
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

    private fun strokeEntity(): StrokeEntity {
        val inputs =
            MutableStrokeInputBatch().apply {
                add(InputToolType.STYLUS, 180f, 220f, 0L, 0.01f, 0.5f, 0f, 0f)
                add(InputToolType.STYLUS, 280f, 280f, 16L, 0.01f, 0.5f, 0f, 0f)
            }
        val encoded =
            InkCodec.encode(
                Stroke(
                    InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 4f),
                    inputs,
                ),
            )
        return StrokeEntity(
            "stroke",
            "page",
            0,
            encoded.brushKind.name,
            encoded.colorArgb,
            encoded.size,
            encoded.epsilon,
            encoded.inputs,
        )
    }
}
