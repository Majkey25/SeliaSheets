package com.majkeylab.seliadocs.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.majkeylab.seliadocs.settings.ThemePalette

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF3156D9),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE4FF),
        onPrimaryContainer = Color(0xFF0A2C76),
        secondary = Color(0xFF725A45),
        secondaryContainer = Color(0xFFF5E3D1),
        onSecondaryContainer = Color(0xFF2A180A),
        background = Color(0xFFECEAE5),
        onBackground = Color(0xFF202124),
        surface = Color(0xFFFBF8F1),
        onSurface = Color(0xFF202124),
        surfaceVariant = Color(0xFFE8E1D8),
        onSurfaceVariant = Color(0xFF5E5A54),
        outline = Color(0xFF77736C),
        outlineVariant = Color(0xFFC9C3BA),
        error = Color(0xFFB3261E),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFAFC6FF),
        onPrimary = Color(0xFF002A78),
        primaryContainer = Color(0xFF163F91),
        onPrimaryContainer = Color(0xFFDCE4FF),
        secondary = Color(0xFFE2C0A3),
        secondaryContainer = Color(0xFF58422F),
        onSecondaryContainer = Color(0xFFFFDCC2),
        background = Color(0xFF1B1B1A),
        onBackground = Color(0xFFE6E2DA),
        surface = Color(0xFF252421),
        onSurface = Color(0xFFE6E2DA),
        surfaceVariant = Color(0xFF494641),
        onSurfaceVariant = Color(0xFFCBC5BD),
        outline = Color(0xFF958F87),
        outlineVariant = Color(0xFF494641),
        error = Color(0xFFFFB4AB),
    )

private val LightPaletteColors =
    lightColorScheme(
        onPrimary = Color.White,
        onPrimaryContainer = Color(0xFF172126),
        secondary = Color(0xFF505E6A),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDEE5EC),
        onSecondaryContainer = Color(0xFF18242E),
        background = Color(0xFFF0F3F5),
        onBackground = Color(0xFF1A1C1E),
        surface = Color(0xFFF9FAFB),
        onSurface = Color(0xFF1A1C1E),
        surfaceVariant = Color(0xFFE4E8EC),
        onSurfaceVariant = Color(0xFF444B52),
        surfaceDim = Color(0xFFD9DEE2),
        surfaceBright = Color(0xFFF9FAFB),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF3F5F7),
        surfaceContainer = Color(0xFFEDF0F3),
        surfaceContainerHigh = Color(0xFFE7EBEF),
        surfaceContainerHighest = Color(0xFFE1E6EB),
        outline = Color(0xFF69757E),
        outlineVariant = Color(0xFFC4CDD5),
        inverseSurface = Color(0xFF2D3135),
        inverseOnSurface = Color(0xFFF0F3F5),
        error = Color(0xFFB3261E),
    )

private val DarkPaletteColors =
    darkColorScheme(
        onPrimary = Color(0xFF102023),
        onPrimaryContainer = Color(0xFFF0F4F5),
        secondary = Color(0xFFBDC7D0),
        onSecondary = Color(0xFF233039),
        secondaryContainer = Color(0xFF3A454F),
        onSecondaryContainer = Color(0xFFE1E7EC),
        background = Color(0xFF161A1D),
        onBackground = Color(0xFFE2E7EC),
        surface = Color(0xFF22272B),
        onSurface = Color(0xFFE2E7EC),
        surfaceVariant = Color(0xFF40474D),
        onSurfaceVariant = Color(0xFFC5CED6),
        surfaceDim = Color(0xFF101417),
        surfaceBright = Color(0xFF363D43),
        surfaceContainerLowest = Color(0xFF0C1013),
        surfaceContainerLow = Color(0xFF191E22),
        surfaceContainer = Color(0xFF1D2226),
        surfaceContainerHigh = Color(0xFF282E33),
        surfaceContainerHighest = Color(0xFF333A40),
        outline = Color(0xFF9BA4AD),
        outlineVariant = Color(0xFF414B54),
        inverseSurface = Color(0xFFE2E7EC),
        inverseOnSurface = Color(0xFF292F34),
        error = Color(0xFFFFB4AB),
    )

internal fun themeColorScheme(darkTheme: Boolean, palette: ThemePalette): ColorScheme =
    when (palette) {
        ThemePalette.CLASSIC -> if (darkTheme) DarkColors else LightColors
        ThemePalette.OCEAN -> paletteColors(darkTheme, Color(0xFF006778), Color(0xFFCEEFF5), Color(0xFF80D4E5), Color(0xFF004E5C))
        ThemePalette.FOREST -> paletteColors(darkTheme, Color(0xFF27653C), Color(0xFFD5EFDA), Color(0xFF8FD6A0), Color(0xFF205332))
        ThemePalette.ROSE -> paletteColors(darkTheme, Color(0xFF994260), Color(0xFFFFD9E4), Color(0xFFFFB1C7), Color(0xFF71364B))
        ThemePalette.AMBER -> paletteColors(darkTheme, Color(0xFF815411), Color(0xFFFFDFAD), Color(0xFFF5C274), Color(0xFF624318))
        ThemePalette.GRAPHITE -> paletteColors(darkTheme, Color(0xFF4C5765), Color(0xFFDEE3EB), Color(0xFFC3CAD3), Color(0xFF414953))
    }

private fun paletteColors(
    darkTheme: Boolean,
    lightAccent: Color,
    lightContainer: Color,
    darkAccent: Color,
    darkContainer: Color,
): ColorScheme {
    val colors = if (darkTheme) DarkPaletteColors else LightPaletteColors
    val accent = if (darkTheme) darkAccent else lightAccent
    val container = if (darkTheme) darkContainer else lightContainer
    return colors.copy(
        primary = accent,
        primaryContainer = container,
        tertiary = accent,
        onTertiary = colors.onPrimary,
        tertiaryContainer = container,
        onTertiaryContainer = colors.onPrimaryContainer,
        surfaceTint = accent,
        inversePrimary = if (darkTheme) lightAccent else darkAccent,
    )
}

@Composable
internal fun SeliaDocsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: ThemePalette = ThemePalette.CLASSIC,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = themeColorScheme(darkTheme, palette),
        content = content,
    )
}
