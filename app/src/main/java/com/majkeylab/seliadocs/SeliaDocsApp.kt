package com.majkeylab.seliadocs

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
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
import com.majkeylab.seliadocs.editor.EditorRoute
import com.majkeylab.seliadocs.library.LibraryScreen
import com.majkeylab.seliadocs.library.LibraryViewModel
import com.majkeylab.seliadocs.settings.AppSettings
import com.majkeylab.seliadocs.settings.AppTheme
import com.majkeylab.seliadocs.settings.SettingsRepository
import com.majkeylab.seliadocs.settings.SettingsScreen
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import kotlinx.coroutines.launch

@Composable
internal fun SeliaDocsApp() {
    val application = LocalContext.current.applicationContext as Application
    val settingsRepository = remember(application) { SettingsRepository.create(application) }
    val settings by
        settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var notebookId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var backupOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = backupOpen || settingsOpen || notebookId != null) {
        if (backupOpen) {
            backupOpen = false
        } else if (settingsOpen) {
            settingsOpen = false
        } else {
            notebookId = null
        }
    }
    val darkTheme =
        when (settings.theme) {
            AppTheme.SYSTEM -> isSystemInDarkTheme()
            AppTheme.LIGHT -> false
            AppTheme.DARK -> true
        }
    SeliaDocsTheme(darkTheme = darkTheme) {
        when {
            backupOpen -> BackupRoute(onClose = { backupOpen = false })
            settingsOpen ->
                SettingsScreen(
                    settings = settings,
                    onUpdate = { value -> scope.launch { settingsRepository.update { value } } },
                    onBackup = { backupOpen = true },
                    onClose = { settingsOpen = false },
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
                    settings = settings,
                    onBack = { notebookId = null },
                    onSettings = { settingsOpen = true },
                )
        }
    }
}
