package com.majkeylab.seliadocs.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.majkeylab.seliadocs.R

@Composable
internal fun BackupRoute(onClose: () -> Unit) {
    val viewModel: BackupViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackupScreen(
        state = state,
        onClose = onClose,
        onExport = viewModel::exportLibrary,
        onRestore = viewModel::restore,
    )
}

@Composable
private fun BackupScreen(
    state: BackupUiState,
    onClose: () -> Unit,
    onExport: (android.net.Uri) -> Unit,
    onRestore: (android.net.Uri, RestoreMode) -> Unit,
) {
    var replaceConfirmation by rememberSaveable { mutableStateOf(false) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
            uri?.let(onExport)
        }
    val mergeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onRestore(it, RestoreMode.MERGE) }
        }
    val replaceLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { onRestore(it, RestoreMode.REPLACE) }
        }

    if (replaceConfirmation) {
        AlertDialog(
            onDismissRequest = { replaceConfirmation = false },
            title = { Text(stringResource(R.string.replace_library_title)) },
            text = { Text(stringResource(R.string.replace_library_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        replaceConfirmation = false
                        replaceLauncher.launch(BACKUP_OPEN_MIME_TYPES)
                    },
                ) {
                    Text(
                        stringResource(R.string.replace_library),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { replaceConfirmation = false }) {
                    Text(stringResource(R.string.keep_existing_library))
                }
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose, enabled = !state.running) {
                        Text(stringResource(R.string.back))
                    }
                    Text(
                        stringResource(R.string.backup_restore),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .fillMaxHeight(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.backup_intro),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    BackupSection(
                        title = stringResource(R.string.backup_entire_library),
                        detail =
                            stringResource(
                                R.string.backup_library_count,
                                state.notebooks,
                                state.pages,
                            ),
                    ) {
                        Text(
                            stringResource(R.string.backup_export_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { exportLauncher.launch(backupFileName()) },
                            enabled = !state.running,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.export_library))
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            stringResource(R.string.restore_backup),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.restore_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { mergeLauncher.launch(BACKUP_OPEN_MIME_TYPES) },
                            enabled = !state.running,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.merge_backup))
                        }
                        Text(
                            stringResource(R.string.merge_backup_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider()
                        TextButton(
                            onClick = { replaceConfirmation = true },
                            enabled = !state.running,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.replace_library),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            stringResource(R.string.replace_library_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.status?.let { status ->
                    item {
                        Surface(
                            color =
                                if (state.failed) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                            shape = RoundedCornerShape(10.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(status, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupSection(
    title: String,
    detail: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

private val BACKUP_OPEN_MIME_TYPES =
    arrayOf(BACKUP_MIME_TYPE, "application/octet-stream", "application/x-zip-compressed")
