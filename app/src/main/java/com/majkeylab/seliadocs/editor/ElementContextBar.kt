package com.majkeylab.seliadocs.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.R

@Composable
internal fun ElementContextBar(
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
