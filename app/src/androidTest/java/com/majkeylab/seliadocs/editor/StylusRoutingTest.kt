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
    fun canceledFingerPointerDoesNotCancelActiveStylus() {
        val committed = CountDownLatch(1)
        val canceled = mutableListOf<Int>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
                view.post {
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
    fun secondFingerCancelsActiveFingerStrokeBeforePinch() {
        val canceled = mutableListOf<Int>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = InkCanvasView(activity)
                activity.setContentView(view)
                view.fingerDrawing = true
                view.listener =
                    object : InkCanvasView.Listener {
                        override fun onStrokeFinished(stroke: Stroke) = Unit

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

    private fun stylusAndFingerEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        flags: Int = 0,
    ): MotionEvent {
        val properties =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                },
                MotionEvent.PointerProperties().apply {
                    id = 1
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )
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
}
