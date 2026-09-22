package com.majkeylab.seliadocs.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.majkeylab.seliadocs.settings.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePaletteTest {
    @Test
    fun allPalettesKeepTextReadableInBothAppearances() {
        for (dark in listOf(false, true)) {
            for (palette in ThemePalette.entries) {
                val colors = themeColorScheme(dark, palette)
                listOf(
                    colors.onPrimary to colors.primary,
                    colors.primary to colors.surface,
                    colors.primary to colors.background,
                    colors.onPrimaryContainer to colors.primaryContainer,
                    colors.onSecondary to colors.secondary,
                    colors.onSecondaryContainer to colors.secondaryContainer,
                    colors.onTertiary to colors.tertiary,
                    colors.onTertiaryContainer to colors.tertiaryContainer,
                    colors.onBackground to colors.background,
                    colors.onSurface to colors.surface,
                    colors.onSurfaceVariant to colors.surfaceVariant,
                    colors.inverseOnSurface to colors.inverseSurface,
                    colors.inversePrimary to colors.inverseSurface,
                    colors.onError to colors.error,
                ).forEach { (foreground, background) ->
                    assertTrue("$palette dark=$dark: $foreground on $background", contrast(foreground, background) >= 4.5f)
                }
            }
        }
    }

    @Test
    fun allPalettesKeepControlsAndSelectionVisible() {
        for (dark in listOf(false, true)) {
            for (palette in ThemePalette.entries) {
                val colors = themeColorScheme(dark, palette)
                listOf(colors.primary, colors.outline, colors.onSurfaceVariant).forEach { foreground ->
                    assertTrue("$palette dark=$dark: $foreground on surface", contrast(foreground, colors.surface) >= 3f)
                }
            }
        }
    }

    @Test
    fun classicPreservesExistingBrandAndSurfaceColors() {
        val light = themeColorScheme(false, ThemePalette.CLASSIC)
        assertEquals(Color(0xFF3156D9), light.primary)
        assertEquals(Color(0xFFDCE4FF), light.primaryContainer)
        assertEquals(Color(0xFF725A45), light.secondary)
        assertEquals(Color(0xFFFBF8F1), light.surface)
        assertEquals(Color(0xFFECEAE5), light.background)
        val dark = themeColorScheme(true, ThemePalette.CLASSIC)
        assertEquals(Color(0xFFAFC6FF), dark.primary)
        assertEquals(Color(0xFF163F91), dark.primaryContainer)
        assertEquals(Color(0xFFE2C0A3), dark.secondary)
        assertEquals(Color(0xFF252421), dark.surface)
        assertEquals(Color(0xFF1B1B1A), dark.background)
    }

    @Test
    fun sixPalettesHaveDistinctAccentsInBothAppearances() {
        assertEquals(6, ThemePalette.entries.size)
        for (dark in listOf(false, true)) {
            assertEquals(6, ThemePalette.entries.map { themeColorScheme(dark, it).primary }.toSet().size)
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val firstLuminance = first.luminance()
        val secondLuminance = second.luminance()
        return (maxOf(firstLuminance, secondLuminance) + 0.05f) /
            (minOf(firstLuminance, secondLuminance) + 0.05f)
    }
}
