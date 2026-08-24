package cz.majkey.perko

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { notebookId = null }) {
                            Text(stringResource(R.string.back))
                        }
                        Text(
                            text = stringResource(R.string.page_editor_placeholder),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            },
        ) { padding ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}
