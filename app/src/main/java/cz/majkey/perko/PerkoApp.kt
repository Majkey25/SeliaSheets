package cz.majkey.perko

import android.app.Application
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
import cz.majkey.perko.editor.EditorRoute
import cz.majkey.perko.library.LibraryScreen
import cz.majkey.perko.library.LibraryViewModel
import cz.majkey.perko.settings.AppSettings
import cz.majkey.perko.settings.AppTheme
import cz.majkey.perko.settings.SettingsRepository
import cz.majkey.perko.settings.SettingsScreen
import cz.majkey.perko.ui.PerkoTheme
import kotlinx.coroutines.launch

@Composable
internal fun PerkoApp() {
    val application = LocalContext.current.applicationContext as Application
    val settingsRepository = remember(application) { SettingsRepository.create(application) }
    val settings by
        settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var notebookId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val darkTheme =
        when (settings.theme) {
            AppTheme.SYSTEM -> isSystemInDarkTheme()
            AppTheme.LIGHT -> false
            AppTheme.DARK -> true
        }
    PerkoTheme(darkTheme = darkTheme) {
        when {
            settingsOpen ->
                SettingsScreen(
                    settings = settings,
                    onUpdate = { value -> scope.launch { settingsRepository.update { value } } },
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
