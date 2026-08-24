package com.majkeylab.seliadocs.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.library.NotebookTemplate
import com.majkeylab.seliadocs.ui.CoverPatternPreview
import com.majkeylab.seliadocs.ui.NotebookPreview
import com.majkeylab.seliadocs.ui.OrientationPreview
import com.majkeylab.seliadocs.ui.PaperPreview
import com.majkeylab.seliadocs.ui.TemplatePreview
import com.majkeylab.seliadocs.ui.coverColorValue
import com.majkeylab.seliadocs.ui.paperLabel
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
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("settings-list")) {
            item {
                Text(
                    stringResource(R.string.settings_visual_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
            item {
                SettingsGroup(
                    title = stringResource(R.string.notebook_defaults),
                    summary = stringResource(R.string.notebook_defaults_summary),
                    initiallyExpanded = true,
                ) {
                    NotebookDefaults(settings, onUpdate)
                }
            }
            item {
                SettingsGroup(
                    title = stringResource(R.string.settings_drawing),
                    summary = stringResource(R.string.drawing_summary),
                ) {
                    ChoiceSetting(
                        label = stringResource(R.string.default_tool),
                        values = DefaultTool.entries,
                        selected = settings.defaultTool,
                        valueLabel = { defaultToolLabel(it) },
                        onSelect = { onUpdate(settings.copy(defaultTool = it)) },
                    )
                    StrokeSliderSetting(
                        label = stringResource(R.string.pen_width),
                        sampleLabel = stringResource(R.string.pen_sample),
                        value = settings.penWidth,
                        range = 2f..12f,
                        color = Color(0xFF202124),
                    ) { onUpdate(settings.copy(penWidth = it)) }
                    StrokeSliderSetting(
                        label = stringResource(R.string.highlighter_width),
                        sampleLabel = stringResource(R.string.highlighter_sample),
                        value = settings.highlighterWidth,
                        range = 8f..40f,
                        color = Color(0x66FFD54F),
                    ) { onUpdate(settings.copy(highlighterWidth = it)) }
                }
            }
            item {
                SettingsGroup(
                    title = stringResource(R.string.interface_export),
                    summary = stringResource(R.string.interface_export_summary),
                ) {
                    Text(
                        stringResource(R.string.theme),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AppTheme.entries.forEach { theme ->
                            ThemePreviewChoice(
                                theme = theme,
                                selected = theme == settings.theme,
                                onClick = { onUpdate(settings.copy(theme = theme)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    SwitchSetting(
                        stringResource(R.string.page_transition),
                        settings.pageTransition,
                    ) { onUpdate(settings.copy(pageTransition = it)) }
                    InfoText(stringResource(R.string.export_details))
                }
            }
            item {
                SettingsGroup(
                    title = stringResource(R.string.app_privacy),
                    summary = stringResource(R.string.app_privacy_summary),
                ) {
                    InfoText(stringResource(R.string.recognition_details))
                    StorageUsage()
                    InfoText(stringResource(R.string.autosave_details))
                    Text(
                        stringResource(R.string.app_details),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                    AppDetailsSection()
                }
            }
        }
    }
}

@Composable
private fun NotebookDefaults(settings: AppSettings, onUpdate: (AppSettings) -> Unit) {
    val selectedTemplate =
        NotebookTemplate.entries.firstOrNull { template ->
            template.matches(
                settings.defaultCoverColor,
                settings.defaultCoverPattern,
                settings.defaultPaper,
                settings.defaultOrientation,
            )
        }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            NotebookPreview(
                coverColor = settings.defaultCoverColor,
                coverPattern = settings.defaultCoverPattern,
                paper = settings.defaultPaper,
                orientation = settings.defaultOrientation,
                title = stringResource(R.string.untitled_notebook),
                compact = false,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().height(240.dp),
            )
        }
        Text(
            stringResource(R.string.start_with_template),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(184.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        ) {
            items(NotebookTemplate.entries) { template ->
                TemplatePreview(
                    template = template,
                    selected = selectedTemplate == template,
                    onClick = {
                        onUpdate(
                            settings.copy(
                                defaultCoverColor = template.coverColor,
                                defaultCoverPattern = template.coverPattern,
                                defaultPaper = template.paper,
                                defaultOrientation = template.orientation,
                            ),
                        )
                    },
                    modifier = Modifier.width(166.dp),
                )
            }
        }
        Text(
            stringResource(R.string.cover_color),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        FlowRow(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CoverColor.entries.forEach { color ->
                CoverColorSetting(
                    color = color,
                    selected = color == settings.defaultCoverColor,
                    onClick = { onUpdate(settings.copy(defaultCoverColor = color)) },
                )
            }
        }
        Text(
            stringResource(R.string.cover_style),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        FlowRow(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            CoverPattern.entries.forEach { pattern ->
                CoverPatternPreview(
                    pattern = pattern,
                    coverColor = settings.defaultCoverColor,
                    selected = pattern == settings.defaultCoverPattern,
                    onClick = { onUpdate(settings.copy(defaultCoverPattern = pattern)) },
                    modifier = Modifier.width(112.dp),
                )
            }
        }
        Text(
            stringResource(R.string.default_paper),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        FlowRow(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            PaperTemplate.entries.forEach { paper ->
                PaperPreview(
                    paper = paper,
                    selected = paper == settings.defaultPaper,
                    onClick = { onUpdate(settings.copy(defaultPaper = paper)) },
                    modifier = Modifier.width(112.dp),
                )
            }
        }
        Text(
            stringResource(R.string.default_orientation),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PageOrientation.entries.forEach { orientation ->
                OrientationPreview(
                    orientation = orientation,
                    paper = settings.defaultPaper,
                    selected = orientation == settings.defaultOrientation,
                    onClick = { onUpdate(settings.copy(defaultOrientation = orientation)) },
                    modifier = Modifier.width(136.dp),
                )
            }
        }
        SwitchSetting(
            stringResource(R.string.default_finger_drawing),
            settings.fingerDrawing,
        ) { onUpdate(settings.copy(fingerDrawing = it)) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column {
        Row(
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
private fun StrokeSliderSetting(
    label: String,
    sampleLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    color: Color,
    onChange: (Float) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.setting_value, label, sliderValue.toInt()))
        Text(sampleLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            color = Color(0xFFFFFEFA),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().height(54.dp).semantics { contentDescription = sampleLabel },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(
                    color = color,
                    start = Offset(24.dp.toPx(), size.height / 2f),
                    end = Offset(size.width - 24.dp.toPx(), size.height / 2f),
                    strokeWidth = sliderValue.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onChange(sliderValue) },
            valueRange = range,
        )
    }
}

@Composable
private fun ThemePreviewChoice(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val dark = theme == AppTheme.DARK
    val background = if (dark) Color(0xFF1B1B1A) else Color(0xFFECEAE5)
    val paper = if (dark) Color(0xFF252421) else Color(0xFFFFFEFA)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f) else Color.Transparent,
        shape = RoundedCornerShape(9.dp),
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier = modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    ) {
        Column(
            Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(72.dp)) {
                Surface(
                    color = background,
                    shape = RoundedCornerShape(5.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {}
                Surface(
                    color = paper,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.58f).height(54.dp),
                ) {}
                if (theme == AppTheme.SYSTEM) {
                    Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f).height(72.dp)) {
                        Surface(
                            color = Color(0xFF252421),
                            shape = RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {}
                    }
                }
            }
            Text(themeLabel(theme), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CoverColorSetting(color: CoverColor, selected: Boolean, onClick: () -> Unit) {
    val label = coverColorLabel(color)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent,
        shape = RoundedCornerShape(9.dp),
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier =
            Modifier.width(104.dp).selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .semantics { contentDescription = label },
    ) {
        Column(
            Modifier.padding(9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                color = coverColorValue(color),
                shape = CircleShape,
                modifier = Modifier.size(42.dp),
            ) {}
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
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
                        listOf("seliadocs.db", "seliadocs.db-wal", "seliadocs.db-shm")
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
private fun themeLabel(value: AppTheme): String =
    stringResource(
        when (value) {
            AppTheme.SYSTEM -> R.string.theme_system
            AppTheme.LIGHT -> R.string.theme_light
            AppTheme.DARK -> R.string.theme_dark
        },
    )

@Composable
private fun coverColorLabel(value: CoverColor): String =
    stringResource(
        when (value) {
            CoverColor.PERIWINKLE -> R.string.color_periwinkle
            CoverColor.GRAPHITE -> R.string.color_graphite
            CoverColor.SAGE -> R.string.color_sage
            CoverColor.SALMON -> R.string.color_salmon
            CoverColor.SAND -> R.string.color_sand
        },
    )
