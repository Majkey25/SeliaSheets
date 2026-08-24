package cz.majkey.perko.editor

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.OutlinedTextField
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
    var textPageId by remember { mutableStateOf<String?>(null) }
    var imagePageId by remember { mutableStateOf<String?>(null) }
    var mathPageId by remember { mutableStateOf<String?>(null) }
    var shapeDialogOpen by remember { mutableStateOf(false) }
    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val pageId = imagePageId
            imagePageId = null
            if (uri != null && pageId != null) viewModel.importImage(pageId, uri)
        }
    textPageId?.let { pageId ->
        TextElementDialog(
            onDismiss = { textPageId = null },
            onSave = { text ->
                viewModel.addText(pageId, text)
                textPageId = null
            },
        )
    }
    if (shapeDialogOpen) {
        ShapeDialog(
            onDismiss = { shapeDialogOpen = false },
            onSelect = { kind ->
                viewModel.cleanSelectedShape(kind)
                shapeDialogOpen = false
            },
        )
    }
    mathPageId?.let { pageId ->
        MathDialog(
            onDismiss = { mathPageId = null },
            onSave = { expression ->
                viewModel.addMath(pageId, expression)
                mathPageId = null
            },
        )
    }
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
            Column {
                EditorTopBar(
                    title = state.notebook?.title.orEmpty(),
                    failed = state.failed,
                    onBack = onBack,
                    onAddPage = viewModel::addPage,
                )
                HorizontalDivider()
                EditorToolBar(
                    state = state,
                    onSelectTool = viewModel::selectTool,
                    onUndo = viewModel::undo,
                    onRedo = viewModel::redo,
                    onAddText = { textPageId = state.selectedPage?.id },
                    onAddImage = {
                        imagePageId = state.selectedPage?.id
                        if (imagePageId != null) {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                    },
                    onCleanShape = { shapeDialogOpen = true },
                    onAddMath = { mathPageId = state.selectedPage?.id },
                )
            }
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
                    elements = state.selectedElements,
                    selectedStrokeIds = state.selectedStrokeIds,
                    fingerDrawing = state.notebook?.fingerDrawing == true,
                    tool = state.tool,
                    onStrokeFinished = { stroke ->
                        state.selectedPage?.let { page -> viewModel.addStroke(page.id, stroke) }
                    },
                    onEraseFinished = { points ->
                        state.selectedPage?.let { page -> viewModel.eraseStrokes(page.id, points) }
                    },
                    onLassoFinished = { points ->
                        state.selectedPage?.let { page -> viewModel.selectStrokes(page.id, points) }
                    },
                    onMoveSelection = { delta ->
                        state.selectedPage?.let { page -> viewModel.moveSelectedStrokes(page.id, delta) }
                    },
                    assetFile = viewModel::assetFile,
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
                    elements = state.selectedElements,
                    selectedStrokeIds = state.selectedStrokeIds,
                    fingerDrawing = state.notebook?.fingerDrawing == true,
                    tool = state.tool,
                    onStrokeFinished = { stroke ->
                        state.selectedPage?.let { page -> viewModel.addStroke(page.id, stroke) }
                    },
                    onEraseFinished = { points ->
                        state.selectedPage?.let { page -> viewModel.eraseStrokes(page.id, points) }
                    },
                    onLassoFinished = { points ->
                        state.selectedPage?.let { page -> viewModel.selectStrokes(page.id, points) }
                    },
                    onMoveSelection = { delta ->
                        state.selectedPage?.let { page -> viewModel.moveSelectedStrokes(page.id, delta) }
                    },
                    assetFile = viewModel::assetFile,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EditorToolBar(
    state: EditorUiState,
    onSelectTool: (EditorTool) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddText: () -> Unit,
    onAddImage: () -> Unit,
    onCleanShape: () -> Unit,
    onAddMath: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onUndo, enabled = state.canUndo) { Text(stringResource(R.string.undo)) }
        TextButton(onClick = onRedo, enabled = state.canRedo) { Text(stringResource(R.string.redo)) }
        VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
        EditorTool.entries.forEach { tool ->
            Surface(
                onClick = { onSelectTool(tool) },
                color =
                    if (state.tool == tool) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = toolLabel(tool),
                    color =
                        if (state.tool == tool) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
        Surface(onClick = onAddText, color = Color.Transparent, shape = RoundedCornerShape(10.dp)) {
            Text(
                stringResource(R.string.tool_text),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        Surface(onClick = onAddImage, color = Color.Transparent, shape = RoundedCornerShape(10.dp)) {
            Text(
                stringResource(R.string.tool_image),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        TextButton(onClick = onCleanShape, enabled = state.selectedStrokeIds.isNotEmpty()) {
            Text(stringResource(R.string.tool_shape))
        }
        Surface(onClick = onAddMath, color = Color.Transparent, shape = RoundedCornerShape(10.dp)) {
            Text(
                stringResource(R.string.tool_math),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        if (state.selectedStrokeIds.isNotEmpty()) {
            Text(
                stringResource(R.string.selected_strokes, state.selectedStrokeIds.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun MathDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var expression by remember { mutableStateOf("") }
    val source = expression.trim().let { value -> if (value.endsWith('=')) value else "$value=" }
    val valid = expression.isNotBlank() && evaluateExpression(source).isSuccess
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_math)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = expression,
                    onValueChange = { expression = it.take(256) },
                    label = { Text(stringResource(R.string.math_expression)) },
                    singleLine = true,
                    isError = expression.isNotBlank() && !valid,
                )
                if (expression.isNotBlank() && !valid) {
                    Text(
                        stringResource(R.string.invalid_expression),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(expression) }, enabled = valid) {
                Text(stringResource(R.string.calculate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ShapeDialog(onDismiss: () -> Unit, onSelect: (ShapeKind) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clean_shape)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ShapeKind.entries.forEach { kind ->
                    TextButton(onClick = { onSelect(kind) }, modifier = Modifier.fillMaxWidth()) {
                        Text(shapeLabel(kind), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun shapeLabel(kind: ShapeKind): String =
    stringResource(
        when (kind) {
            ShapeKind.LINE -> R.string.shape_line
            ShapeKind.ARROW -> R.string.shape_arrow
            ShapeKind.ELLIPSE -> R.string.shape_ellipse
            ShapeKind.RECTANGLE -> R.string.shape_rectangle
            ShapeKind.TRIANGLE -> R.string.shape_triangle
        },
    )

@Composable
private fun TextElementDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_text)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(10_000) },
                label = { Text(stringResource(R.string.text_content)) },
                minLines = 3,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun toolLabel(tool: EditorTool): String =
    stringResource(
        when (tool) {
            EditorTool.PEN -> R.string.tool_pen
            EditorTool.PENCIL -> R.string.tool_pencil
            EditorTool.HIGHLIGHTER -> R.string.tool_highlighter
            EditorTool.ERASER -> R.string.tool_eraser
            EditorTool.LASSO -> R.string.tool_lasso
        },
    )

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
