package com.majkeylab.seliadocs.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.settings.CustomThemeColors
import com.majkeylab.seliadocs.settings.InterfaceCorners
import com.majkeylab.seliadocs.settings.InterfaceFont
import com.majkeylab.seliadocs.settings.ThemePalette

internal const val DARK_SURFACE_LUMINANCE = 0.179f

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

internal fun themeColorScheme(
    darkTheme: Boolean,
    palette: ThemePalette,
    custom: CustomThemeColors = if (darkTheme) CustomThemeColors.Dark else CustomThemeColors(),
): ColorScheme =
    when (palette) {
        ThemePalette.CLASSIC -> if (darkTheme) DarkColors else LightColors
        ThemePalette.OCEAN -> paletteColors(darkTheme, Color(0xFF006778), Color(0xFFCEEFF5), Color(0xFF80D4E5), Color(0xFF004E5C))
        ThemePalette.FOREST -> paletteColors(darkTheme, Color(0xFF27653C), Color(0xFFD5EFDA), Color(0xFF8FD6A0), Color(0xFF205332))
        ThemePalette.ROSE -> paletteColors(darkTheme, Color(0xFF994260), Color(0xFFFFD9E4), Color(0xFFFFB1C7), Color(0xFF71364B))
        ThemePalette.AMBER -> paletteColors(darkTheme, Color(0xFF815411), Color(0xFFFFDFAD), Color(0xFFF5C274), Color(0xFF624318))
        ThemePalette.GRAPHITE -> paletteColors(darkTheme, Color(0xFF4C5765), Color(0xFFDEE3EB), Color(0xFFC3CAD3), Color(0xFF414953))
        ThemePalette.LAVENDER -> paletteColors(darkTheme, Color(0xFF6A438A), Color(0xFFEFDCFF), Color(0xFFD5B5F4), Color(0xFF523467))
        ThemePalette.PAPER -> customThemeColors(darkTheme, if (darkTheme) CustomThemeColors.Dark.copy(
            accent = 0xFFD8C6AA.toInt(), background = 0xFF24211C.toInt(), surface = 0xFF302B24.toInt(),
        ) else CustomThemeColors(accent = 0xFF675239.toInt(), background = 0xFFF0E8D8.toInt(), surface = 0xFFFFFAEF.toInt()))
        ThemePalette.COFFEE -> customThemeColors(darkTheme, if (darkTheme) CustomThemeColors.Dark.copy(
            accent = 0xFFF0BA94.toInt(), background = 0xFF211A17.toInt(), surface = 0xFF302520.toInt(),
        ) else CustomThemeColors(accent = 0xFF854622.toInt(), background = 0xFFF2E5DE.toInt(), surface = 0xFFFFF8F4.toInt()))
        ThemePalette.MIDNIGHT -> customThemeColors(darkTheme, if (darkTheme) CustomThemeColors.Dark.copy(
            accent = 0xFF91D6D1.toInt(), background = 0xFF000000.toInt(), surface = 0xFF101818.toInt(),
        ) else CustomThemeColors(accent = 0xFF1F5B66.toInt(), background = 0xFFE3EDF0.toInt(), surface = 0xFFF5FAFC.toInt()))
        ThemePalette.HIGH_CONTRAST -> customThemeColors(darkTheme, if (darkTheme) CustomThemeColors.Dark.copy(
            accent = 0xFFFFE063.toInt(), background = 0xFF000000.toInt(), surface = 0xFF000000.toInt(), text = 0xFFFFFFFF.toInt(),
        ) else CustomThemeColors(accent = 0xFF003A8C.toInt(), background = 0xFFFFFFFF.toInt(), surface = 0xFFFFFFFF.toInt(), text = 0xFF000000.toInt()))
        ThemePalette.MATERIAL -> if (darkTheme) darkColorScheme() else lightColorScheme()
        ThemePalette.CUSTOM -> customThemeColors(darkTheme, custom.validated())
    }

internal fun readableThemeColor(
    preferred: Color,
    background: Color,
    minimum: Float = 4.5f,
    otherBackground: Color = background,
    toward: Color? = null,
): Color {
    fun contrastOn(color: Color, other: Color): Float =
        (maxOf(color.luminance(), other.luminance()) + 0.05f) / (minOf(color.luminance(), other.luminance()) + 0.05f)
    fun contrast(color: Color): Float = minOf(contrastOn(color, background), contrastOn(color, otherBackground))
    fun readable(color: Color): Boolean = contrast(color) >= minimum &&
        (toward == null || (color.luminance() - background.luminance()) * (toward.luminance() - background.luminance()) >= 0f)
    if (readable(preferred)) return preferred
    val target = toward ?: if (contrast(Color.Black) > contrast(Color.White)) Color.Black else Color.White
    var low = 0f
    var high = 1f
    repeat(16) {
        val midpoint = (low + high) / 2f
        if (readable(lerp(preferred, target, midpoint))) high = midpoint else low = midpoint
    }
    return lerp(preferred, target, high)
}

private fun customThemeColors(darkTheme: Boolean, custom: CustomThemeColors): ColorScheme {
    val base = if (darkTheme) DarkPaletteColors else LightPaletteColors
    val surface = Color(custom.surface)
    val pole = if (surface.luminance() > DARK_SURFACE_LUMINANCE) Color.Black else Color.White
    val background = readableThemeColor(Color(custom.background), pole)
    fun sharedText(color: Color, minimum: Float = 4.5f) = readableThemeColor(color, surface, minimum, background, pole)
    val text = sharedText(Color(custom.text))
    val variant = lerp(surface, if (pole == Color.Black) Color.White else Color.Black, 0.06f)
    val primary = sharedText(Color(custom.accent))
    val secondary = sharedText(Color(custom.secondary))
    val primaryContainer = lerp(surface, primary, 0.15f)
    val secondaryContainer = lerp(surface, secondary, 0.15f)
    val error = sharedText(Color(custom.error))
    val errorContainer = lerp(surface, error, 0.15f)
    return base.copy(
        primary = primary, onPrimary = readableThemeColor(Color.White, primary),
        primaryContainer = primaryContainer, onPrimaryContainer = readableThemeColor(text, primaryContainer),
        secondary = secondary, onSecondary = readableThemeColor(Color.White, secondary),
        secondaryContainer = secondaryContainer, onSecondaryContainer = readableThemeColor(text, secondaryContainer),
        tertiary = primary, onTertiary = readableThemeColor(Color.White, primary),
        tertiaryContainer = primaryContainer, onTertiaryContainer = readableThemeColor(text, primaryContainer),
        background = background, onBackground = text,
        surface = surface, onSurface = text,
        surfaceVariant = variant, onSurfaceVariant = text,
        surfaceDim = variant, surfaceBright = surface, surfaceContainerLowest = surface,
        surfaceContainerLow = surface, surfaceContainer = surface, surfaceContainerHigh = variant, surfaceContainerHighest = variant,
        outline = sharedText(Color(custom.outline), 3f), outlineVariant = lerp(surface, text, 0.25f),
        inverseSurface = text, inverseOnSurface = readableThemeColor(surface, text),
        inversePrimary = readableThemeColor(primary, text), surfaceTint = primary,
        error = error, onError = readableThemeColor(Color.White, error),
        errorContainer = errorContainer, onErrorContainer = readableThemeColor(text, errorContainer),
    )
}

@Composable
internal fun resolvedThemeColors(darkTheme: Boolean, palette: ThemePalette, custom: CustomThemeColors): ColorScheme {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(darkTheme, palette, custom, context, configuration) {
        if (palette == ThemePalette.MATERIAL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else themeColorScheme(darkTheme, palette, custom)
    }
}

internal fun interfaceTypography(font: InterfaceFont, scale: Float): Typography {
    val family = when (font) {
        InterfaceFont.SYSTEM -> FontFamily.Default
        InterfaceFont.SERIF -> FontFamily.Serif
        InterfaceFont.MONOSPACE -> FontFamily.Monospace
    }
    fun TextStyle.adjusted() = copy(fontFamily = family, fontSize = fontSize * scale, lineHeight = lineHeight * scale)
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.adjusted(), displayMedium = base.displayMedium.adjusted(), displaySmall = base.displaySmall.adjusted(),
        headlineLarge = base.headlineLarge.adjusted(), headlineMedium = base.headlineMedium.adjusted(), headlineSmall = base.headlineSmall.adjusted(),
        titleLarge = base.titleLarge.adjusted(), titleMedium = base.titleMedium.adjusted(), titleSmall = base.titleSmall.adjusted(),
        bodyLarge = base.bodyLarge.adjusted(), bodyMedium = base.bodyMedium.adjusted(), bodySmall = base.bodySmall.adjusted(),
        labelLarge = base.labelLarge.adjusted(), labelMedium = base.labelMedium.adjusted(), labelSmall = base.labelSmall.adjusted(),
    )
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
    custom: CustomThemeColors = if (darkTheme) CustomThemeColors.Dark else CustomThemeColors(),
    font: InterfaceFont = InterfaceFont.SYSTEM,
    textScale: Float = 1f,
    corners: InterfaceCorners = InterfaceCorners.STANDARD,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = resolvedThemeColors(darkTheme, palette, custom),
        typography = remember(font, textScale) { interfaceTypography(font, textScale) },
        shapes = remember(corners) {
            when (corners) {
                InterfaceCorners.STANDARD -> Shapes()
                InterfaceCorners.SOFT -> Shapes(
                    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
                    large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp),
                )
                InterfaceCorners.SQUARE -> Shapes(
                    extraSmall = RoundedCornerShape(0.dp), small = RoundedCornerShape(0.dp), medium = RoundedCornerShape(0.dp),
                    large = RoundedCornerShape(0.dp), extraLarge = RoundedCornerShape(0.dp),
                )
            }
        },
        content = content,
    )
}
