package cz.majkey.perko.ui

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
        background = Color(0xFFECEAE5),
        onBackground = Color(0xFF202124),
        surface = Color(0xFFFBF8F1),
        onSurface = Color(0xFF202124),
        error = Color(0xFFB3261E),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFAFC6FF),
        onPrimary = Color(0xFF002A78),
        background = Color(0xFF1B1B1A),
        onBackground = Color(0xFFE6E2DA),
        surface = Color(0xFF252421),
        onSurface = Color(0xFFE6E2DA),
        error = Color(0xFFFFB4AB),
    )

@Composable
internal fun PerkoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
