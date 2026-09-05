package com.majkeylab.seliadocs.editor

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseInkSmokeTest {
    @Test
    fun signedReleaseDrawsVisibleBlueInk() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Release ink smoke is opt-in",
            arguments.getString("releaseInkSmoke") == "true",
        )
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 5_000)
        assertEquals(
            "com.majkeylab.seliadocs",
            requireNotNull(automation.rootInActiveWindow).packageName?.toString(),
        )
        val before = requireNotNull(automation.takeScreenshot()) { "Pre-stroke screenshot unavailable" }
        var beforeStroke = before
        var after: Bitmap? = null
        try {
            val sx = requiredCoordinate("startX", before.width)
            val sy = requiredCoordinate("startY", before.height)
            val ex = requiredCoordinate("endX", before.width)
            val ey = requiredCoordinate("endY", before.height)
            require(sx != ex || sy != ey) { "Stroke coordinates must differ" }
            if (arguments.getString("pinchBeforeStroke") == "true") {
                val zoomBefore = requireNotNull(zoomPercent(automation)) { "Zoom state unavailable before pinch" }
                injectPinch(automation, sx, sy, ex, ey)
                automation.waitForIdle(200, 2_000)
                val zoomAfter = requireNotNull(zoomPercent(automation)) { "Zoom state unavailable after pinch" }
                assertTrue("Pinch did not increase zoom: $zoomBefore% to $zoomAfter%", zoomAfter > zoomBefore)
                beforeStroke = requireNotNull(automation.takeScreenshot()) { "Post-pinch screenshot unavailable" }
            }
            val toolType =
                if (arguments.getString("fingerInput") == "true") MotionEvent.TOOL_TYPE_FINGER
                else MotionEvent.TOOL_TYPE_STYLUS
            injectStroke(automation, sx, sy, ex, ey, toolType)
            SystemClock.sleep(750)
            automation.waitForIdle(200, 2_000)
            val captured = requireNotNull(automation.takeScreenshot()) { "Post-stroke screenshot unavailable" }
            after = captured
            assertTrue(
                "Screenshot size changed",
                beforeStroke.width == captured.width && beforeStroke.height == captured.height,
            )
            assertTrue(
                "No visible blue ink appeared near the injected stroke",
                visibleBlueChanges(beforeStroke, captured, sx, sy, ex, ey) >= 12,
            )
        } finally {
            if (beforeStroke !== before) beforeStroke.recycle()
            before.recycle()
            after?.recycle()
        }
    }

    private fun injectStroke(automation: UiAutomation, sx: Float, sy: Float, ex: Float, ey: Float, toolType: Int) {
        val downTime = SystemClock.uptimeMillis()
        repeat(EVENT_COUNT) { index ->
            val progress = index.toFloat() / (EVENT_COUNT - 1)
            val action = when (index) {
                0 -> MotionEvent.ACTION_DOWN
                EVENT_COUNT - 1 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = motionEvent(
                downTime = downTime,
                action = action,
                toolType = toolType,
                points = listOf(sx + (ex - sx) * progress to sy + (ey - sy) * progress),
                pressure = if (action == MotionEvent.ACTION_UP) 0f else 0.2f + 0.7f * progress,
                releasedPointer = 0.takeIf { action == MotionEvent.ACTION_UP },
            )
            inject(automation, event)
        }
    }

    private fun injectPinch(automation: UiAutomation, sx: Float, sy: Float, ex: Float, ey: Float) {
        val mx = (sx + ex) / 2f
        val my = (sy + ey) / 2f
        val start = listOf((mx + sx) / 2f to (my + sy) / 2f, (mx + ex) / 2f to (my + ey) / 2f)
        val end = listOf(sx to sy, ex to ey)
        val downTime = SystemClock.uptimeMillis()
        inject(automation, motionEvent(downTime, MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, start.take(1)))
        inject(
            automation,
            motionEvent(downTime, pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), MotionEvent.TOOL_TYPE_FINGER, start),
        )
        repeat(3) { index ->
            val progress = (index + 1) / 3f
            val points = start.zip(end) { from, to ->
                from.first + (to.first - from.first) * progress to
                    from.second + (to.second - from.second) * progress
            }
            inject(automation, motionEvent(downTime, MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_FINGER, points))
        }
        inject(
            automation,
            motionEvent(
                downTime,
                pointerAction(MotionEvent.ACTION_POINTER_UP, 1),
                MotionEvent.TOOL_TYPE_FINGER,
                end,
                releasedPointer = 1,
            ),
        )
        inject(
            automation,
            motionEvent(
                downTime,
                MotionEvent.ACTION_UP,
                MotionEvent.TOOL_TYPE_FINGER,
                end.take(1),
                releasedPointer = 0,
            ),
        )
    }

    private fun motionEvent(
        downTime: Long,
        action: Int,
        toolType: Int,
        points: List<Pair<Float, Float>>,
        pressure: Float = 0.8f,
        releasedPointer: Int? = null,
    ): MotionEvent {
        val properties = Array(points.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                this.toolType = toolType
            }
        }
        val coordinates = Array(points.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = points[index].first
                y = points[index].second
                this.pressure = if (index == releasedPointer) 0f else pressure
                size = 0.08f
            }
        }
        val source =
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
                InputDevice.SOURCE_STYLUS
            } else {
                InputDevice.SOURCE_TOUCHSCREEN
            }
        return MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, points.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, source, 0,
        )
    }

    private fun inject(automation: UiAutomation, event: MotionEvent) {
        try {
            assertTrue("Input event ${event.actionMasked} was rejected", automation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
        SystemClock.sleep(16)
    }

    private fun pointerAction(action: Int, pointerIndex: Int): Int =
        action or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun zoomPercent(automation: UiAutomation): Int? {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        automation.rootInActiveWindow?.let(nodes::add)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            val state = AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString()
            val zoom =
                state
                    ?.takeIf { it.startsWith("Zoom ") }
                    ?.substringAfter("Zoom ")
                    ?.substringBefore('%')
                    ?.toIntOrNull()
            repeat(node.childCount) { index -> node.getChild(index)?.let(nodes::add) }
            if (zoom != null) return zoom
        }
        return null
    }

    private fun requiredCoordinate(name: String, limit: Int): Float {
        val value = requireNotNull(InstrumentationRegistry.getArguments().getString(name)?.toFloatOrNull()) {
            "Missing or invalid $name"
        }
        require(value.isFinite() && value in SAMPLE_RADIUS.toFloat()..(limit - 1 - SAMPLE_RADIUS).toFloat()) {
            "$name is outside the screenshot"
        }
        return value
    }

    private fun visibleBlueChanges(before: Bitmap, after: Bitmap, sx: Float, sy: Float, ex: Float, ey: Float): Int {
        val changed = HashSet<Long>()
        repeat(SAMPLE_COUNT) { index ->
            val progress = index.toFloat() / (SAMPLE_COUNT - 1)
            val centerX = (sx + (ex - sx) * progress).roundToInt()
            val centerY = (sy + (ey - sy) * progress).roundToInt()
            for (dy in -SAMPLE_RADIUS..SAMPLE_RADIUS) for (dx in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
                val x = centerX + dx
                val y = centerY + dy
                val old = before.getPixel(x, y)
                val new = after.getPixel(x, y)
                val moved =
                    maxOf(
                        abs(Color.red(new) - Color.red(old)),
                        abs(Color.green(new) - Color.green(old)),
                        abs(Color.blue(new) - Color.blue(old)),
                    ) >= 20
                val blue =
                    Color.blue(new) >= 140 &&
                        Color.blue(new) > Color.red(new) + 25 &&
                        Color.blue(new) > Color.green(new) + 20
                if (moved && blue) changed += (y.toLong() shl 32) or (x.toLong() and 0xffffffffL)
            }
        }
        return changed.size
    }

    private companion object {
        const val EVENT_COUNT = 9
        const val SAMPLE_COUNT = 33
        const val SAMPLE_RADIUS = 12
    }
}
