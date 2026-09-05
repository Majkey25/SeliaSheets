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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.library.NotebookTemplate
import com.majkeylab.seliadocs.recognition.RecognitionLanguage
import com.majkeylab.seliadocs.recognition.RecognitionModelStatus
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
@OptIn(ExperimentalMaterial3Api::class)
internal fun SettingsScreen(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBackup: () -> Unit,
    onClose: () -> Unit,
    recognitionModelStatus: RecognitionModelStatus = RecognitionModelStatus.NotDownloaded,
    onDownloadRecognitionModel: (RecognitionLanguage) -> Unit = {},
    onDeleteRecognitionModel: (RecognitionLanguage) -> Unit = {},
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                modifier = Modifier.testTag("settings-top-bar"),
                title = {
                    Text(
                        stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("settings-top-bar-title"),
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("settings-list")) {
            item {
                SettingsGroup(
                    title = stringResource(R.string.notebook_defaults),
                    summary = stringResource(R.string.notebook_defaults_summary),
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
                        onSelect = { tool -> onUpdate { it.copy(defaultTool = tool) } },
                    )
                    StrokeSliderSetting(
                        label = stringResource(R.string.pen_width),
                        sampleLabel = stringResource(R.string.pen_sample),
                        value = settings.penWidth,
                        range = PEN_WIDTH_RANGE,
                        color = Color(settings.penColorArgb),
                    ) { width -> onUpdate { it.copy(penWidth = width) } }
                    StrokeSliderSetting(
                        label = stringResource(R.string.highlighter_width),
                        sampleLabel = stringResource(R.string.highlighter_sample),
                        value = settings.highlighterWidth,
                        range = HIGHLIGHTER_WIDTH_RANGE,
                        color = Color(settings.highlighterColorArgb),
                    ) { width -> onUpdate { it.copy(highlighterWidth = width) } }
                    SwitchSetting(
                        stringResource(R.string.shape_assist),
                        settings.shapeAssist,
                    ) { enabled -> onUpdate { it.copy(shapeAssist = enabled) } }
                    InfoText(stringResource(R.string.shape_assist_hint))
                    SwitchSetting(
                        label = stringResource(R.string.image_ocr),
                        checked = settings.imageOcr,
                        tag = "settings-image-ocr",
                    ) { enabled -> onUpdate { it.copy(imageOcr = enabled) } }
                    InfoText(stringResource(R.string.image_ocr_hint))
                    SwitchSetting(
                        label = stringResource(R.string.handwriting_recognition),
                        checked = settings.handwritingRecognition,
                        tag = "settings-handwriting-recognition",
                    ) { enabled -> onUpdate { it.copy(handwritingRecognition = enabled) } }
                    RecognitionLanguageSetting(
                        selected = settings.recognitionLanguage,
                        onSelect = { language -> onUpdate { it.copy(recognitionLanguage = language) } },
                    )
                    RecognitionModelSetting(
                        language = settings.recognitionLanguage,
                        status = recognitionModelStatus,
                        onDownload = onDownloadRecognitionModel,
                        onDelete = onDeleteRecognitionModel,
                    )
                    InfoText(stringResource(R.string.recognition_disclosure))
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
                                onClick = { onUpdate { it.copy(theme = theme) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    SwitchSetting(
                        stringResource(R.string.page_transition),
                        settings.pageTransition,
                    ) { enabled -> onUpdate { it.copy(pageTransition = enabled) } }
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
                    NavigationSetting(
                        title = stringResource(R.string.backup_restore),
                        summary = stringResource(R.string.backup_restore_summary),
                        onClick = onBackup,
                    )
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
private fun NavigationSetting(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("›", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun NotebookDefaults(settings: AppSettings, onUpdate: ((AppSettings) -> AppSettings) -> Unit) {
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
                        onUpdate {
                            it.copy(
                                defaultCoverColor = template.coverColor,
                                defaultCoverPattern = template.coverPattern,
                                defaultPaper = template.paper,
                                defaultOrientation = template.orientation,
                            )
                        }
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
                    onClick = { onUpdate { it.copy(defaultCoverColor = color) } },
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
                    onClick = { onUpdate { it.copy(defaultCoverPattern = pattern) } },
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
                    onClick = { onUpdate { it.copy(defaultPaper = paper) } },
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
                    onClick = { onUpdate { it.copy(defaultOrientation = orientation) } },
                    modifier = Modifier.width(136.dp),
                )
            }
        }
        SwitchSetting(
            stringResource(R.string.default_finger_drawing),
            settings.fingerDrawing,
        ) { enabled -> onUpdate { it.copy(fingerDrawing = enabled) } }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    summary: String,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Column {
        Row(
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
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
    val valueDescription = stringResource(R.string.brush_width_value, sliderValue.toInt())
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
            modifier = Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = sampleLabel },
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
            modifier =
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = valueDescription
                },
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
internal fun SwitchSetting(
    label: String,
    checked: Boolean,
    tag: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .then(if (tag == null) Modifier else Modifier.testTag(tag))
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onChange,
                ).semantics(mergeDescendants = true) {}
                .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RecognitionLanguageSetting(
    selected: RecognitionLanguage,
    onSelect: (RecognitionLanguage) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.recognition_language), style = MaterialTheme.typography.bodyLarge)
        RecognitionLanguage.entries.forEach { language ->
            val label = stringResource(if (language == RecognitionLanguage.CZECH) R.string.czech else R.string.english)
            Row(
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(
                            selected = language == selected,
                            onClick = { onSelect(language) },
                            role = Role.RadioButton,
                        ).testTag("settings-recognition-${language.name.lowercase()}")
                        .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = language == selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
    }
}

@Composable
private fun RecognitionModelSetting(
    language: RecognitionLanguage,
    status: RecognitionModelStatus,
    onDownload: (RecognitionLanguage) -> Unit,
    onDelete: (RecognitionLanguage) -> Unit,
) {
    when (status) {
        RecognitionModelStatus.NotDownloaded ->
            TextButton(
                onClick = { onDownload(language) },
                modifier = Modifier.testTag("settings-recognition-download"),
            ) { Text(stringResource(R.string.download_recognition_model)) }
        RecognitionModelStatus.Downloading ->
            Text(
                stringResource(R.string.downloading_recognition_model),
                modifier = Modifier.testTag("settings-recognition-downloading").semantics { disabled() },
            )
        RecognitionModelStatus.Deleting ->
            Text(
                stringResource(R.string.deleting_recognition_model),
                modifier = Modifier.testTag("settings-recognition-deleting").semantics { disabled() },
            )
        RecognitionModelStatus.Ready -> {
            Text(stringResource(R.string.recognition_model_ready))
            TextButton(
                onClick = { onDelete(language) },
                modifier = Modifier.testTag("settings-recognition-delete"),
            ) { Text(stringResource(R.string.delete_recognition_model)) }
        }
        is RecognitionModelStatus.Failed -> {
            Text(status.message)
            TextButton(onClick = { onDownload(language) }) { Text(stringResource(R.string.retry_download)) }
        }
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
