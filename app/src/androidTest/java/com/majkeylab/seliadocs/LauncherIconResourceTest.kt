package com.majkeylab.seliadocs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class LauncherIconResourceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun foregroundArtIsTransparentAndFitsTheAdaptiveSafePresentation() {
        val inset = foregroundInset()
        val bitmap = requireNotNull(
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_foreground_art),
        )
        try {
            assertTransparentBorder(bitmap)
            assertTrue(
                "Center subject must be visible",
                alphaAt(bitmap, bitmap.width / 2, bitmap.height / 2) > 0,
            )
            assertNoBakedBeigePlate(bitmap)

            val outerBounds = presentedBounds(bitmap, inset, minAlpha = VISIBLE_ALPHA)
            assertTrue(
                "Foreground alpha fringe exceeds the guaranteed adaptive safe circle: ${outerBounds.maxRadius}",
                outerBounds.maxRadius <= SAFE_CIRCLE_RADIUS,
            )
            val subjectBounds = presentedBounds(bitmap, inset, minAlpha = MEANINGFUL_ALPHA)
            assertTrue(
                "Meaningful foreground content exceeds the guaranteed adaptive safe circle: ${subjectBounds.maxRadius}",
                subjectBounds.maxRadius <= SAFE_CIRCLE_RADIUS,
            )
            assertTrue(
                "Meaningful foreground subject is too narrow",
                subjectBounds.right - subjectBounds.left >= MIN_MEANINGFUL_SUBJECT_SIZE,
            )
            assertTrue(
                "Meaningful foreground subject is too short",
                subjectBounds.bottom - subjectBounds.top >= MIN_MEANINGFUL_SUBJECT_SIZE,
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun adaptiveLaunchersUseBeigeBackgroundAndTransparentForegroundLayer() {
        assertAdaptiveLauncher(R.mipmap.ic_launcher)
        assertAdaptiveLauncher(R.mipmap.ic_launcher_round)

        assertEquals(INTENDED_FOREGROUND_INSET, foregroundInset(), INSET_TOLERANCE)
        assertEquals(0xFFE8DED1.toInt(), context.getColor(R.color.ic_launcher_background))
    }

    @Test
    fun monochromeLauncherLayerUsesMatchingTransparentArtwork() {
        assertEquals(
            INTENDED_FOREGROUND_INSET,
            insetFraction(R.drawable.ic_launcher_monochrome, R.drawable.ic_launcher_foreground_art),
            INSET_TOLERANCE,
        )
    }

    @Test
    fun legacyDensityFallbacksRemainDecodableFullIcons() {
        ZipFile(File(context.applicationInfo.sourceDir)).use { apk ->
            LEGACY_ICON_SIZES.forEach { (density, size) ->
                listOf("ic_launcher.png", "ic_launcher_round.png").forEach { name ->
                    val entry = apk.entries().asSequence().firstOrNull {
                        it.name.contains("mipmap-$density") && it.name.endsWith("/$name")
                    }
                    assertNotNull("Missing $density $name fallback", entry)
                    val bytes = apk.getInputStream(requireNotNull(entry)).readBytes()
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    try {
                        assertEquals("$density $name width", size, bitmap.width)
                        assertEquals("$density $name height", size, bitmap.height)
                        val bounds = opaqueBounds(bitmap)
                        assertEquals("$density $name opaque left", 0, bounds.left)
                        assertEquals("$density $name opaque top", 0, bounds.top)
                        assertEquals("$density $name opaque right", bitmap.width, bounds.right)
                        assertEquals("$density $name opaque bottom", bitmap.height, bounds.bottom)
                        assertEquals("$density $name top-left alpha", 255, alphaAt(bitmap, 0, 0))
                        assertEquals("$density $name top-right alpha", 255, alphaAt(bitmap, bitmap.width - 1, 0))
                        assertEquals("$density $name bottom-left alpha", 255, alphaAt(bitmap, 0, bitmap.height - 1))
                        assertEquals("$density $name bottom-right alpha", 255, alphaAt(bitmap, bitmap.width - 1, bitmap.height - 1))
                        assertEquals("$density $name center alpha", 255, alphaAt(bitmap, bitmap.width / 2, bitmap.height / 2))
                        assertTrue("$density $name needs meaningful color variation", opaqueColorCount(bitmap) >= MIN_LEGACY_COLORS)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun assertAdaptiveLauncher(resourceId: Int) {
        val parser = context.resources.getXml(resourceId)
        parser.moveToStartTag()
        assertEquals("adaptive-icon", parser.name)

        var background = 0
        var foreground = 0
        var monochrome = 0
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "background" -> background = parser.getAttributeResourceValue(ANDROID_NS, "drawable", 0)
                "foreground" -> foreground = parser.getAttributeResourceValue(ANDROID_NS, "drawable", 0)
                "monochrome" -> monochrome = parser.getAttributeResourceValue(ANDROID_NS, "drawable", 0)
            }
        }
        assertEquals(R.color.ic_launcher_background, background)
        assertEquals(R.drawable.ic_launcher_foreground, foreground)
        assertEquals(R.drawable.ic_launcher_monochrome, monochrome)
    }

    private fun opaqueBounds(bitmap: Bitmap): Bounds {
        var left = bitmap.width
        var top = bitmap.height
        var right = 0
        var bottom = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (alphaAt(bitmap, x, y) == 0) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
            }
        }
        assertTrue("Bitmap must contain visible pixels", right > left && bottom > top)
        return Bounds(left, top, right, bottom)
    }

    private fun alphaAt(bitmap: Bitmap, x: Int, y: Int): Int = bitmap.getPixel(x, y) ushr 24

    private fun assertTransparentBorder(bitmap: Bitmap) {
        for (x in 0 until bitmap.width) {
            assertEquals("Foreground top border must be transparent", 0, alphaAt(bitmap, x, 0))
            assertEquals("Foreground bottom border must be transparent", 0, alphaAt(bitmap, x, bitmap.height - 1))
        }
        for (y in 1 until bitmap.height - 1) {
            assertEquals("Foreground left border must be transparent", 0, alphaAt(bitmap, 0, y))
            assertEquals("Foreground right border must be transparent", 0, alphaAt(bitmap, bitmap.width - 1, y))
        }
    }

    private fun assertNoBakedBeigePlate(bitmap: Bitmap) {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel ushr 24 < MEANINGFUL_ALPHA) continue
                val red = pixel shr 16 and 0xFF
                val green = pixel shr 8 and 0xFF
                val blue = pixel and 0xFF
                val distance =
                    (red - BACKGROUND_RED) * (red - BACKGROUND_RED) +
                        (green - BACKGROUND_GREEN) * (green - BACKGROUND_GREEN) +
                        (blue - BACKGROUND_BLUE) * (blue - BACKGROUND_BLUE)
                assertTrue(
                    "Meaningful foreground pixel at $x,$y is too close to the baked beige background",
                    distance > MIN_BACKGROUND_DISTANCE_SQUARED,
                )
            }
        }
    }

    private fun opaqueColorCount(bitmap: Bitmap): Int {
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel ushr 24 == 255) colors += pixel and 0x00FFFFFF
                if (colors.size >= MIN_LEGACY_COLORS) return colors.size
            }
        }
        return colors.size
    }

    private fun foregroundInset(): Double =
        insetFraction(R.drawable.ic_launcher_foreground, R.drawable.ic_launcher_foreground_art)

    private fun insetFraction(resourceId: Int, expectedDrawableId: Int): Double {
        val parser = context.resources.getXml(resourceId)
        parser.moveToStartTag()
        assertEquals("inset", parser.name)
        assertEquals(
            expectedDrawableId,
            parser.getAttributeResourceValue(ANDROID_NS, "drawable", 0),
        )
        val value = requireNotNull(parser.getAttributeValue(ANDROID_NS, "inset"))
        val inset = value.removeSuffix("%").toDouble() / 100
        return inset
    }

    private fun presentedBounds(bitmap: Bitmap, inset: Double, minAlpha: Int): PresentedBounds {
        var left = 1.0
        var top = 1.0
        var right = 0.0
        var bottom = 0.0
        var maxRadius = 0.0
        val scale = CHILD_EXPANSION * (1 - 2 * inset)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (alphaAt(bitmap, x, y) < minAlpha) continue
                val presentedX = 0.5 + scale * ((x + 0.5) / bitmap.width.toDouble() - 0.5)
                val presentedY = 0.5 + scale * ((y + 0.5) / bitmap.height.toDouble() - 0.5)
                left = minOf(left, presentedX)
                top = minOf(top, presentedY)
                right = maxOf(right, presentedX)
                bottom = maxOf(bottom, presentedY)
                maxRadius = maxOf(maxRadius, kotlin.math.hypot(presentedX - 0.5, presentedY - 0.5))
            }
        }
        assertTrue("Foreground must contain alpha >= $minAlpha", right > left && bottom > top)
        return PresentedBounds(left, top, right, bottom, maxRadius)
    }

    private fun XmlPullParser.moveToStartTag() {
        while (eventType != XmlPullParser.START_TAG) next()
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun width(): Int = right - left
    }

    private data class PresentedBounds(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val maxRadius: Double,
    )

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val CHILD_EXPANSION = 1.5
        const val SAFE_CIRCLE_RADIUS = 33.0 / 108.0
        const val INTENDED_FOREGROUND_INSET = 0.28
        const val INSET_TOLERANCE = 0.000001
        const val VISIBLE_ALPHA = 8
        const val MEANINGFUL_ALPHA = 128
        const val MIN_MEANINGFUL_SUBJECT_SIZE = 0.35
        const val BACKGROUND_RED = 0xE8
        const val BACKGROUND_GREEN = 0xDE
        const val BACKGROUND_BLUE = 0xD1
        const val MIN_BACKGROUND_DISTANCE_SQUARED = 16 * 16
        const val MIN_LEGACY_COLORS = 8
        val LEGACY_ICON_SIZES = mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)
    }
}
