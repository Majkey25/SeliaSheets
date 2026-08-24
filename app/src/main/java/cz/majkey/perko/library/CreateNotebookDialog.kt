package cz.majkey.perko.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.majkey.perko.R
import cz.majkey.perko.data.CoverColor
import cz.majkey.perko.data.CoverPattern
import cz.majkey.perko.data.CreateNotebookRequest
import cz.majkey.perko.data.PageOrientation
import cz.majkey.perko.data.PaperTemplate
import cz.majkey.perko.settings.AppSettings
import cz.majkey.perko.ui.CoverPatternPreview
import cz.majkey.perko.ui.NotebookPreview
import cz.majkey.perko.ui.OrientationPreview
import cz.majkey.perko.ui.PaperPreview
import cz.majkey.perko.ui.TemplatePreview
import cz.majkey.perko.ui.coverColorValue
import cz.majkey.perko.ui.orientationLabel
import cz.majkey.perko.ui.paperLabel
import cz.majkey.perko.ui.templateLabel

@Composable
internal fun CreateNotebookDialog(
    defaults: AppSettings,
    onDismiss: () -> Unit,
    onCreate: (CreateNotebookRequest) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var coverColor by
        remember(defaults.defaultCoverColor) { mutableStateOf(defaults.defaultCoverColor) }
    var coverPattern by
        remember(defaults.defaultCoverPattern) { mutableStateOf(defaults.defaultCoverPattern) }
    var paper by remember(defaults.defaultPaper) { mutableStateOf(defaults.defaultPaper) }
    var orientation by remember(defaults.defaultOrientation) { mutableStateOf(defaults.defaultOrientation) }
    var fingerDrawing by remember(defaults.fingerDrawing) { mutableStateOf(defaults.fingerDrawing) }
    val selectedTemplate =
        NotebookTemplate.entries.firstOrNull { template ->
            template.matches(coverColor, coverPattern, paper, orientation)
        }
    val fallbackTitle = stringResource(R.string.untitled_notebook)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f).widthIn(max = 920.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.new_notebook),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.choose_notebook_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
                HorizontalDivider()
                BoxWithConstraints(Modifier.weight(1f)) {
                    if (maxWidth >= 720.dp) {
                        Row(Modifier.fillMaxSize()) {
                            PreviewPane(
                                title,
                                coverColor,
                                coverPattern,
                                paper,
                                orientation,
                                selectedTemplate,
                                false,
                                Modifier.fillMaxHeight().weight(0.38f),
                            )
                            VerticalDivider()
                            ConfigurationPane(
                                title = title,
                                onTitleChange = { title = it.take(120) },
                                coverColor = coverColor,
                                coverPattern = coverPattern,
                                paper = paper,
                                orientation = orientation,
                                fingerDrawing = fingerDrawing,
                                selectedTemplate = selectedTemplate,
                                onTemplate = { template ->
                                    coverColor = template.coverColor
                                    coverPattern = template.coverPattern
                                    paper = template.paper
                                    orientation = template.orientation
                                },
                                onCoverColor = { coverColor = it },
                                onCoverPattern = { coverPattern = it },
                                onPaper = { paper = it },
                                onOrientation = { orientation = it },
                                onFingerDrawing = { fingerDrawing = it },
                                modifier = Modifier.fillMaxHeight().weight(0.62f),
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            PreviewPane(
                                title,
                                coverColor,
                                coverPattern,
                                paper,
                                orientation,
                                selectedTemplate,
                                true,
                                Modifier.fillMaxWidth().height(240.dp),
                            )
                            HorizontalDivider()
                            ConfigurationPane(
                                title = title,
                                onTitleChange = { title = it.take(120) },
                                coverColor = coverColor,
                                coverPattern = coverPattern,
                                paper = paper,
                                orientation = orientation,
                                fingerDrawing = fingerDrawing,
                                selectedTemplate = selectedTemplate,
                                onTemplate = { template ->
                                    coverColor = template.coverColor
                                    coverPattern = template.coverPattern
                                    paper = template.paper
                                    orientation = template.orientation
                                },
                                onCoverColor = { coverColor = it },
                                onCoverPattern = { coverPattern = it },
                                onPaper = { paper = it },
                                onOrientation = { orientation = it },
                                onFingerDrawing = { fingerDrawing = it },
                                modifier = Modifier.fillMaxWidth(),
                                scroll = false,
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selectedTemplate?.let { templateLabel(it) }
                            ?: stringResource(R.string.custom_notebook),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onCreate(
                                CreateNotebookRequest(
                                    title = normalizeTitle(title, fallbackTitle),
                                    coverColor = coverColor,
                                    coverPattern = coverPattern,
                                    paper = paper,
                                    orientation = orientation,
                                    fingerDrawing = fingerDrawing,
                                ),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.create_notebook))
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewPane(
    title: String,
    coverColor: CoverColor,
    coverPattern: CoverPattern,
    paper: PaperTemplate,
    orientation: PageOrientation,
    selectedTemplate: NotebookTemplate?,
    compact: Boolean,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(if (compact) 12.dp else 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.live_preview),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Start),
        )
        Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
        NotebookPreview(
            coverColor = coverColor,
            coverPattern = coverPattern,
            paper = paper,
            orientation = orientation,
            title = title,
            compact = compact,
            modifier = Modifier.fillMaxWidth().height(if (compact) 120.dp else 260.dp),
        )
        Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
        Text(
            selectedTemplate?.let { templateLabel(it) } ?: stringResource(R.string.custom_notebook),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "${paperLabel(paper)} · ${orientationLabel(orientation)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfigurationPane(
    title: String,
    onTitleChange: (String) -> Unit,
    coverColor: CoverColor,
    coverPattern: CoverPattern,
    paper: PaperTemplate,
    orientation: PageOrientation,
    fingerDrawing: Boolean,
    selectedTemplate: NotebookTemplate?,
    onTemplate: (NotebookTemplate) -> Unit,
    onCoverColor: (CoverColor) -> Unit,
    onCoverPattern: (CoverPattern) -> Unit,
    onPaper: (PaperTemplate) -> Unit,
    onOrientation: (PageOrientation) -> Unit,
    onFingerDrawing: (Boolean) -> Unit,
    modifier: Modifier,
    scroll: Boolean = true,
) {
    val nameDescription = stringResource(R.string.notebook_name)
    Column(
        modifier
            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text(stringResource(R.string.notebook_name)) },
            placeholder = { Text(stringResource(R.string.untitled_notebook)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = nameDescription },
        )
        SectionTitle(stringResource(R.string.start_with_template))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().height(184.dp),
        ) {
            items(NotebookTemplate.entries) { template ->
                TemplatePreview(
                    template = template,
                    selected = selectedTemplate == template,
                    onClick = { onTemplate(template) },
                    modifier = Modifier.width(166.dp).fillMaxHeight(),
                )
            }
        }
        SectionTitle(stringResource(R.string.cover_color))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CoverColor.entries.forEach { color ->
                CoverColorChoice(color, color == coverColor) { onCoverColor(color) }
            }
        }
        SectionTitle(stringResource(R.string.cover_style))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            CoverPattern.entries.forEach { pattern ->
                CoverPatternPreview(
                    pattern = pattern,
                    coverColor = coverColor,
                    selected = pattern == coverPattern,
                    onClick = { onCoverPattern(pattern) },
                    modifier = Modifier.width(112.dp),
                )
            }
        }
        SectionTitle(stringResource(R.string.paper))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            PaperTemplate.entries.forEach { value ->
                PaperPreview(
                    paper = value,
                    selected = value == paper,
                    onClick = { onPaper(value) },
                    modifier = Modifier.width(112.dp),
                )
            }
        }
        SectionTitle(stringResource(R.string.orientation))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PageOrientation.entries.forEach { value ->
                OrientationPreview(
                    orientation = value,
                    paper = paper,
                    selected = value == orientation,
                    onClick = { onOrientation(value) },
                    modifier = Modifier.width(136.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.finger_drawing))
                Text(
                    stringResource(R.string.finger_drawing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = fingerDrawing, onCheckedChange = onFingerDrawing)
        }
    }
}

@Composable
private fun CoverColorChoice(color: CoverColor, selected: Boolean, onClick: () -> Unit) {
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
            Modifier.width(104.dp)
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .semantics { contentDescription = label },
    ) {
        Column(
            Modifier.padding(9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(color = coverColorValue(color), shape = CircleShape, modifier = Modifier.size(42.dp)) {}
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectionTitle(value: String) {
    Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

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
