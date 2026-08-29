package com.majkeylab.seliadocs.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

@Composable
internal fun SeliaDocsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
