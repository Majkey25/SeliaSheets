package com.majkeylab.seliadocs.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.R

@Composable
internal fun ElementContextBar(
    onEdit: (() -> Unit)? = null,
    onRecognizeText: (() -> Unit)? = null,
    onDuplicate: () -> Unit,
    onBringForward: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("element-context-bar")
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.element_selected),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            onEdit?.let { edit ->
                TextButton(onClick = edit) {
                    Text(stringResource(R.string.edit_text))
                }
            }
            onRecognizeText?.let { recognize ->
                TextButton(onClick = recognize) {
                    Text(stringResource(R.string.recognize_image_text))
                }
            }
            TextButton(onClick = onDuplicate) {
                Text(stringResource(R.string.duplicate_element))
            }
            TextButton(onClick = onBringForward) {
                Text(stringResource(R.string.bring_forward))
            }
            TextButton(onClick = onDelete) {
                Text(
                    stringResource(R.string.delete_element),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun InkContextBar(
    count: Int,
    onDuplicate: () -> Unit,
    onColorChange: (Int) -> Unit,
    onTransform: (Float, Float) -> Unit,
    onDelete: () -> Unit,
) {
    var colorsOpen by rememberSaveable { mutableStateOf(false) }
    var transformOpen by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("ink-context-bar")
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.selected_strokes, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            TextButton(onClick = onDuplicate) {
                Text(stringResource(R.string.duplicate_element))
            }
            Box {
                TextButton(onClick = { colorsOpen = true }) {
                    Text(stringResource(R.string.color))
                }
                DropdownMenu(expanded = colorsOpen, onDismissRequest = { colorsOpen = false }) {
                    PEN_COLOR_OPTIONS.forEach { (_, label, colorArgb) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            leadingIcon = {
                                Surface(
                                    color = Color(colorArgb),
                                    shape = CircleShape,
                                    modifier = Modifier.size(20.dp),
                                ) {}
                            },
                            onClick = {
                                colorsOpen = false
                                onColorChange(colorArgb)
                            },
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { transformOpen = true }) {
                    Text(stringResource(R.string.transform))
                }
                DropdownMenu(expanded = transformOpen, onDismissRequest = { transformOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scale_up)) },
                        onClick = {
                            transformOpen = false
                            onTransform(1.1f, 0f)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.scale_down)) },
                        onClick = {
                            transformOpen = false
                            onTransform(0.9f, 0f)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rotate_clockwise)) },
                        onClick = {
                            transformOpen = false
                            onTransform(1f, 15f)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rotate_counterclockwise)) },
                        onClick = {
                            transformOpen = false
                            onTransform(1f, -15f)
                        },
                    )
                }
            }
            TextButton(onClick = onDelete) {
                Text(
                    stringResource(R.string.delete_selection),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
