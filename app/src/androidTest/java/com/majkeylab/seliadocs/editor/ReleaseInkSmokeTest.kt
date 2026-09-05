package com.majkeylab.seliadocs.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
    fun signedReleaseDrawsVisibleBlueStylusStroke() {
        assumeTrue(
            "Release ink smoke is opt-in",
            InstrumentationRegistry.getArguments().getString("releaseInkSmoke") == "true",
        )
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        assertEquals(
            "com.majkeylab.seliadocs",
            requireNotNull(automation.rootInActiveWindow).packageName?.toString(),
        )
        val before = requireNotNull(automation.takeScreenshot()) { "Pre-stroke screenshot unavailable" }
        var after: Bitmap? = null
        try {
            val sx = requiredCoordinate("startX", before.width)
            val sy = requiredCoordinate("startY", before.height)
            val ex = requiredCoordinate("endX", before.width)
            val ey = requiredCoordinate("endY", before.height)
            require(sx != ex || sy != ey) { "Stroke coordinates must differ" }
            val downTime = SystemClock.uptimeMillis()
            repeat(EVENT_COUNT) { index ->
                val progress = index.toFloat() / (EVENT_COUNT - 1)
                val action = when (index) {
                    0 -> MotionEvent.ACTION_DOWN
                    EVENT_COUNT - 1 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                val properties =
                    arrayOf(
                        MotionEvent.PointerProperties().apply {
                            id = 0
                            toolType = MotionEvent.TOOL_TYPE_STYLUS
                        },
                    )
                val coordinates =
                    arrayOf(
                        MotionEvent.PointerCoords().apply {
                            x = sx + (ex - sx) * progress
                            y = sy + (ey - sy) * progress
                            pressure = if (action == MotionEvent.ACTION_UP) 0f else 0.2f + 0.7f * progress
                            size = 0.08f
                        },
                    )
                val event = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), action, 1, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
                )
                try {
                    assertTrue("Stylus event $index was rejected", automation.injectInputEvent(event, true))
                } finally {
                    event.recycle()
                }
                if (index < EVENT_COUNT - 1) SystemClock.sleep(16)
            }
            SystemClock.sleep(750)
            automation.waitForIdle(200, 2_000)
            val captured = requireNotNull(automation.takeScreenshot()) { "Post-stroke screenshot unavailable" }
            after = captured
            assertTrue("Screenshot size changed", before.width == captured.width && before.height == captured.height)
            assertTrue(
                "No visible blue ink appeared near the injected stroke",
                visibleBlueChanges(before, captured, sx, sy, ex, ey) >= 12,
            )
        } finally {
            before.recycle()
            after?.recycle()
        }
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
