package com.majkeylab.seliadocs.editor

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.settings.AppSettings
import com.majkeylab.seliadocs.settings.DefaultTool
import kotlinx.coroutines.delay

@Composable
internal fun EditorRoute(
    notebookId: String,
    settings: AppSettings,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as Application
    val initialTool =
        when (settings.defaultTool) {
            DefaultTool.PEN -> EditorTool.PEN
            DefaultTool.PENCIL -> EditorTool.PENCIL
            DefaultTool.HIGHLIGHTER -> EditorTool.HIGHLIGHTER
        }
    val factory =
        remember(application, notebookId, initialTool) {
            viewModelFactory { initializer { EditorViewModel(application, notebookId, initialTool) } }
        }
    val editorViewModel: EditorViewModel =
        viewModel(key = "editor-$notebookId", factory = factory)
    EditorScreen(viewModel = editorViewModel, settings = settings, onBack = onBack, onSettings = onSettings)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun EditorScreen(
    viewModel: EditorViewModel,
    settings: AppSettings,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var textPageId by remember { mutableStateOf<String?>(null) }
    var imagePageId by remember { mutableStateOf<String?>(null) }
    var mathPageId by remember { mutableStateOf<String?>(null) }
    var shapeDialogOpen by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var contentsOpen by rememberSaveable { mutableStateOf(false) }
    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val pageId = imagePageId
            imagePageId = null
            if (uri != null && pageId != null) viewModel.importImage(pageId, uri)
        }
    val pdfPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importPdf(uri)
        }
    val pdfExporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) viewModel.exportPdf(uri)
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
    if (searchOpen) {
        SearchDialog(
            state = state,
            onQuery = viewModel::searchPageText,
            onSelect = { pageId ->
                viewModel.selectPage(pageId)
                viewModel.clearSearch()
                searchOpen = false
            },
            onDismiss = {
                viewModel.clearSearch()
                searchOpen = false
            },
        )
    }
    if (contentsOpen) {
        ModalBottomSheet(
            onDismissRequest = { contentsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ContentsPanel(
                state = state,
                onSelectPage = {
                    viewModel.selectPage(it)
                    contentsOpen = false
                },
                onCreateChapter = viewModel::createChapter,
                onDeleteChapter = viewModel::deleteChapter,
                onRenamePage = viewModel::renamePage,
                onBookmarkPage = viewModel::setPageBookmarked,
                onAssignPage = viewModel::assignPageToChapter,
                onDuplicatePage = viewModel::duplicatePage,
                onDeletePage = viewModel::deletePage,
                loadPagePreview = viewModel::loadPagePreview,
                modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp, max = 720.dp),
            )
        }
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
    Scaffold(
        modifier =
            Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.isCtrlPressed && event.key == Key.Z && event.isShiftPressed -> {
                        viewModel.redo()
                        true
                    }
                    event.isCtrlPressed && event.key == Key.Z -> {
                        viewModel.undo()
                        true
                    }
                    event.key == Key.PageUp -> {
                        viewModel.selectPreviousPage()
                        true
                    }
                    event.key == Key.PageDown -> {
                        viewModel.selectNextPage()
                        true
                    }
                    else -> false
                }
            },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column {
                EditorTopBar(
                    title = state.notebook?.title.orEmpty(),
                    failed = state.failed,
                    onBack = onBack,
                    onAddPage = viewModel::addPage,
                    onSettings = onSettings,
                    onExport = {
                        val title = state.notebook?.title.orEmpty().ifBlank { "SeliaSheets notebook" }
                        pdfExporter.launch("${safeFileName(title)}.pdf")
                    },
                )
                HorizontalDivider()
                EditorToolBar(
                    state = state,
                    onSelectTool = viewModel::selectTool,
                    onEraserMode = viewModel::setEraserMode,
                    onUndo = viewModel::undo,
                    onRedo = viewModel::redo,
                    onSearch = { searchOpen = true },
                    onAddText = { textPageId = state.selectedPage?.id },
                    onAddImage = {
                        imagePageId = state.selectedPage?.id
                        if (imagePageId != null) {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                    },
                    onImportPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                    onCleanShape = { shapeDialogOpen = true },
                    onAddMath = { mathPageId = state.selectedPage?.id },
                )
                if (state.selectedElement != null) {
                    HorizontalDivider()
                    ElementContextBar(
                        onDuplicate = viewModel::duplicateSelectedElement,
                        onBringForward = viewModel::bringSelectedElementForward,
                        onDelete = viewModel::deleteSelectedElement,
                    )
                }
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
                ContentsPanel(
                    state = state,
                    onSelectPage = viewModel::selectPage,
                    onCreateChapter = viewModel::createChapter,
                    onDeleteChapter = viewModel::deleteChapter,
                    onRenamePage = viewModel::renamePage,
                    onBookmarkPage = viewModel::setPageBookmarked,
                    onAssignPage = viewModel::assignPageToChapter,
                    onDuplicatePage = viewModel::duplicatePage,
                    onDeletePage = viewModel::deletePage,
                    loadPagePreview = viewModel::loadPagePreview,
                    modifier = Modifier.width(244.dp).fillMaxHeight(),
                )
                VerticalDivider()
                PageCanvas(
                    page = state.selectedPage,
                    pageNumber = state.pages.indexOf(state.selectedPage) + 1,
                    strokes = state.selectedStrokes,
                    elements = state.selectedElements,
                    blocks = state.selectedBlocks,
                    selectedStrokeIds = state.selectedStrokeIds,
                    selectedElementId = state.selectedElementId,
                    smartShapePreviewId = state.smartShapePreviewId,
                    fingerDrawing = state.notebook?.fingerDrawing == true,
                    tool = state.tool,
                    penWidth = settings.penWidth,
                    highlighterWidth = settings.highlighterWidth,
                    pageTransitionEnabled = settings.pageTransition,
                    onStrokeFinished = { pageId, stroke ->
                        viewModel.addStroke(pageId, stroke, settings.shapeAssist)
                    },
                    onEraseFinished = viewModel::eraseStrokes,
                    onSelectContent = viewModel::selectContent,
                    onMoveSelection = viewModel::moveSelectedStrokes,
                    onPageTextChanged = viewModel::updatePageText,
                    onCommitElementTransform = viewModel::updateSelectedElement,
                    assetFile = viewModel::assetFile,
                    loadPdfPage = viewModel::renderPdfPage,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                PageLocationBar(
                    state = state,
                    onOpenContents = { contentsOpen = true },
                    onBookmarkPage = viewModel::setPageBookmarked,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                )
                HorizontalDivider()
                PageCanvas(
                    page = state.selectedPage,
                    pageNumber = state.pages.indexOf(state.selectedPage) + 1,
                    strokes = state.selectedStrokes,
                    elements = state.selectedElements,
                    blocks = state.selectedBlocks,
                    selectedStrokeIds = state.selectedStrokeIds,
                    selectedElementId = state.selectedElementId,
                    smartShapePreviewId = state.smartShapePreviewId,
                    fingerDrawing = state.notebook?.fingerDrawing == true,
                    tool = state.tool,
                    penWidth = settings.penWidth,
                    highlighterWidth = settings.highlighterWidth,
                    pageTransitionEnabled = settings.pageTransition,
                    onStrokeFinished = { pageId, stroke ->
                        viewModel.addStroke(pageId, stroke, settings.shapeAssist)
                    },
                    onEraseFinished = viewModel::eraseStrokes,
                    onSelectContent = viewModel::selectContent,
                    onMoveSelection = viewModel::moveSelectedStrokes,
                    onPageTextChanged = viewModel::updatePageText,
                    onCommitElementTransform = viewModel::updateSelectedElement,
                    assetFile = viewModel::assetFile,
                    loadPdfPage = viewModel::renderPdfPage,
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
    onEraserMode: (EraserMode) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSearch: () -> Unit,
    onAddText: () -> Unit,
    onAddImage: () -> Unit,
    onImportPdf: () -> Unit,
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
        TextButton(onClick = onSearch) { Text(stringResource(R.string.search)) }
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
        if (state.tool == EditorTool.ERASER) {
            VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
            EraserMode.entries.forEach { mode ->
                Surface(
                    onClick = { onEraserMode(mode) },
                    color =
                        if (state.eraserMode == mode) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        eraserModeLabel(mode),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
        Surface(onClick = onAddText, color = Color.Transparent, shape = RoundedCornerShape(10.dp)) {
            Text(
                stringResource(R.string.tool_text_box),
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
        Surface(onClick = onImportPdf, color = Color.Transparent, shape = RoundedCornerShape(10.dp)) {
            Text(
                stringResource(R.string.import_pdf),
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
private fun SearchDialog(
    state: EditorUiState,
    onQuery: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(state.searchQuery) }
    val visibleResults = if (state.searchQuery == query) state.searchResults else emptyList()
    LaunchedEffect(query) {
        delay(250)
        onQuery(query)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_notebook)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(256) },
                    label = { Text(stringResource(R.string.search_page_text)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    itemsIndexed(visibleResults, key = { _, result -> result.pageId }) { _, result ->
                        TextButton(
                            onClick = { onSelect(result.pageId) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    stringResource(R.string.search_page_result, result.pageIndex + 1),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    result.text.replace('\n', ' ').take(120),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
                if (query.isNotBlank() && state.searchQuery == query && visibleResults.isEmpty()) {
                    Text(stringResource(R.string.no_search_results), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
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
            EditorTool.TYPE -> R.string.tool_type
            EditorTool.PEN -> R.string.tool_pen
            EditorTool.PENCIL -> R.string.tool_pencil
            EditorTool.HIGHLIGHTER -> R.string.tool_highlighter
            EditorTool.ERASER -> R.string.tool_eraser
            EditorTool.LASSO -> R.string.tool_lasso
        },
    )

@Composable
private fun eraserModeLabel(mode: EraserMode): String =
    stringResource(
        when (mode) {
            EraserMode.SEGMENT -> R.string.eraser_segment
            EraserMode.STROKE -> R.string.eraser_stroke
        },
    )

@Composable
private fun EditorTopBar(
    title: String,
    failed: Boolean,
    onBack: () -> Unit,
    onAddPage: () -> Unit,
    onSettings: () -> Unit,
    onExport: () -> Unit,
) {
    val addDescription = stringResource(R.string.add_page)
    val moreDescription = stringResource(R.string.more_options)
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onAddPage,
                    modifier = Modifier.semantics { contentDescription = addDescription },
                ) {
                    Text("+", fontSize = 26.sp)
                }
                Box {
                    TextButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.semantics { contentDescription = moreDescription },
                    ) {
                        Text(stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings)) },
                            onClick = {
                                menuOpen = false
                                onSettings()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_pdf)) },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                        )
                    }
                }
            }
            if (failed) {
                Text(
                    text = stringResource(R.string.editor_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

private fun safeFileName(value: String): String =
    value.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(100).ifBlank { "SeliaSheets notebook" }
