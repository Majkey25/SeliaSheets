package com.majkeylab.seliadocs.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun notebookMotionStartsDisabled() {
        assertEquals(false, AppSettings().pageTransition)
    }

    @Test
    fun nonFiniteBrushWidthsFallBackToVisibleMinimum() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { width ->
            val settings = AppSettings(penWidth = width, highlighterWidth = width).validated()
            assertEquals(PEN_WIDTH_RANGE.start, settings.penWidth, 0f)
            assertEquals(HIGHLIGHTER_WIDTH_RANGE.start, settings.highlighterWidth, 0f)
        }
    }

    @Test
    fun penOpacityCannotBecomeInvisibleAndKeepsRgb() {
        listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3, 128 to 128, 255 to 255).forEach { (alpha, expected) ->
            val settings = AppSettings(penColorArgb = (alpha shl 24) or 0x00123456)
            assertEquals((expected shl 24) or 0x00123456, settings.validated().penColorArgb)
            assertEquals(settings.highlighterColorArgb, settings.validated().highlighterColorArgb)
        }
    }

    @Test
    fun highlighterOpacityIsClampedWithoutChangingRgbOrOtherSettings() {
        listOf(0 to 26, 25 to 26, 26 to 26, 102 to 102, 204 to 204, 205 to 204, 255 to 204)
            .forEach { (alpha, expectedAlpha) ->
                val settings = AppSettings(
                    penWidth = 7f,
                    highlighterWidth = 30f,
                    penColorArgb = 0xFF123456.toInt(),
                    highlighterColorArgb = (alpha shl 24) or 0x00A1B2C3,
                    fingerDrawing = true,
                    shapeAssist = false,
                )
                assertEquals(
                    "Highlighter alpha $alpha",
                    settings.copy(highlighterColorArgb = (expectedAlpha shl 24) or 0x00A1B2C3),
                    settings.validated(),
                )
            }
    }

    @Test
    fun defaultFortyPercentHighlighterRemainsUnchanged() {
        assertEquals(102, AppSettings().highlighterColorArgb ushr 24)
        assertEquals(AppSettings(), AppSettings().validated())
    }
}
