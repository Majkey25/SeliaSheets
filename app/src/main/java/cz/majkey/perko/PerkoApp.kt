package cz.majkey.perko

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import cz.majkey.perko.editor.EditorRoute
import cz.majkey.perko.library.LibraryScreen
import cz.majkey.perko.library.LibraryViewModel

@Composable
internal fun PerkoApp() {
    var notebookId by rememberSaveable { mutableStateOf<String?>(null) }
    if (notebookId == null) {
        val libraryViewModel: LibraryViewModel = viewModel()
        LibraryScreen(
            viewModel = libraryViewModel,
            onOpenNotebook = { notebookId = it },
        )
    } else {
        EditorRoute(notebookId = requireNotNull(notebookId), onBack = { notebookId = null })
    }
}
