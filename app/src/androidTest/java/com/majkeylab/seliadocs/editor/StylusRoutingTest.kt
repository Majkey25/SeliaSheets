package com.majkeylab.seliadocs.editor

import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StylusRoutingTest {
    @Test
    fun stylusHoverPreviewFollowsHoverLifecycleWithoutCommittingInk() {
        val finished = mutableListOf<Stroke>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            finished += stroke
                        }

                        override fun onStrokeCanceled(pointerId: Int) = Unit
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)

                view.onHoverEvent(stylusHoverEvent(MotionEvent.ACTION_HOVER_ENTER, 100f, 120f))
                assertTrue(view.hoverPreviewVisible)
                view.onHoverEvent(stylusHoverEvent(MotionEvent.ACTION_HOVER_MOVE, 180f, 220f))
                assertTrue(view.hoverPreviewVisible)
                view.onHoverEvent(stylusHoverEvent(MotionEvent.ACTION_HOVER_EXIT, 180f, 220f))
                assertFalse(view.hoverPreviewVisible)
            }
        }

        assertTrue(finished.isEmpty())
    }

    @Test
    fun fingerHoverNeverShowsStylusPreview() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)

                view.onHoverEvent(
                    stylusHoverEvent(
                        MotionEvent.ACTION_HOVER_ENTER,
                        100f,
                        120f,
                        MotionEvent.TOOL_TYPE_FINGER,
                    ),
                )

                assertFalse(view.hoverPreviewVisible)
            }
        }
    }

    @Test
    fun fingerDownClearsStylusHoverWhenFingerDrawingIsOff() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                view.onHoverEvent(stylusHoverEvent(MotionEvent.ACTION_HOVER_ENTER, 100f, 120f))
                assertTrue(view.hoverPreviewVisible)

                val now = android.os.SystemClock.uptimeMillis()
                view.dispatchTouchEvent(fingerEvent(now, now, MotionEvent.ACTION_DOWN, 100f, 120f))

                assertFalse(view.hoverPreviewVisible)
            }
        }
    }

    @Test
    fun reattachedCanvasStillCommitsStylusStroke() {
        val committed = CountDownLatch(1)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val parent = FrameLayout(activity)
                val view = InkCanvasView(activity)
                val listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            committed.countDown()
                        }

                        override fun onStrokeCanceled(pointerId: Int) = Unit
                    }
                view.listener = listener
                parent.addView(view)
                activity.setContentView(parent)
                parent.removeView(view)
                parent.postDelayed(
                    {
                        val attachedView =
                            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                                InkCanvasView(activity).also { it.listener = listener }
                            } else {
                                view
                            }
                        parent.addView(attachedView)
                        attachedView.postWhenReady {
                            val downTime = android.os.SystemClock.uptimeMillis()
                            attachedView.dispatchTouchEvent(
                                stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 40f, 50f),
                            )
                            attachedView.dispatchTouchEvent(
                                stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_UP, 80f, 90f),
                            )
                        }
                    },
                    250L,
                )
            }
            assertTrue(committed.await(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun deviceLessSyntheticStylusDoesNotInventOptionalAxes() {
        val committed = CountDownLatch(1)
        val finished = AtomicReference<Stroke>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            finished.set(stroke)
                            committed.countDown()
                        }

                        override fun onStrokeCanceled(pointerId: Int) = Unit
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                view.postWhenReady {
                    val downTime = android.os.SystemClock.uptimeMillis()
                    view.dispatchTouchEvent(
                        stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 40f, 50f, pressure = 0.15f),
                    )
                    view.dispatchTouchEvent(
                        stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_MOVE, 80f, 90f, pressure = 1.4f),
                    )
                    view.dispatchTouchEvent(
                        stylusEvent(downTime, downTime + 32, MotionEvent.ACTION_UP, 100f, 120f, pressure = 0.9f),
                    )
                }
            }
            assertTrue(committed.await(10, TimeUnit.SECONDS))
        }

        val stroke = requireNotNull(finished.get())
        assertFalse(stroke.inputs.hasPressure())
        assertFalse(stroke.inputs.hasTilt())
        assertFalse(stroke.inputs.hasOrientation())
        assertEquals(
            StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1),
            stroke.brush.family,
        )
    }

    @Test
    fun pencilKeepsOneDynamicBrushAcrossInputTilt() {
        val committed = CountDownLatch(2)
        val finished = mutableListOf<Stroke>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.tool = EditorTool.PENCIL
                view.brush = InkCodec.createBrush(BrushKind.PENCIL, 0xFF202124.toInt(), 2.2f)
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            finished += stroke
                            committed.countDown()
                            if (finished.size == 1) {
                                view.post {
                                    val tiltedTime = android.os.SystemClock.uptimeMillis()
                                    view.dispatchTouchEvent(
                                        stylusEvent(
                                            tiltedTime,
                                            tiltedTime,
                                            MotionEvent.ACTION_DOWN,
                                            120f,
                                            130f,
                                            tilt = 1.2f,
                                        ),
                                    )
                                    view.dispatchTouchEvent(
                                        stylusEvent(
                                            tiltedTime,
                                            tiltedTime + 16,
                                            MotionEvent.ACTION_UP,
                                            180f,
                                            190f,
                                            tilt = 1.2f,
                                        ),
                                    )
                                }
                            }
                        }

                        override fun onStrokeCanceled(pointerId: Int) = Unit
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                view.postWhenReady {
                    val uprightTime = android.os.SystemClock.uptimeMillis()
                    view.dispatchTouchEvent(
                        stylusEvent(uprightTime, uprightTime, MotionEvent.ACTION_DOWN, 40f, 50f, tilt = 0f),
                    )
                    view.dispatchTouchEvent(
                        stylusEvent(uprightTime, uprightTime + 16, MotionEvent.ACTION_UP, 90f, 100f, tilt = 0f),
                    )
                }
            }
            assertTrue(committed.await(10, TimeUnit.SECONDS))
        }

        val upright = finished.first().brush
        val tilted = finished.last().brush
        assertEquals(SeliaInkBrushes.pencil, upright.family)
        assertEquals(SeliaInkBrushes.pencil, tilted.family)
        assertEquals(upright.size, tilted.size, 0.001f)
        assertEquals(upright.colorIntArgb, tilted.colorIntArgb)
    }

    @Test
    fun completedStylusStrokeIsCommitted() {
        val committed = CountDownLatch(1)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            committed.countDown()
                        }

                        override fun onStrokeCanceled(pointerId: Int) = Unit
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                view.postWhenReady {
                    val downTime = android.os.SystemClock.uptimeMillis()
                    view.dispatchTouchEvent(
                        stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 40f, 50f),
                    )
                    view.dispatchTouchEvent(
                        stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_MOVE, 80f, 90f),
                    )
                    view.dispatchTouchEvent(
                        stylusEvent(downTime, downTime + 32, MotionEvent.ACTION_UP, 100f, 120f),
                    )
                }
            }
            assertTrue(committed.await(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun canceledPalmStrokeIsNotCommitted() {
        val finished = mutableListOf<Stroke>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            finished += stroke
                        }

                        override fun onStrokeCanceled(pointerId: Int) = Unit
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                val downTime = 1_000L
                view.dispatchTouchEvent(
                    stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 40f, 50f),
                )
                view.dispatchTouchEvent(
                    stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_MOVE, 80f, 90f),
                )
                view.dispatchTouchEvent(
                    stylusEvent(
                        downTime,
                        downTime + 32,
                        MotionEvent.ACTION_UP,
                        100f,
                        120f,
                        MotionEvent.FLAG_CANCELED,
                    ),
                )
            }
        }

        assertTrue(finished.isEmpty())
    }

    @Test
    fun canceledFingerPointerDoesNotCancelActiveStylus() {
        val committed = CountDownLatch(1)
        val canceled = mutableListOf<Int>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            committed.countDown()
                        }

                        override fun onStrokeCanceled(pointerId: Int) {
                            canceled += pointerId
                        }
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                view.postWhenReady {
                    val downTime = android.os.SystemClock.uptimeMillis()
                    view.dispatchTouchEvent(
                        stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 40f, 50f),
                    )
                    view.dispatchTouchEvent(
                        stylusAndFingerEvent(
                            downTime,
                            downTime + 8,
                            pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1),
                        ),
                    )
                    view.dispatchTouchEvent(
                        stylusAndFingerEvent(downTime, downTime + 16, MotionEvent.ACTION_MOVE),
                    )
                    view.dispatchTouchEvent(
                        stylusAndFingerEvent(
                            downTime,
                            downTime + 24,
                            pointerAction(MotionEvent.ACTION_POINTER_UP, 1),
                            MotionEvent.FLAG_CANCELED,
                        ),
                    )
                    view.dispatchTouchEvent(
                        stylusEvent(downTime, downTime + 32, MotionEvent.ACTION_UP, 120f, 130f),
                    )
                }
            }
            assertTrue(committed.await(10, TimeUnit.SECONDS))
        }
        assertTrue(canceled.isEmpty())
    }

    @Test
    fun stylusCancelsActiveFingerStrokeBeforeDrawing() {
        val committed = CountDownLatch(1)
        val finished = mutableListOf<Stroke>()
        val canceled = mutableListOf<Int>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.fingerDrawing = true
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            finished += stroke
                            committed.countDown()
                        }

                        override fun onStrokeCanceled(pointerId: Int) {
                            canceled += pointerId
                        }
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                view.postWhenReady {
                    val downTime = android.os.SystemClock.uptimeMillis()
                    view.dispatchTouchEvent(
                        fingerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 40f, 50f),
                    )
                    view.dispatchTouchEvent(
                        fingerEvent(downTime, downTime + 8, MotionEvent.ACTION_MOVE, 60f, 70f),
                    )
                    view.dispatchTouchEvent(
                        stylusAndFingerEvent(
                            downTime,
                            downTime + 16,
                            pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1),
                            stylusPointerIndex = 1,
                        ),
                    )
                    view.dispatchTouchEvent(
                        stylusAndFingerEvent(
                            downTime,
                            downTime + 24,
                            MotionEvent.ACTION_MOVE,
                            stylusPointerIndex = 1,
                        ),
                    )
                    view.dispatchTouchEvent(
                        stylusAndFingerEvent(
                            downTime,
                            downTime + 32,
                            pointerAction(MotionEvent.ACTION_POINTER_UP, 1),
                            stylusPointerIndex = 1,
                        ),
                    )
                    view.dispatchTouchEvent(
                        fingerEvent(downTime, downTime + 40, MotionEvent.ACTION_UP, 120f, 130f),
                    )
                }
            }
            assertTrue(committed.await(10, TimeUnit.SECONDS))
            scenario.onActivity { }
        }

        assertTrue(canceled == listOf(0))
        assertTrue(finished.size == 1)
    }

    @Test
    fun palmDownBeforeStylusStillDeliversThePenStroke() {
        val committed = CountDownLatch(1)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val parent = FrameLayout(activity)
                val view = InkCanvasView(activity)
                parent.addView(view, FrameLayout.LayoutParams(500, 500))
                activity.setContentView(parent)
                view.fingerDrawing = false
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            committed.countDown()
                        }

                        override fun onStrokeCanceled(pointerId: Int) = Unit
                    }
                parent.measure(exactly(500), exactly(500))
                parent.layout(0, 0, 500, 500)
                view.postWhenReady {
                    val downTime = android.os.SystemClock.uptimeMillis()
                    parent.dispatchTouchEvent(fingerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 90f, 100f))
                    parent.dispatchTouchEvent(
                        stylusAndFingerEvent(
                            downTime,
                            downTime + 16,
                            pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1),
                            stylusPointerIndex = 1,
                        ),
                    )
                    parent.dispatchTouchEvent(
                        stylusAndFingerEvent(
                            downTime,
                            downTime + 32,
                            pointerAction(MotionEvent.ACTION_POINTER_UP, 1),
                            stylusPointerIndex = 1,
                        ),
                    )
                    parent.dispatchTouchEvent(fingerEvent(downTime, downTime + 48, MotionEvent.ACTION_UP, 90f, 100f))
                }
            }
            assertTrue(committed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun stylusCancelsFingerLassoThenStartsStylusLasso() {
        assertIncomingPenCancelsFingerGesture(
            fingerTool = EditorTool.LASSO,
            incomingTool = MotionEvent.TOOL_TYPE_STYLUS,
            expectErase = false,
        )
    }

    @Test
    fun hardwareEraserCancelsFingerEraserThenStartsErase() {
        assertIncomingPenCancelsFingerGesture(
            fingerTool = EditorTool.ERASER,
            incomingTool = MotionEvent.TOOL_TYPE_ERASER,
            expectErase = true,
        )
    }

    @Test
    fun secondFingerCancelsActiveFingerStrokeBeforePinch() {
        val canceled = mutableListOf<Int>()
        val finished = mutableListOf<Stroke>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.fingerDrawing = true
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            finished += stroke
                        }

                        override fun onStrokeCanceled(pointerId: Int) {
                            canceled += pointerId
                        }
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                val downTime = android.os.SystemClock.uptimeMillis()
                view.dispatchTouchEvent(
                    fingerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 40f, 50f),
                )
                view.dispatchTouchEvent(
                    twoFingerEvent(
                        downTime,
                        downTime + 8,
                        pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1),
                    ),
                )
            }
        }

        assertTrue(canceled == listOf(0))
        assertTrue(finished.isEmpty())
    }

    @Test
    fun hardwareEraserEmitsEraseGestureWithoutInk() {
        val erased = CountDownLatch(1)
        val finished = mutableListOf<Stroke>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            finished += stroke
                        }

                        override fun onStrokeCanceled(pointerId: Int) = Unit

                        override fun onEraseFinished(points: List<CanvasPoint>) {
                            if (points.isNotEmpty()) erased.countDown()
                        }
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                val downTime = android.os.SystemClock.uptimeMillis()
                view.dispatchTouchEvent(
                    stylusEvent(
                        downTime,
                        downTime,
                        MotionEvent.ACTION_DOWN,
                        40f,
                        50f,
                        toolType = MotionEvent.TOOL_TYPE_ERASER,
                    ),
                )
                view.dispatchTouchEvent(
                    stylusEvent(
                        downTime,
                        downTime + 16,
                        MotionEvent.ACTION_UP,
                        100f,
                        120f,
                        toolType = MotionEvent.TOOL_TYPE_ERASER,
                    ),
                )
            }
            assertTrue(erased.await(3, TimeUnit.SECONDS))
        }
        assertTrue(finished.isEmpty())
    }

    @Test
    fun stylusButtonsTemporarilyEraseWithoutInk() {
        val erased = CountDownLatch(2)
        val finished = mutableListOf<Stroke>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.tool = EditorTool.PEN
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            finished += stroke
                        }

                        override fun onStrokeCanceled(pointerId: Int) = Unit

                        override fun onEraseFinished(points: List<CanvasPoint>) {
                            if (points.isNotEmpty()) erased.countDown()
                        }
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                listOf(MotionEvent.BUTTON_STYLUS_PRIMARY, MotionEvent.BUTTON_STYLUS_SECONDARY).forEach { button ->
                    val downTime = android.os.SystemClock.uptimeMillis()
                    view.dispatchTouchEvent(
                        stylusEvent(
                            downTime,
                            downTime,
                            MotionEvent.ACTION_DOWN,
                            40f,
                            50f,
                            buttonState = button,
                        ),
                    )
                    view.dispatchTouchEvent(
                        stylusEvent(
                            downTime,
                            downTime + 16,
                            MotionEvent.ACTION_UP,
                            100f,
                            120f,
                            buttonState = button,
                        ),
                    )
                }
                assertEquals(EditorTool.PEN, view.tool)
            }
            assertTrue(erased.await(3, TimeUnit.SECONDS))
        }
        assertTrue(finished.isEmpty())
    }

    @Test
    fun lassoReturnsPageCoordinates() {
        val lasso = mutableListOf<CanvasPoint>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.tool = EditorTool.LASSO
                view.setPageSize(1_000, 1_000)
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) = Unit

                        override fun onStrokeCanceled(pointerId: Int) = Unit

                        override fun onLassoFinished(points: List<CanvasPoint>) {
                            lasso += points
                        }
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                val downTime = android.os.SystemClock.uptimeMillis()
                view.dispatchTouchEvent(
                    stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 50f, 50f),
                )
                view.dispatchTouchEvent(
                    stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_MOVE, 100f, 100f),
                )
                view.dispatchTouchEvent(
                    stylusEvent(downTime, downTime + 32, MotionEvent.ACTION_UP, 150f, 150f),
                )
            }
        }

        assertTrue(lasso.contains(CanvasPoint(300f, 300f)))
    }

    @Test
    fun enlargedCanvasLassoReturnsPageCoordinates() {
        val lasso = mutableListOf<CanvasPoint>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.tool = EditorTool.LASSO
                view.setPageSize(1_000, 1_000)
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) = Unit

                        override fun onStrokeCanceled(pointerId: Int) = Unit

                        override fun onLassoFinished(points: List<CanvasPoint>) {
                            lasso += points
                        }
                    }
                view.measure(exactly(1_000), exactly(1_000))
                view.layout(0, 0, 1_000, 1_000)
                val downTime = android.os.SystemClock.uptimeMillis()
                view.dispatchTouchEvent(
                    stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 300f, 200f),
                )
                view.dispatchTouchEvent(
                    stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_UP, 300f, 200f),
                )
            }
        }

        assertEquals(CanvasPoint(300f, 200f), lasso.last())
    }

    @Test
    fun enlargedCanvasStylusStrokeUsesPageCoordinates() {
        val committed = CountDownLatch(1)
        val finished = AtomicReference<Stroke>()
        val viewRef = AtomicReference<InkCanvasView>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view, android.view.ViewGroup.LayoutParams(1_000, 1_000))
                view.setPageSize(1_000, 1_000)
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            finished.set(stroke)
                            committed.countDown()
                        }

                        override fun onStrokeCanceled(pointerId: Int) = Unit
                    }
                view.measure(exactly(1_000), exactly(1_000))
                view.layout(0, 0, 1_000, 1_000)
                viewRef.set(view)
            }
            scenario.onActivity {
                val view = requireNotNull(viewRef.get())
                assertTrue(view.isAttachedToWindow)
                view.postWhenReady {
                    assertEquals(1_000, view.width)
                    assertEquals(1_000, view.height)
                    val downTime = android.os.SystemClock.uptimeMillis()
                    view.dispatchTouchEvent(
                        stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 300f, 200f),
                    )
                    view.dispatchTouchEvent(
                        stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_UP, 300f, 200f),
                    )
                }
            }
            assertTrue(committed.await(10, TimeUnit.SECONDS))
        }

        val first = requireNotNull(finished.get()).inputs[0]
        assertEquals(300f, first.x, 0.001f)
        assertEquals(200f, first.y, 0.001f)
    }

    @Test
    fun fingerIsIgnoredWhenFingerDrawingIsOff() {
        val finished = mutableListOf<Stroke>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            finished += stroke
                        }

                        override fun onStrokeCanceled(pointerId: Int) = Unit
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                val downTime = android.os.SystemClock.uptimeMillis()
                view.dispatchTouchEvent(fingerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 40f, 50f))
                view.dispatchTouchEvent(fingerEvent(downTime, downTime + 16, MotionEvent.ACTION_UP, 80f, 90f))
            }
        }

        assertTrue(finished.isEmpty())
    }

    @Test
    fun typeToolDoesNotCaptureStylusInput() {
        val finished = mutableListOf<Stroke>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.tool = EditorTool.TYPE
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) {
                            finished += stroke
                        }

                        override fun onStrokeCanceled(pointerId: Int) = Unit
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                val downTime = android.os.SystemClock.uptimeMillis()
                view.dispatchTouchEvent(stylusEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 40f, 50f))
                view.dispatchTouchEvent(stylusEvent(downTime, downTime + 16, MotionEvent.ACTION_UP, 80f, 90f))
            }
        }

        assertTrue(finished.isEmpty())
    }

    private fun assertIncomingPenCancelsFingerGesture(
        fingerTool: EditorTool,
        incomingTool: Int,
        expectErase: Boolean,
    ) {
        val canceled = mutableListOf<Int>()
        val erased = mutableListOf<List<CanvasPoint>>()
        val selected = mutableListOf<List<CanvasPoint>>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.fingerDrawing = true
                view.tool = fingerTool
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) = Unit

                        override fun onStrokeCanceled(pointerId: Int) {
                            canceled += pointerId
                        }

                        override fun onEraseFinished(points: List<CanvasPoint>) {
                            erased += points
                        }

                        override fun onLassoFinished(points: List<CanvasPoint>) {
                            selected += points
                        }
                    }
                view.measure(exactly(500), exactly(500))
                view.layout(0, 0, 500, 500)
                val downTime = android.os.SystemClock.uptimeMillis()
                view.dispatchTouchEvent(
                    fingerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, 40f, 50f),
                )
                view.dispatchTouchEvent(
                    stylusAndFingerEvent(
                        downTime,
                        downTime + 16,
                        pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1),
                        stylusPointerIndex = 1,
                        stylusToolType = incomingTool,
                    ),
                )
                view.dispatchTouchEvent(
                    stylusAndFingerEvent(
                        downTime,
                        downTime + 32,
                        pointerAction(MotionEvent.ACTION_POINTER_UP, 1),
                        stylusPointerIndex = 1,
                        stylusToolType = incomingTool,
                    ),
                )
                view.dispatchTouchEvent(
                    fingerEvent(downTime, downTime + 48, MotionEvent.ACTION_UP, 40f, 50f),
                )
            }
        }

        assertTrue(canceled == listOf(0))
        assertTrue(erased.size == if (expectErase) 1 else 0)
        assertTrue(selected.size == if (expectErase) 0 else 1)
    }

    private fun stylusEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
        flags: Int = 0,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        pressure: Float = 0.7f,
        buttonState: Int = 0,
        tilt: Float = 0.4f,
    ): MotionEvent {
        val properties =
            MotionEvent.PointerProperties().apply {
                id = 0
                this.toolType = toolType
            }
        val coordinates =
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                this.pressure = pressure
                size = 0.1f
                orientation = 0.3f
                setAxisValue(MotionEvent.AXIS_TILT, tilt)
            }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(properties),
            arrayOf(coordinates),
            0,
            buttonState,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_STYLUS,
            flags,
        )
    }

    private fun stylusAndFingerEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        flags: Int = 0,
        stylusPointerIndex: Int = 0,
        stylusToolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
    ): MotionEvent {
        val toolTypes =
            if (stylusPointerIndex == 0) {
                intArrayOf(stylusToolType, MotionEvent.TOOL_TYPE_FINGER)
            } else {
                intArrayOf(MotionEvent.TOOL_TYPE_FINGER, stylusToolType)
            }
        val properties =
            Array(2) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = toolTypes[index]
                }
            }
        val coordinates =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = 90f
                    y = 100f
                    pressure = 0.7f
                    size = 0.1f
                },
                MotionEvent.PointerCoords().apply {
                    x = 260f
                    y = 300f
                    pressure = 0.8f
                    size = 0.8f
                },
            )
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            properties.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_STYLUS,
            flags,
        )
    }

    private fun fingerEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent {
        val properties =
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        val coordinates =
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 0.8f
                size = 0.8f
            }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            arrayOf(properties),
            arrayOf(coordinates),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private fun twoFingerEvent(downTime: Long, eventTime: Long, action: Int): MotionEvent {
        val properties =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
                MotionEvent.PointerProperties().apply {
                    id = 1
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )
        val coordinates =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = 40f
                    y = 50f
                    pressure = 0.8f
                    size = 0.8f
                },
                MotionEvent.PointerCoords().apply {
                    x = 260f
                    y = 300f
                    pressure = 0.8f
                    size = 0.8f
                },
            )
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            properties.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private fun pointerAction(action: Int, pointerIndex: Int): Int =
        action or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun exactly(size: Int): Int =
        android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)

    private fun View.postWhenReady(block: () -> Unit) {
        postDelayed(
            {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    val eventTime = android.os.SystemClock.uptimeMillis()
                    onHoverEvent(
                        stylusEvent(eventTime, eventTime, MotionEvent.ACTION_HOVER_ENTER, 1f, 1f),
                    )
                    postDelayed(block, 1_000L)
                } else {
                    block()
                }
            },
            250L,
        )
    }

    private fun stylusHoverEvent(
        action: Int,
        x: Float,
        y: Float,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
    ): MotionEvent {
        val now = android.os.SystemClock.uptimeMillis()
        return MotionEvent.obtain(
            now,
            now,
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
                },
            ),
            0,
            0,
            1f,
            1f,
            0,
            0,
            if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
                InputDevice.SOURCE_TOUCHSCREEN
            } else {
                InputDevice.SOURCE_STYLUS
            },
            0,
        )
    }
}
