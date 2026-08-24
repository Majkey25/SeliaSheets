package cz.majkey.perko.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.majkey.perko.R
import cz.majkey.perko.data.PageOrientation
import cz.majkey.perko.data.PaperTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.back)) }
                    Text(
                        stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                SettingsGroup(stringResource(R.string.settings_drawing), initiallyExpanded = true) {
                    ChoiceSetting(
                        label = stringResource(R.string.default_tool),
                        values = DefaultTool.entries,
                        selected = settings.defaultTool,
                        valueLabel = { defaultToolLabel(it) },
                        onSelect = { onUpdate(settings.copy(defaultTool = it)) },
                    )
                    SliderSetting(
                        stringResource(R.string.pen_width),
                        settings.penWidth,
                        2f..12f,
                    ) { onUpdate(settings.copy(penWidth = it)) }
                    SliderSetting(
                        stringResource(R.string.highlighter_width),
                        settings.highlighterWidth,
                        8f..40f,
                    ) { onUpdate(settings.copy(highlighterWidth = it)) }
                    SwitchSetting(
                        stringResource(R.string.default_finger_drawing),
                        settings.fingerDrawing,
                    ) { onUpdate(settings.copy(fingerDrawing = it)) }
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_paper)) {
                    ChoiceSetting(
                        label = stringResource(R.string.default_paper),
                        values = PaperTemplate.entries,
                        selected = settings.defaultPaper,
                        valueLabel = { paperLabel(it) },
                        onSelect = { onUpdate(settings.copy(defaultPaper = it)) },
                    )
                    ChoiceSetting(
                        label = stringResource(R.string.default_orientation),
                        values = PageOrientation.entries,
                        selected = settings.defaultOrientation,
                        valueLabel = {
                            stringResource(
                                if (it == PageOrientation.PORTRAIT) R.string.portrait else R.string.landscape,
                            )
                        },
                        onSelect = { onUpdate(settings.copy(defaultOrientation = it)) },
                    )
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_recognition)) {
                    InfoText(stringResource(R.string.recognition_details))
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_export)) {
                    InfoText(stringResource(R.string.export_details))
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_interface)) {
                    ChoiceSetting(
                        label = stringResource(R.string.theme),
                        values = AppTheme.entries,
                        selected = settings.theme,
                        valueLabel = { themeLabel(it) },
                        onSelect = { onUpdate(settings.copy(theme = it)) },
                    )
                    SwitchSetting(
                        stringResource(R.string.page_transition),
                        settings.pageTransition,
                    ) { onUpdate(settings.copy(pageTransition = it)) }
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_data)) {
                    StorageUsage()
                    InfoText(stringResource(R.string.autosave_details))
                }
            }
            item {
                SettingsGroup(stringResource(R.string.app_details)) {
                    AppDetailsSection()
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column {
        Row(
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (expanded) R.string.collapse_group else R.string.expand_group),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (expanded) content()
        HorizontalDivider()
    }
}

@Composable
private fun <T> ChoiceSetting(
    label: String,
    values: List<T>,
    selected: T,
    valueLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(valueLabel(value)) },
                )
            }
        }
    }
}

@Composable
private fun SliderSetting(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.setting_value, label, sliderValue.toInt()))
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onChange(sliderValue) },
            valueRange = range,
        )
    }
}

@Composable
private fun SwitchSetting(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoText(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun StorageUsage() {
    val context = LocalContext.current
    val bytes by
        produceState(0L, context) {
            value =
                withContext(Dispatchers.IO) {
                    context.filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } +
                        listOf("perko.db", "perko.db-wal", "perko.db-shm")
                            .sumOf { context.getDatabasePath(it).takeIf(java.io.File::isFile)?.length() ?: 0L }
                }
        }
    Text(
        stringResource(R.string.storage_usage, readableBytes(bytes)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

private fun readableBytes(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }

@Composable
private fun defaultToolLabel(value: DefaultTool): String =
    stringResource(
        when (value) {
            DefaultTool.PEN -> R.string.tool_pen
            DefaultTool.PENCIL -> R.string.tool_pencil
            DefaultTool.HIGHLIGHTER -> R.string.tool_highlighter
        },
    )

@Composable
private fun paperLabel(value: PaperTemplate): String =
    stringResource(
        when (value) {
            PaperTemplate.BLANK -> R.string.paper_blank
            PaperTemplate.RULED -> R.string.paper_ruled
            PaperTemplate.GRID -> R.string.paper_grid
            PaperTemplate.DOT -> R.string.paper_dot
        },
    )

@Composable
private fun themeLabel(value: AppTheme): String =
    stringResource(
        when (value) {
            AppTheme.SYSTEM -> R.string.theme_system
            AppTheme.LIGHT -> R.string.theme_light
            AppTheme.DARK -> R.string.theme_dark
        },
    )
