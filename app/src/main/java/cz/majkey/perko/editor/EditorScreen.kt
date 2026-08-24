package cz.majkey.perko.editor

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cz.majkey.perko.R
import cz.majkey.perko.data.PageEntity

@Composable
internal fun EditorRoute(notebookId: String, onBack: () -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    val factory =
        remember(application, notebookId) {
            viewModelFactory { initializer { EditorViewModel(application, notebookId) } }
        }
    val editorViewModel: EditorViewModel =
        viewModel(key = "editor-$notebookId", factory = factory)
    EditorScreen(viewModel = editorViewModel, onBack = onBack)
}

@Composable
private fun EditorScreen(viewModel: EditorViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<PageEntity?>(null) }
    deleteTarget?.let { page ->
        DeletePageDialog(
            onDismiss = { deleteTarget = null },
            onDelete = {
                viewModel.deletePage(page.id)
                deleteTarget = null
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            EditorTopBar(
                title = state.notebook?.title.orEmpty(),
                failed = state.failed,
                onBack = onBack,
                onAddPage = viewModel::addPage,
            )
        },
    ) { padding ->
        if (state.notebook == null || state.pages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.editor_loading))
            }
            return@Scaffold
        }
        val wide = LocalConfiguration.current.screenWidthDp >= 840
        if (wide) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                PageRail(
                    state = state,
                    viewModel = viewModel,
                    onDelete = { deleteTarget = it },
                    modifier = Modifier.width(164.dp).fillMaxHeight(),
                )
                VerticalDivider()
                PageCanvas(
                    page = state.selectedPage,
                    pageNumber = state.pages.indexOf(state.selectedPage) + 1,
                    strokes = state.selectedStrokes,
                    fingerDrawing = state.notebook?.fingerDrawing == true,
                    onStrokeFinished = { stroke ->
                        state.selectedPage?.let { page -> viewModel.addStroke(page.id, stroke) }
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                PageStrip(
                    state = state,
                    viewModel = viewModel,
                    onDelete = { deleteTarget = it },
                    modifier = Modifier.fillMaxWidth().height(148.dp),
                )
                HorizontalDivider()
                PageCanvas(
                    page = state.selectedPage,
                    pageNumber = state.pages.indexOf(state.selectedPage) + 1,
                    strokes = state.selectedStrokes,
                    fingerDrawing = state.notebook?.fingerDrawing == true,
                    onStrokeFinished = { stroke ->
                        state.selectedPage?.let { page -> viewModel.addStroke(page.id, stroke) }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    failed: Boolean,
    onBack: () -> Unit,
    onAddPage: () -> Unit,
) {
    val addDescription = stringResource(R.string.add_page)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (failed) {
                Text(
                    text = stringResource(R.string.editor_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = onAddPage,
                modifier = Modifier.semantics { contentDescription = addDescription },
            ) {
                Text("+", fontSize = 26.sp)
            }
        }
    }
}

@Composable
private fun PageRail(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onDelete: (PageEntity) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
            PageThumbnail(
                pageNumber = index + 1,
                selected = page.id == state.selectedPageId,
                pageCount = state.pages.size,
                onSelect = { viewModel.selectPage(page.id) },
                onDuplicate = { viewModel.duplicatePage(page.id) },
                onMoveUp = { viewModel.movePage(index, index - 1) },
                onMoveDown = { viewModel.movePage(index, index + 1) },
                onDelete = { onDelete(page) },
                modifier = Modifier.fillMaxWidth().height(174.dp),
            )
        }
    }
}

@Composable
private fun PageStrip(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onDelete: (PageEntity) -> Unit,
    modifier: Modifier,
) {
    LazyRow(
        modifier = modifier.padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
            PageThumbnail(
                pageNumber = index + 1,
                selected = page.id == state.selectedPageId,
                pageCount = state.pages.size,
                onSelect = { viewModel.selectPage(page.id) },
                onDuplicate = { viewModel.duplicatePage(page.id) },
                onMoveUp = { viewModel.movePage(index, index - 1) },
                onMoveDown = { viewModel.movePage(index, index + 1) },
                onDelete = { onDelete(page) },
                modifier = Modifier.width(104.dp).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun PageThumbnail(
    pageNumber: Int,
    selected: Boolean,
    pageCount: Int,
    onSelect: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val pageDescription = stringResource(R.string.page_number, pageNumber)
    val actionsDescription = stringResource(R.string.page_actions, pageNumber)
    Surface(
        color = Color(0xFFFFFEFA),
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0xFFC9C6BF)),
        modifier =
            modifier
                .testTag("page-thumbnail")
                .semantics { contentDescription = pageDescription }
                .clickable(onClick = onSelect),
    ) {
        Box(Modifier.padding(6.dp)) {
            Text(
                text = pageNumber.toString(),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            Box(Modifier.align(Alignment.TopEnd)) {
                TextButton(
                    onClick = { menuOpen = true },
                    modifier =
                        Modifier.size(48.dp).semantics {
                            contentDescription = actionsDescription
                        },
                ) {
                    Text("•••")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.duplicate_page)) },
                        onClick = {
                            menuOpen = false
                            onDuplicate()
                        },
                    )
                    if (pageNumber > 1) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.move_page_up)) },
                            onClick = {
                                menuOpen = false
                                onMoveUp()
                            },
                        )
                    }
                    if (pageNumber < pageCount) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.move_page_down)) },
                            onClick = {
                                menuOpen = false
                                onMoveDown()
                            },
                        )
                    }
                    if (pageCount > 1) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_page)) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeletePageDialog(onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_page_title)) },
        text = { Text(stringResource(R.string.delete_page_message)) },
        confirmButton = {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
