package com.majkeylab.seliadocs

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.majkeylab.seliadocs.backup.BackupRoute
import com.majkeylab.seliadocs.backup.BackupViewModel
import com.majkeylab.seliadocs.backup.LibraryReplacementReporter
import com.majkeylab.seliadocs.editor.EditorWorkspace
import com.majkeylab.seliadocs.library.LibraryScreen
import com.majkeylab.seliadocs.library.LibraryViewModel
import com.majkeylab.seliadocs.recognition.RecognitionModelManager
import com.majkeylab.seliadocs.settings.AppSettings
import com.majkeylab.seliadocs.settings.AppTheme
import com.majkeylab.seliadocs.settings.SettingsRepository
import com.majkeylab.seliadocs.settings.SettingsScreen
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import com.majkeylab.seliadocs.ui.OnboardingScreen
import com.majkeylab.seliadocs.ui.NotebookOpeningCover
import com.majkeylab.seliadocs.data.NotebookEntity
import java.io.IOException
import kotlinx.coroutines.launch

@Composable
internal fun SeliaDocsApp(
    backupViewModel: BackupViewModel? = null,
    recognitionModelManager: RecognitionModelManager? = null,
    settingsRepository: SettingsRepository? = null,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val rootBackupViewModel: BackupViewModel = backupViewModel ?: viewModel()
    val rootRecognitionModelManager =
        remember(recognitionModelManager) { recognitionModelManager ?: RecognitionModelManager() }
    val backupState by rootBackupViewModel.state.collectAsStateWithLifecycle()
    val rootSettingsRepository =
        remember(application, settingsRepository) { settingsRepository ?: SettingsRepository.create(application) }
    val loadedSettings by
        rootSettingsRepository.settings.collectAsStateWithLifecycle<AppSettings?>(initialValue = null)
    val settings = loadedSettings ?: AppSettings()
    val recognitionModelStatus by rootRecognitionModelManager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var settingsUpdateGeneration by remember { mutableIntStateOf(0) }
    var settingsWrites by remember { mutableIntStateOf(0) }
    var failedSettingsUpdate by remember { mutableStateOf<((AppSettings) -> AppSettings)?>(null) }
    val updateSettings: ((AppSettings) -> AppSettings) -> Unit = { transform ->
        val generation = ++settingsUpdateGeneration
        failedSettingsUpdate = null
        settingsWrites++
        scope.launch {
            try {
                rootSettingsRepository.update(transform)
            } catch (_: IOException) {
                if (generation == settingsUpdateGeneration) failedSettingsUpdate = transform
            } finally {
                settingsWrites--
            }
        }
    }
    var notebookId by rememberSaveable { mutableStateOf<String?>(null) }
    var openingNotebook by remember { mutableStateOf<NotebookEntity?>(null) }
    var requestedPageId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var backupOpen by rememberSaveable { mutableStateOf(false) }
    var introductionOpen by rememberSaveable { mutableStateOf(false) }
    var introductionDismissed by rememberSaveable { mutableStateOf(false) }
    var libraryGeneration by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(settings.recognitionLanguage) {
        rootRecognitionModelManager.select(settings.recognitionLanguage)
    }
    BackHandler(enabled = introductionOpen || backupOpen || settingsOpen || notebookId != null) {
        openingNotebook = null
        if (introductionOpen) {
            introductionOpen = false
        } else if (backupOpen) {
            backupOpen = false
        } else if (settingsOpen) {
            settingsOpen = false
        } else {
            notebookId = null
        }
    }
    val activity = LocalActivity.current as? MainActivity
    val darkTheme = settings.theme.resolveDarkTheme(activity)
    SeliaDocsTheme(darkTheme = darkTheme, palette = settings.themePalette) {
        if (loadedSettings == null) {
            Surface(Modifier.fillMaxSize()) {}
            return@SeliaDocsTheme
        }
        failedSettingsUpdate?.let { transform ->
            AlertDialog(
                onDismissRequest = { failedSettingsUpdate = null },
                title = { Text(stringResource(R.string.settings_save_failed)) },
                text = { Text(stringResource(R.string.settings_save_failed_message)) },
                confirmButton = {
                    TextButton(onClick = { updateSettings(transform) }) { Text(stringResource(R.string.retry)) }
                },
                dismissButton = {
                    val firstRun = !settings.onboardingComplete && !introductionDismissed
                    TextButton(onClick = {
                        failedSettingsUpdate = null
                        if (firstRun) introductionDismissed = true
                    }) {
                        Text(stringResource(if (firstRun) R.string.intro_continue_unsaved else R.string.dismiss))
                    }
                },
            )
        }
        LibraryReplacementReporter(
            replacementGeneration = backupState.replacementGeneration,
            claimReplacement = rootBackupViewModel::claimPendingReplacement,
            acknowledgeReplacement = rootBackupViewModel::acknowledgeReplacement,
            releaseReplacementClaim = rootBackupViewModel::releaseReplacementClaim,
            onLibraryReplaced = {
                openingNotebook = null
                notebookId = null
                requestedPageId = null
                backupOpen = false
                settingsOpen = false
                libraryGeneration++
            },
        )
        when {
            introductionOpen || (!settings.onboardingComplete && !introductionDismissed) ->
                OnboardingScreen(
                    saving = settingsWrites > 0,
                    onFinish = {
                        introductionOpen = false
                        if (!settings.onboardingComplete) updateSettings { it.copy(onboardingComplete = true) }
                    },
                )
            backupOpen ->
                BackupRoute(
                    viewModel = rootBackupViewModel,
                    onClose = { backupOpen = false },
                )
            settingsOpen ->
                SettingsScreen(
                    settings = settings,
                    onUpdate = updateSettings,
                    onBackup = { backupOpen = true },
                    onClose = { settingsOpen = false },
                    onIntroduction = { introductionOpen = true },
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
                    onOpenNotebook = { id ->
                        openingNotebook = if (settings.pageTransition) libraryViewModel.state.value.notebooks.firstOrNull { it.id == id } else null
                        requestedPageId = null
                        notebookId = id
                    },
                    onSettings = { settingsOpen = true },
                )
            }
            else -> Box(Modifier.fillMaxSize()) {
                EditorWorkspace(
                    notebookId = requireNotNull(notebookId),
                    initialPageId = requestedPageId,
                    onInitialPageOpened = { requestedPageId = null },
                    libraryGeneration = libraryGeneration,
                    recognitionModelManager = rootRecognitionModelManager,
                    settings = settings,
                    onUpdateSettings = updateSettings,
                    onBack = { openingNotebook = null; notebookId = null },
                    onSettings = { openingNotebook = null; settingsOpen = true },
                )
                NotebookOpeningCover(openingNotebook, settings.pageTransition) { id ->
                    if (openingNotebook?.id == id) openingNotebook = null
                }
            }
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
