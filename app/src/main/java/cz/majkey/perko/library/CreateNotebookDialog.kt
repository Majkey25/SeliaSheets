package cz.majkey.perko.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cz.majkey.perko.R
import cz.majkey.perko.data.CoverColor
import cz.majkey.perko.data.CoverPattern
import cz.majkey.perko.data.CreateNotebookRequest
import cz.majkey.perko.data.PageOrientation
import cz.majkey.perko.data.PaperTemplate
import cz.majkey.perko.settings.AppSettings

@Composable
internal fun CreateNotebookDialog(
    defaults: AppSettings,
    onDismiss: () -> Unit,
    onCreate: (CreateNotebookRequest) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var coverColor by remember { mutableStateOf(CoverColor.PERIWINKLE) }
    var coverPattern by remember { mutableStateOf(CoverPattern.SOLID) }
    var paper by remember(defaults.defaultPaper) { mutableStateOf(defaults.defaultPaper) }
    var orientation by
        remember(defaults.defaultOrientation) { mutableStateOf(defaults.defaultOrientation) }
    var fingerDrawing by remember(defaults.fingerDrawing) { mutableStateOf(defaults.fingerDrawing) }
    val fallbackTitle = stringResource(R.string.untitled_notebook)
    val nameDescription = stringResource(R.string.notebook_name)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_notebook)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.notebook_name)) },
                    singleLine = true,
                    modifier =
                        Modifier.fillMaxWidth().semantics {
                            contentDescription = nameDescription
                        },
                )
                OptionRow(
                    title = stringResource(R.string.cover_color),
                    values = CoverColor.entries,
                    selected = coverColor,
                    label = { coverColorLabel(it) },
                    onSelect = { coverColor = it },
                )
                OptionRow(
                    title = stringResource(R.string.cover_pattern),
                    values = CoverPattern.entries,
                    selected = coverPattern,
                    label = { coverPatternLabel(it) },
                    onSelect = { coverPattern = it },
                )
                OptionRow(
                    title = stringResource(R.string.paper),
                    values = PaperTemplate.entries,
                    selected = paper,
                    label = { paperLabel(it) },
                    onSelect = { paper = it },
                )
                OptionRow(
                    title = stringResource(R.string.orientation),
                    values = PageOrientation.entries,
                    selected = orientation,
                    label = { orientationLabel(it) },
                    onSelect = { orientation = it },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.finger_drawing), modifier = Modifier.weight(1f))
                    Switch(checked = fingerDrawing, onCheckedChange = { fingerDrawing = it })
                }
            }
        },
        confirmButton = {
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
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun <T> OptionRow(
    title: String,
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(label(value)) },
                    modifier = Modifier.padding(end = 2.dp),
                )
            }
        }
    }
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

@Composable
private fun coverPatternLabel(value: CoverPattern): String =
    stringResource(
        when (value) {
            CoverPattern.SOLID -> R.string.pattern_solid
            CoverPattern.BAND -> R.string.pattern_band
            CoverPattern.CORNERS -> R.string.pattern_corners
            CoverPattern.GRID -> R.string.pattern_grid
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
private fun orientationLabel(value: PageOrientation): String =
    stringResource(
        if (value == PageOrientation.PORTRAIT) R.string.portrait else R.string.landscape,
    )
