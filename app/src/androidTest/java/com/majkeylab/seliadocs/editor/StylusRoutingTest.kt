package com.majkeylab.seliadocs.editor

import android.view.InputDevice
import android.view.MotionEvent
import androidx.ink.strokes.Stroke
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StylusRoutingTest {
    @Test
    fun completedStylusStrokeIsCommitted() {
        val committed = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
                view.post {
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
    fun hardwareEraserEmitsEraseGestureWithoutInk() {
        val erased = CountDownLatch(1)
        val finished = mutableListOf<Stroke>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
    fun lassoReturnsPageCoordinates() {
        val lasso = mutableListOf<CanvasPoint>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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

    private fun stylusEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
        flags: Int = 0,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
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
                pressure = 0.7f
                size = 0.1f
                orientation = 0.3f
                setAxisValue(MotionEvent.AXIS_TILT, 0.4f)
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
            InputDevice.SOURCE_STYLUS,
            flags,
        )
    }

    private fun exactly(size: Int): Int =
        android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)
}
