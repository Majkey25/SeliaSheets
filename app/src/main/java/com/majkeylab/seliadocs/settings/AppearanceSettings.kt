package com.majkeylab.seliadocs.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.ui.ThemePalettePicker
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun AppearanceSettings(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
    AppearanceLabel(R.string.appearance_mode)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppTheme.entries.forEach { theme ->
            ThemePreviewChoice(theme, theme == settings.theme, { onUpdate { it.copy(theme = theme) } }, Modifier.weight(1f))
        }
    }
    AppearanceLabel(R.string.appearance_themes)
    InfoText(stringResource(R.string.appearance_themes_hint))
    ThemePalettePicker(
        selected = settings.themePalette,
        onSelect = { palette -> onUpdate { it.copy(themePalette = palette, darkThemePalette = it.darkThemePalette ?: it.themePalette) } },
        darkSelected = settings.darkThemePalette ?: settings.themePalette,
        onSelectDark = { palette -> onUpdate { it.copy(darkThemePalette = palette) } },
        lightCustom = settings.lightCustomTheme,
        darkCustom = settings.darkCustomTheme,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    InfoText(stringResource(R.string.material_theme_hint))
    if (settings.themePalette == ThemePalette.CUSTOM) {
        CustomThemeSettings(false, settings.lightCustomTheme) { transform -> onUpdate { it.copy(lightCustomTheme = transform(it.lightCustomTheme)) } }
    }
    if ((settings.darkThemePalette ?: settings.themePalette) == ThemePalette.CUSTOM) {
        CustomThemeSettings(true, settings.darkCustomTheme) { transform -> onUpdate { it.copy(darkCustomTheme = transform(it.darkCustomTheme)) } }
    }
    ChoiceSetting(stringResource(R.string.interface_font), InterfaceFont.entries, settings.interfaceFont, {
        stringResource(when (it) {
            InterfaceFont.SYSTEM -> R.string.font_system
            InterfaceFont.SERIF -> R.string.font_serif
            InterfaceFont.MONOSPACE -> R.string.font_monospace
        })
    }) { font -> onUpdate { it.copy(interfaceFont = font) } }
    var textScale by remember(settings.interfaceTextScale) { mutableFloatStateOf(settings.interfaceTextScale) }
    val textSizeLabel = stringResource(R.string.interface_text_size)
    val percentage = stringResource(R.string.interface_text_percent, (textScale * 100).roundToInt())
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("$textSizeLabel · $percentage", style = MaterialTheme.typography.titleSmall)
        Surface(
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.appearance_text_sample), modifier = Modifier.testTag("appearance-text-preview"),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp * textScale, lineHeight = 24.sp * textScale))
            }
        }
        Slider(
            value = textScale, valueRange = INTERFACE_TEXT_SCALE_RANGE,
            onValueChange = { textScale = it }, onValueChangeFinished = { onUpdate { it.copy(interfaceTextScale = textScale) } },
            modifier = Modifier.testTag("interface-text-scale").semantics { contentDescription = textSizeLabel; stateDescription = percentage },
        )
    }
    ChoiceSetting(stringResource(R.string.interface_corners), InterfaceCorners.entries, settings.interfaceCorners, {
        stringResource(when (it) {
            InterfaceCorners.STANDARD -> R.string.corners_standard
            InterfaceCorners.SOFT -> R.string.corners_soft
            InterfaceCorners.SQUARE -> R.string.corners_square
        })
    }) { corners -> onUpdate { it.copy(interfaceCorners = corners) } }
    SwitchSetting(stringResource(R.string.page_transition), settings.pageTransition) { enabled -> onUpdate { it.copy(pageTransition = enabled) } }
    InfoText(stringResource(R.string.notebook_motion_hint))
    TextButton(onClick = { onUpdate { it.resetAppearance() } }, modifier = Modifier.padding(horizontal = 20.dp).testTag("reset-appearance")) {
        Text(stringResource(R.string.reset_appearance))
    }
}

internal fun AppSettings.resetAppearance() = copy(
    theme = AppTheme.SYSTEM, themePalette = ThemePalette.CLASSIC, darkThemePalette = null,
    lightCustomTheme = CustomThemeColors(), darkCustomTheme = CustomThemeColors.Dark,
    interfaceFont = InterfaceFont.SYSTEM, interfaceTextScale = 1f, interfaceCorners = InterfaceCorners.STANDARD, pageTransition = false,
)

@Composable
private fun AppearanceLabel(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

@Composable
private fun CustomThemeSettings(dark: Boolean, colors: CustomThemeColors, onChange: ((CustomThemeColors) -> CustomThemeColors) -> Unit) {
    AppearanceLabel(if (dark) R.string.custom_dark_theme else R.string.custom_light_theme)
    InfoText(stringResource(R.string.custom_contrast_hint))
    Column(Modifier.padding(horizontal = 20.dp)) {
        CustomColorSetting(R.string.custom_accent, colors.accent) { value -> onChange { it.copy(accent = value) } }
        CustomColorSetting(R.string.custom_background, colors.background) { value -> onChange { it.copy(background = value) } }
        CustomColorSetting(R.string.custom_surface, colors.surface) { value -> onChange { it.copy(surface = value) } }
        CustomColorSetting(R.string.custom_text, colors.text) { value -> onChange { it.copy(text = value) } }
        CustomColorSetting(R.string.custom_secondary, colors.secondary) { value -> onChange { it.copy(secondary = value) } }
        CustomColorSetting(R.string.custom_outline, colors.outline) { value -> onChange { it.copy(outline = value) } }
        CustomColorSetting(R.string.custom_error, colors.error) { value -> onChange { it.copy(error = value) } }
    }
}

internal fun themeColorHex(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

internal fun parseThemeColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it.digitToIntOrNull(16) == null }) return null
    return hex.toInt(16) or 0xFF000000.toInt()
}

@Composable
private fun CustomColorSetting(labelRes: Int, color: Int, onChange: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(labelRes)
    TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("custom-color-$labelRes")) {
        Surface(color = Color(color), shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.size(28.dp)) {}
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f))
        Text(themeColorHex(color), style = MaterialTheme.typography.labelLarge)
    }
    if (open) ThemeColorDialog(label, color, { open = false }) { onChange(it); open = false }
}

@Composable
private fun ThemeColorDialog(label: String, initial: Int, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    val initialHsv = remember(initial) { FloatArray(3).also { AndroidColor.colorToHSV(initial, it) } }
    var hue by rememberSaveable { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(initialHsv[2]) }
    var hex by rememberSaveable { mutableStateOf(themeColorHex(initial)) }
    val parsed = parseThemeColor(hex)
    fun setChannel(index: Int, value: Float) {
        when (index) { 0 -> hue = value; 1 -> saturation = value; 2 -> brightness = value }
        hex = themeColorHex(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Surface(color = Color(parsed ?: initial), shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth().height(56.dp)) {}
                OutlinedTextField(
                    value = hex, singleLine = true,
                    onValueChange = { value ->
                        hex = value
                        parseThemeColor(value)?.let { color ->
                            val hsv = FloatArray(3).also { AndroidColor.colorToHSV(color, it) }
                            hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                        }
                    },
                    label = { Text(stringResource(R.string.custom_hex)) }, isError = parsed == null,
                    supportingText = { Text(stringResource(R.string.custom_hex_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("theme-color-hex"),
                )
                listOf(R.string.custom_hue, R.string.custom_saturation, R.string.custom_brightness).forEachIndexed { index, resource ->
                    val sliderLabel = stringResource(resource)
                    val value = when (index) { 0 -> hue / 360f; 1 -> saturation; else -> brightness }
                    Text(sliderLabel, style = MaterialTheme.typography.labelLarge)
                    Slider(value = value, onValueChange = { setChannel(index, if (index == 0) it * 360f else it) },
                        modifier = Modifier.semantics { contentDescription = sliderLabel })
                }
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onApply) }, enabled = parsed != null, modifier = Modifier.testTag("theme-color-apply")) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
