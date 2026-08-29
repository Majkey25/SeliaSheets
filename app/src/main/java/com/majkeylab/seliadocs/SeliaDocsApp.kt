package com.majkeylab.seliadocs

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.majkeylab.seliadocs.backup.BackupRoute
import com.majkeylab.seliadocs.backup.BackupViewModel
import com.majkeylab.seliadocs.backup.LibraryReplacementReporter
import com.majkeylab.seliadocs.editor.EditorRoute
import com.majkeylab.seliadocs.library.LibraryScreen
import com.majkeylab.seliadocs.library.LibraryViewModel
import com.majkeylab.seliadocs.recognition.RecognitionModelManager
import com.majkeylab.seliadocs.settings.AppSettings
import com.majkeylab.seliadocs.settings.AppTheme
import com.majkeylab.seliadocs.settings.SettingsRepository
import com.majkeylab.seliadocs.settings.SettingsScreen
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import kotlinx.coroutines.launch

@Composable
internal fun SeliaDocsApp(
    backupViewModel: BackupViewModel? = null,
    recognitionModelManager: RecognitionModelManager? = null,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val rootBackupViewModel: BackupViewModel = backupViewModel ?: viewModel()
    val rootRecognitionModelManager =
        remember(recognitionModelManager) { recognitionModelManager ?: RecognitionModelManager() }
    val backupState by rootBackupViewModel.state.collectAsStateWithLifecycle()
    val settingsRepository = remember(application) { SettingsRepository.create(application) }
    val settings by
        settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val recognitionModelStatus by rootRecognitionModelManager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var notebookId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var backupOpen by rememberSaveable { mutableStateOf(false) }
    var libraryGeneration by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(settings.recognitionLanguage) {
        rootRecognitionModelManager.select(settings.recognitionLanguage)
    }
    BackHandler(enabled = backupOpen || settingsOpen || notebookId != null) {
        if (backupOpen) {
            backupOpen = false
        } else if (settingsOpen) {
            settingsOpen = false
        } else {
            notebookId = null
        }
    }
    val activity = LocalActivity.current as? MainActivity
    val darkTheme = settings.theme.resolveDarkTheme(activity)
    SeliaDocsTheme(darkTheme = darkTheme) {
        LibraryReplacementReporter(
            replacementGeneration = backupState.replacementGeneration,
            claimReplacement = rootBackupViewModel::claimPendingReplacement,
            acknowledgeReplacement = rootBackupViewModel::acknowledgeReplacement,
            releaseReplacementClaim = rootBackupViewModel::releaseReplacementClaim,
            onLibraryReplaced = {
                notebookId = null
                backupOpen = false
                settingsOpen = false
                libraryGeneration++
            },
        )
        when {
            backupOpen ->
                BackupRoute(
                    viewModel = rootBackupViewModel,
                    onClose = { backupOpen = false },
                )
            settingsOpen ->
                SettingsScreen(
                    settings = settings,
                    onUpdate = { transform -> scope.launch { settingsRepository.update(transform) } },
                    onBackup = { backupOpen = true },
                    onClose = { settingsOpen = false },
                    recognitionModelStatus = recognitionModelStatus,
                    onDownloadRecognitionModel = { language ->
                        scope.launch { rootRecognitionModelManager.download(language) }
                    },
                    onDeleteRecognitionModel = { language ->
                        scope.launch { rootRecognitionModelManager.delete(language) }
                    },
                )
            notebookId == null -> {
                val libraryViewModel: LibraryViewModel = viewModel()
                LibraryScreen(
                    viewModel = libraryViewModel,
                    settings = settings,
                    onOpenNotebook = { notebookId = it },
                    onSettings = { settingsOpen = true },
                )
            }
            else ->
                EditorRoute(
                    notebookId = requireNotNull(notebookId),
                    libraryGeneration = libraryGeneration,
                    recognitionModelManager = rootRecognitionModelManager,
                    settings = settings,
                    onUpdateSettings = { transform -> scope.launch { settingsRepository.update(transform) } },
                    onBack = { notebookId = null },
                    onSettings = { settingsOpen = true },
                )
        }
    }
}

@Composable
internal fun AppTheme.resolveDarkTheme(
    activity: MainActivity?,
    systemDarkTheme: Boolean = isSystemInDarkTheme(),
): Boolean {
    val darkTheme =
        when (this) {
            AppTheme.SYSTEM -> systemDarkTheme
            AppTheme.LIGHT -> false
            AppTheme.DARK -> true
        }
    SideEffect { activity?.setSystemBarIconAppearance(darkTheme) }
    return darkTheme
}
