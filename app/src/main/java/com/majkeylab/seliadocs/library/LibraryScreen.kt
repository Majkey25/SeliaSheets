package com.majkeylab.seliadocs.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.NotebookEntity
import com.majkeylab.seliadocs.settings.AppSettings
import com.majkeylab.seliadocs.ui.coverColorValue

@Composable
internal fun LibraryScreen(
    viewModel: LibraryViewModel,
    settings: AppSettings,
    onOpenNotebook: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    var createOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<NotebookEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<NotebookEntity?>(null) }
    var actionTarget by remember { mutableStateOf<NotebookEntity?>(null) }

    LaunchedEffect(state.notebooks.firstOrNull()?.id) {
        gridState.scrollToItem(0)
    }

    if (createOpen) {
        CreateNotebookDialog(
            defaults = settings,
            onDismiss = { createOpen = false },
            onCreate = {
                viewModel.createNotebook(it)
                createOpen = false
            },
        )
    }
    renameTarget?.let { notebook ->
        RenameNotebookDialog(
            notebook = notebook,
            onDismiss = { renameTarget = null },
            onRename = { title ->
                viewModel.renameNotebook(notebook.id, title)
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { notebook ->
        DeleteNotebookDialog(
            onDismiss = { deleteTarget = null },
            onDelete = {
                viewModel.deleteNotebook(notebook.id)
                deleteTarget = null
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                LibraryTopBar(
                    state = state,
                    onQueryChange = viewModel::setQuery,
                    onTrashChange = viewModel::setTrash,
                    onSettings = onSettings,
                )
            },
            floatingActionButton = {
                if (!state.trash) {
                    val description = stringResource(R.string.new_notebook)
                    FloatingActionButton(
                        onClick = { createOpen = true },
                        modifier = Modifier.semantics { contentDescription = description },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text("+", fontSize = 28.sp)
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (state.notebooks.isEmpty()) {
                    EmptyLibrary(modifier = Modifier.align(Alignment.Center))
                } else {
                    val columns =
                        if (LocalDensity.current.fontScale >= 1.5f) {
                            GridCells.Fixed(1)
                        } else {
                            GridCells.Adaptive(148.dp)
                        }
                    LazyVerticalGrid(
                        columns = columns,
                        state = gridState,
                        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.notebooks, key = NotebookEntity::id) { notebook ->
                            NotebookCover(
                                notebook = notebook,
                                onOpen = { onOpenNotebook(notebook.id) },
                                onActions = { actionTarget = notebook },
                            )
                        }
                    }
                }
                if (state.failed) {
                    Text(
                        text = stringResource(R.string.library_error),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
                    )
                }
            }
        }
        actionTarget?.let { notebook ->
            NotebookActionSheet(
                notebook = notebook,
                trash = state.trash,
                onDismiss = { actionTarget = null },
                onRename = {
                    actionTarget = null
                    renameTarget = notebook
                },
                onFavorite = {
                    actionTarget = null
                    viewModel.setFavorite(notebook.id, !notebook.favorite)
                },
                onTrash = {
                    actionTarget = null
                    viewModel.setTrashed(notebook.id, true)
                },
                onRestore = {
                    actionTarget = null
                    viewModel.setTrashed(notebook.id, false)
                },
                onDelete = {
                    actionTarget = null
                    deleteTarget = notebook
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LibraryTopBar(
    state: LibraryUiState,
    onQueryChange: (String) -> Unit,
    onTrashChange: (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        TopAppBar(
            modifier = Modifier.testTag("library-top-bar"),
            title = {
                Text(
                    text = stringResource(if (state.trash) R.string.trash else R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("library-top-bar-title"),
                )
            },
            actions = {
                TextButton(onClick = { onTrashChange(!state.trash) }) {
                    Text(stringResource(if (state.trash) R.string.active_notebooks else R.string.trash))
                }
                TextButton(onClick = onSettings) { Text(stringResource(R.string.settings)) }
            },
        )
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.search_notebooks)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.no_notebooks), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.no_notebooks_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotebookCover(
    notebook: NotebookEntity,
    onOpen: () -> Unit,
    onActions: () -> Unit,
) {
    val openDescription = stringResource(R.string.open_notebook, notebook.title)
    val actionsDescription = stringResource(R.string.notebook_actions, notebook.title)
    Surface(
        color = notebookCoverColor(notebook.coverColor),
        contentColor = Color(0xFF202124),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().aspectRatio(0.76f),
    ) {
        Box {
            CoverPatternOverlay(notebook.coverPattern, Modifier.fillMaxSize())
            NotebookBinding(Modifier.align(Alignment.CenterStart).width(34.dp).fillMaxHeight())
            Surface(
                color = Color(0xFFE9A092),
                shape = RoundedCornerShape(bottomStart = 7.dp, bottomEnd = 7.dp),
                modifier = Modifier.align(Alignment.TopCenter).size(width = 48.dp, height = 22.dp),
            ) {}
            Box(
                Modifier.matchParentSize()
                    .clickable(role = Role.Button, onClick = onOpen)
                    .semantics { contentDescription = openDescription },
            )
            Column(modifier = Modifier.padding(start = 28.dp, top = 14.dp, end = 14.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (notebook.favorite) Text("★", color = Color(0xFFE07B67))
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onActions,
                        modifier =
                            Modifier.clearAndSetSemantics {
                                contentDescription = actionsDescription
                                onClick {
                                    onActions()
                                    true
                                }
                            },
                    ) {
                        Text("•••")
                    }
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    color = Color(0xFFFDFBF7),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                ) {
                    Text(
                        text = notebook.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NotebookActionSheet(
    notebook: NotebookEntity,
    trash: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onFavorite: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = notebook.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (trash) {
                ActionButton(R.string.restore, onRestore)
                ActionButton(R.string.delete_permanently, onDelete)
            } else {
                ActionButton(R.string.rename, onRename)
                ActionButton(
                    if (notebook.favorite) R.string.remove_favorite else R.string.favorite,
                    onFavorite,
                )
                ActionButton(R.string.move_to_trash, onTrash)
            }
            ActionButton(R.string.cancel, onDismiss)
        }
    }
}

@Composable
private fun ActionButton(label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label))
    }
}

@Composable
private fun RenameNotebookDialog(
    notebook: NotebookEntity,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var title by remember(notebook.id) { mutableStateOf(notebook.title) }
    val fallback = stringResource(R.string.untitled_notebook)
    val nameDescription = stringResource(R.string.notebook_name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename)) },
        text = {
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
        },
        confirmButton = {
            TextButton(onClick = { onRename(normalizeTitle(title, fallback)) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun DeleteNotebookDialog(onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_notebook_title)) },
        text = { Text(stringResource(R.string.delete_notebook_message)) },
        confirmButton = {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun NotebookBinding(modifier: Modifier) {
    Canvas(modifier) {
        repeat(4) { index ->
            val centerY = size.height * (0.2f + index * 0.2f)
            drawArc(
                color = Color(0xFFFDFBF7),
                startAngle = 70f,
                sweepAngle = 220f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(-size.width * 0.15f, centerY - 13.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(26.dp.toPx(), 26.dp.toPx()),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun CoverPatternOverlay(patternValue: String, modifier: Modifier) {
    val pattern = runCatching { CoverPattern.valueOf(patternValue) }.getOrDefault(CoverPattern.SOLID)
    Canvas(modifier) {
        when (pattern) {
            CoverPattern.SOLID -> Unit
            CoverPattern.BAND ->
                drawRect(
                    color = Color.White.copy(alpha = 0.16f),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.58f, 0f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.16f, size.height),
                )
            CoverPattern.CORNERS -> {
                drawCircle(
                    color = Color(0xFFE9A092).copy(alpha = 0.55f),
                    radius = size.minDimension * 0.22f,
                    center = androidx.compose.ui.geometry.Offset(size.width, 0f),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.16f),
                    radius = size.minDimension * 0.18f,
                    center = androidx.compose.ui.geometry.Offset(0f, size.height),
                )
            }
            CoverPattern.GRID -> {
                val spacing = 22.dp.toPx()
                var x = spacing
                while (x < size.width) {
                    drawLine(Color.White.copy(alpha = 0.14f), start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height))
                    x += spacing
                }
                var y = spacing
                while (y < size.height) {
                    drawLine(Color.White.copy(alpha = 0.14f), start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(size.width, y))
                    y += spacing
                }
            }
        }
    }
}

private fun notebookCoverColor(value: String): Color =
    coverColorValue(
        runCatching { CoverColor.valueOf(value) }.getOrDefault(CoverColor.PERIWINKLE),
    )
