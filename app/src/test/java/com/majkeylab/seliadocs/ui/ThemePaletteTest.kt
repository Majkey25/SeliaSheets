package com.majkeylab.seliadocs.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.majkeylab.seliadocs.settings.ThemePalette
import com.majkeylab.seliadocs.settings.CustomThemeColors
import com.majkeylab.seliadocs.settings.AppSettings
import com.majkeylab.seliadocs.settings.InterfaceFont
import com.majkeylab.seliadocs.settings.parseThemeColor
import com.majkeylab.seliadocs.settings.resetAppearance
import com.majkeylab.seliadocs.settings.themeColorHex
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
    fun palettesHaveDistinctAppearances() {
        for (dark in listOf(false, true)) {
            assertEquals(ThemePalette.entries.size, ThemePalette.entries.map {
                val colors = themeColorScheme(dark, it)
                Triple(colors.primary, colors.surface, colors.background)
            }.toSet().size)
        }
    }

    @Test
    fun customColorsKeepTextReadableEvenWhenEveryInputIsIdentical() {
        for (color in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF777777.toInt(), 0xFF808080.toInt(), 0xFFCC2266.toInt())) {
            val custom = CustomThemeColors(color, color, color, color, color, color, color)
            val colors = themeColorScheme(false, ThemePalette.CUSTOM, custom)
            listOf(colors.onSurface to colors.surface, colors.onBackground to colors.background,
                colors.onPrimary to colors.primary, colors.onPrimaryContainer to colors.primaryContainer,
                colors.onSecondary to colors.secondary, colors.onError to colors.error,
                colors.onSurface to colors.surfaceContainerHighest).forEach { (foreground, background) ->
                assertTrue("$foreground on $background", contrast(foreground, background) >= 4.5f)
            }
        }
    }

    @Test
    fun conflictingBackgroundsAreHarmonizedForSharedForegroundRoles() {
        for (panel in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF757575.toInt(), 0xFF777777.toInt())) {
            for (canvas in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF555555.toInt())) {
                val colors = themeColorScheme(false, ThemePalette.CUSTOM,
                    CustomThemeColors(background = canvas, surface = panel, text = 0xFF000000.toInt(), accent = 0xFFFFFFFF.toInt()))
                for (background in listOf(colors.surface, colors.surfaceVariant, colors.background)) {
                    for (text in listOf(colors.onSurface, colors.onSurfaceVariant, colors.primary, colors.secondary, colors.error)) {
                        assertTrue("$text on $background", contrast(text, background) >= 4.5f)
                    }
                }
            }
        }
    }

    @Test
    fun customHexRejectsInvalidInputAndResetPreservesDrawingSettings() {
        assertEquals(0xFF12ABEF.toInt(), parseThemeColor(" #12abef "))
        assertEquals("#12ABEF", themeColorHex(0xFF12ABEF.toInt()))
        for (invalid in listOf("", "#abc", "1234567", "12345g", "-12345", "FFFFFFFF")) assertEquals(null, parseThemeColor(invalid))
        val settings = AppSettings(penWidth = 9f, interfaceFont = InterfaceFont.SERIF, themePalette = ThemePalette.CUSTOM,
            interfaceTextScale = Float.NaN, lightCustomTheme = CustomThemeColors(accent = 0x123456))
        assertEquals(1f, settings.validated().interfaceTextScale)
        assertEquals(0xFF123456.toInt(), settings.validated().lightCustomTheme.accent)
        val reset = settings.resetAppearance()
        assertEquals(9f, reset.penWidth)
        assertEquals(InterfaceFont.SYSTEM, reset.interfaceFont)
        assertEquals(ThemePalette.CLASSIC, reset.themePalette)
    }

    private fun contrast(first: Color, second: Color): Float {
        val firstLuminance = first.luminance()
        val secondLuminance = second.luminance()
        return (maxOf(firstLuminance, secondLuminance) + 0.05f) /
            (minOf(firstLuminance, secondLuminance) + 0.05f)
    }
}
