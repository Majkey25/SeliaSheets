package com.majkeylab.seliadocs.editor

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.PageTextMatch
import com.majkeylab.seliadocs.recognition.InkMathCandidate
import com.majkeylab.seliadocs.recognition.InkTextRecognizer
import com.majkeylab.seliadocs.recognition.RecognitionLanguage
import com.majkeylab.seliadocs.recognition.RecognitionModelManager
import com.majkeylab.seliadocs.settings.AppSettings
import com.majkeylab.seliadocs.settings.DefaultTool
import com.majkeylab.seliadocs.settings.HIGHLIGHTER_WIDTH_RANGE
import com.majkeylab.seliadocs.settings.PEN_WIDTH_RANGE
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class SeliaWindowClass { COMPACT, MEDIUM, EXPANDED }

internal fun seliaWindowClass(widthDp: Int): SeliaWindowClass =
    when {
        widthDp >= 840 -> SeliaWindowClass.EXPANDED
        widthDp >= 600 -> SeliaWindowClass.MEDIUM
        else -> SeliaWindowClass.COMPACT
    }

internal data class PageTextDraft(val pageId: String, val value: TextFieldValue)

internal enum class EditorCloseIntent { BACK, SETTINGS }

internal data class EditorCloseState(
    val intent: EditorCloseIntent? = null,
    val completed: Boolean = false,
) {
    val closing: Boolean
        get() = intent != null
}

internal class EditorSessionHolder : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private var sessionKey: String? = null
    private var draft: PageTextDraft? = null
    private val mutableCloseState = MutableStateFlow(EditorCloseState())
    val closeState = mutableCloseState.asStateFlow()

    @Synchronized
    fun prepare(key: String) {
        if (sessionKey == null) {
            sessionKey = key
            return
        }
        if (sessionKey == key) return
        viewModelStore.clear()
        sessionKey = key
        draft = null
        mutableCloseState.value = EditorCloseState()
    }

    @Synchronized
    fun acceptDraft(pageId: String, value: TextFieldValue): Boolean {
        if (mutableCloseState.value.closing) return false
        draft = PageTextDraft(pageId, value)
        return true
    }

    @Synchronized
    fun beginClose(intent: EditorCloseIntent): Boolean {
        if (mutableCloseState.value.closing) return false
        mutableCloseState.value = EditorCloseState(intent = intent)
        return true
    }

    @Synchronized
    fun latestDraft(validPageIds: Set<String>): PageTextDraft? {
        val current = draft ?: return null
        if (current.pageId in validPageIds) return current
        draft = null
        return null
    }

    @Synchronized
    fun draftFor(pageId: String?): TextFieldValue? = draft?.takeIf { it.pageId == pageId }?.value

    @Synchronized
    fun mutationsAllowed(): Boolean = !mutableCloseState.value.closing

    @Synchronized
    fun completeClose(saved: Boolean) {
        val current = mutableCloseState.value
        if (!current.closing) return
        mutableCloseState.value = if (saved) current.copy(completed = true) else EditorCloseState()
    }

    @Synchronized
    fun consumeCompletedClose() {
        val completed = mutableCloseState.value
        check(completed.completed)
        if (completed.intent == EditorCloseIntent.BACK) viewModelStore.clear()
        draft = null
        mutableCloseState.value = EditorCloseState()
    }

    override fun onCleared() {
        viewModelStore.clear()
    }
}

@Composable
internal fun EditorRoute(
    notebookId: String,
    libraryGeneration: Long,
    recognitionModelManager: RecognitionModelManager,
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
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
    val holderFactory = remember { viewModelFactory { initializer { EditorSessionHolder() } } }
    val recognizerProvider: suspend (RecognitionLanguage) -> InkTextRecognizer =
        remember(recognitionModelManager) {
            { language -> recognitionModelManager.createRecognizer(language) }
        }
    val sessionHolder: EditorSessionHolder =
        viewModel(key = "editor-session-holder", factory = holderFactory)
    sessionHolder.prepare("$libraryGeneration:$notebookId")
    val factory =
        remember(application, notebookId, initialTool, sessionHolder, recognizerProvider) {
            viewModelFactory {
                initializer {
                    EditorViewModel(
                        application,
                        notebookId,
                        initialTool,
                        sessionHolder::mutationsAllowed,
                        recognizerProvider,
                    )
                }
            }
        }
    CompositionLocalProvider(LocalViewModelStoreOwner provides sessionHolder) {
        val editorViewModel: EditorViewModel = viewModel(key = "editor", factory = factory)
        EditorScreen(
            viewModel = editorViewModel,
            sessionHolder = sessionHolder,
            settings = settings,
            onUpdateSettings = onUpdateSettings,
            onBack = onBack,
            onSettings = onSettings,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun EditorScreen(
    viewModel: EditorViewModel,
    sessionHolder: EditorSessionHolder,
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val closeState by sessionHolder.closeState.collectAsStateWithLifecycle()
    var textPageId by remember { mutableStateOf<String?>(null) }
    var imagePageId by remember { mutableStateOf<String?>(null) }
    var shapeDialogOpen by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var contentsOpen by rememberSaveable { mutableStateOf(false) }
    val requestSearch: () -> Unit = {
        val draft = sessionHolder.latestDraft(state.pages.mapTo(mutableSetOf()) { it.id })
        viewModel.flushPageTextBeforeSearch(draft?.pageId, draft?.value?.text) { saved ->
            if (saved && sessionHolder.mutationsAllowed()) searchOpen = true
        }
    }
    val requestClose: (EditorCloseIntent) -> Unit = { intent ->
        if (sessionHolder.beginClose(intent)) {
            val draft = sessionHolder.latestDraft(state.pages.mapTo(mutableSetOf()) { it.id })
            viewModel.flushPageTextBeforeClose(
                draft?.pageId,
                draft?.value?.text,
                sessionHolder::completeClose,
            )
        }
    }
    LaunchedEffect(closeState.intent, closeState.completed) {
        val intent = closeState.intent
        if (intent == null || !closeState.completed) return@LaunchedEffect
        when (intent) {
            EditorCloseIntent.BACK -> onBack()
            EditorCloseIntent.SETTINGS -> onSettings()
        }
        sessionHolder.consumeCompletedClose()
    }
    BackHandler { requestClose(EditorCloseIntent.BACK) }
    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val pageId = imagePageId
            imagePageId = null
            if (uri != null && pageId != null) viewModel.importImage(pageId, uri, settings.imageOcr)
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
    if (state.ambiguousMathCandidates.isNotEmpty()) {
        MathCandidatesDialog(
            candidates = state.ambiguousMathCandidates,
            onSelect = viewModel::chooseMathCandidate,
            onDismiss = viewModel::dismissMathCandidates,
        )
    }
    if (state.handwritingCandidates.isNotEmpty()) {
        HandwritingCandidatesDialog(
            candidates = state.handwritingCandidates,
            onSelect = viewModel::addHandwritingCandidateToPage,
            onDismiss = viewModel::dismissHandwritingCandidates,
        )
    }
    if (searchOpen) {
        SearchDialog(
            state = state,
            onQuery = { query -> viewModel.searchPageText(query, settings.imageOcr) },
            onSelect = { result ->
                viewModel.openSearchResult(result)
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
    val onAddImage = {
        imagePageId = state.selectedPage?.id
        if (imagePageId != null) {
            imagePicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
    val onExport = {
        val title = state.notebook?.title.orEmpty().ifBlank { "SeliaSheets notebook" }
        pdfExporter.launch("${safeFileName(title)}.pdf")
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowClass = seliaWindowClass(maxWidth.value.toInt())
        val compact = windowClass == SeliaWindowClass.COMPACT
        Scaffold(
            modifier =
                Modifier.fillMaxSize().onPreviewKeyEvent { event ->
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
                    if (compact) {
                        CompactEditorTopBar(
                            state = state,
                            onBack = { requestClose(EditorCloseIntent.BACK) },
                            onOpenContents = { contentsOpen = true },
                            onUndo = viewModel::undo,
                            onRedo = viewModel::redo,
                            onAddPage = viewModel::addPage,
                            onSearch = requestSearch,
                            onSelectPencil = { viewModel.selectTool(EditorTool.PENCIL) },
                            onFingerDrawing = viewModel::setFingerDrawing,
                            onExport = onExport,
                            onSettings = { requestClose(EditorCloseIntent.SETTINGS) },
                        )
                    } else {
                        EditorTopBar(
                            title = state.notebook?.title.orEmpty(),
                            failed = state.failed,
                            onBack = { requestClose(EditorCloseIntent.BACK) },
                            onAddPage = viewModel::addPage,
                            onSettings = { requestClose(EditorCloseIntent.SETTINGS) },
                            onExport = onExport,
                        )
                        HorizontalDivider()
                        EditorToolBar(
                            state = state,
                            onSelectTool = viewModel::selectTool,
                            onEraserMode = viewModel::setEraserMode,
                            onUndo = viewModel::undo,
                            onRedo = viewModel::redo,
                            onSearch = requestSearch,
                            onAddText = { textPageId = state.selectedPage?.id },
                            onAddImage = onAddImage,
                            onImportPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                            onCleanShape = { shapeDialogOpen = true },
                            onConvertHandwriting = {
                                viewModel.recognizeSelectedHandwriting(settings.recognitionLanguage)
                            },
                            settings = settings,
                            onUpdateSettings = onUpdateSettings,
                        )
                    }
                    if (state.selectedElement != null) {
                        HorizontalDivider()
                        ElementContextBar(
                            onDuplicate = viewModel::duplicateSelectedElement,
                            onBringForward = viewModel::bringSelectedElementForward,
                            onDelete = viewModel::deleteSelectedElement,
                        )
                    }
                    state.recognitionMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            },
            bottomBar = {
                if (compact) {
                    CompactEditorPalette(
                        state = state,
                        onSelectTool = viewModel::selectTool,
                        onEraserMode = viewModel::setEraserMode,
                        onAddText = { textPageId = state.selectedPage?.id },
                        onAddImage = onAddImage,
                        onImportPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                        onCleanShape = { shapeDialogOpen = true },
                        onConvertHandwriting = {
                            viewModel.recognizeSelectedHandwriting(settings.recognitionLanguage)
                        },
                        settings = settings,
                        onUpdateSettings = onUpdateSettings,
                    )
                }
            },
        ) { padding ->
            when {
                state.notebook == null || state.pages.isEmpty() -> {
                    Box(
                        Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.editor_loading))
                    }
                }
                windowClass == SeliaWindowClass.EXPANDED -> {
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
                            pageCount = state.pages.size,
                            strokes = state.selectedStrokes,
                            elements = state.selectedElements,
                            blocks = state.selectedBlocks,
                            selectedStrokeIds = state.selectedStrokeIds,
                            selectedElementId = state.selectedElementId,
                            smartShapePreviewId = state.smartShapePreviewId,
                            ocrSearchHighlight = state.ocrSearchHighlight,
                            fingerDrawing = state.notebook?.fingerDrawing == true,
                            tool = state.tool,
                            penWidth = settings.penWidth,
                            highlighterWidth = settings.highlighterWidth,
                            penColorArgb = settings.penColorArgb,
                            highlighterColorArgb = settings.highlighterColorArgb,
                            pageTransitionEnabled = settings.pageTransition,
                            onPreviousPage = viewModel::selectPreviousPage,
                            onNextPage = viewModel::selectNextPage,
                            onStrokeFinished = { pageId, stroke ->
                                viewModel.addStroke(
                                    pageId,
                                    stroke,
                                    settings.shapeAssist,
                                    settings.handwritingRecognition,
                                    settings.recognitionLanguage,
                                )
                            },
                            onEraseFinished = viewModel::eraseStrokes,
                            onSelectContent = viewModel::selectContent,
                            onMoveSelection = viewModel::moveSelectedStrokes,
                            onPageTextChanged = viewModel::updatePageText,
                            onCommitElementTransform = viewModel::updateSelectedElement,
                            onSelectElement = viewModel::selectElement,
                            assetFile = viewModel::assetFile,
                            onPageTextDraftChanged = sessionHolder::acceptDraft,
                            initialPageTextDraft = sessionHolder.draftFor(state.selectedPage?.id),
                            pageTextInputEnabled = !closeState.closing,
                            loadPdfPage = viewModel::renderPdfPage,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
                else -> {
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (windowClass == SeliaWindowClass.MEDIUM) {
                            PageLocationBar(
                                state = state,
                                onOpenContents = { contentsOpen = true },
                                onBookmarkPage = viewModel::setPageBookmarked,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                            )
                            HorizontalDivider()
                        }
                        PageCanvas(
                            page = state.selectedPage,
                            pageNumber = state.pages.indexOf(state.selectedPage) + 1,
                            pageCount = state.pages.size,
                            strokes = state.selectedStrokes,
                            elements = state.selectedElements,
                            blocks = state.selectedBlocks,
                            selectedStrokeIds = state.selectedStrokeIds,
                            selectedElementId = state.selectedElementId,
                            smartShapePreviewId = state.smartShapePreviewId,
                            ocrSearchHighlight = state.ocrSearchHighlight,
                            fingerDrawing = state.notebook?.fingerDrawing == true,
                            tool = state.tool,
                            penWidth = settings.penWidth,
                            highlighterWidth = settings.highlighterWidth,
                            penColorArgb = settings.penColorArgb,
                            highlighterColorArgb = settings.highlighterColorArgb,
                            pageTransitionEnabled = settings.pageTransition,
                            onPreviousPage = viewModel::selectPreviousPage,
                            onNextPage = viewModel::selectNextPage,
                            onStrokeFinished = { pageId, stroke ->
                                viewModel.addStroke(
                                    pageId,
                                    stroke,
                                    settings.shapeAssist,
                                    settings.handwritingRecognition,
                                    settings.recognitionLanguage,
                                )
                            },
                            onEraseFinished = viewModel::eraseStrokes,
                            onSelectContent = viewModel::selectContent,
                            onMoveSelection = viewModel::moveSelectedStrokes,
                            onPageTextChanged = viewModel::updatePageText,
                            onCommitElementTransform = viewModel::updateSelectedElement,
                            onSelectElement = viewModel::selectElement,
                            assetFile = viewModel::assetFile,
                            onPageTextDraftChanged = sessionHolder::acceptDraft,
                            initialPageTextDraft = sessionHolder.draftFor(state.selectedPage?.id),
                            pageTextInputEnabled = !closeState.closing,
                            loadPdfPage = viewModel::renderPdfPage,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CompactEditorTopBar(
    state: EditorUiState,
    onBack: () -> Unit,
    onOpenContents: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddPage: () -> Unit,
    onSearch: () -> Unit,
    onSelectPencil: () -> Unit,
    onFingerDrawing: (Boolean) -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val largeFont = LocalDensity.current.fontScale >= 1.5f
    val page = state.selectedPage
    val title = state.notebook?.title.orEmpty()
    val position =
        page?.let { stringResource(R.string.page_of_pages, it.pageIndex + 1, state.pages.size) }.orEmpty()
    val locationDescription = if (position.isEmpty()) title else "$title, $position"
    val backDescription = stringResource(R.string.back)
    val undoDescription = stringResource(R.string.undo)
    val redoDescription = stringResource(R.string.redo)
    val moreDescription = stringResource(R.string.more_options)
    Column {
        TopAppBar(
            modifier = Modifier.testTag("editor-top-bar"),
            title = {
                TextButton(
                    onClick = onOpenContents,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("compact-page-location")
                            .semantics { contentDescription = locationDescription },
                ) {
                    if (largeFont) {
                        Text(
                            text = position.ifEmpty { title },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().testTag("editor-top-bar-title"),
                        )
                    } else {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("editor-top-bar-title"),
                            )
                            Text(
                                text = position,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp).testTag("compact-back"),
                ) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = backDescription)
                }
            },
            actions = {
                if (!largeFont) {
                    IconButton(
                        onClick = onUndo,
                        enabled = state.canUndo,
                        modifier = Modifier.size(48.dp).testTag("compact-undo"),
                    ) {
                        Icon(painterResource(R.drawable.ic_undo), contentDescription = undoDescription)
                    }
                    IconButton(
                        onClick = onRedo,
                        enabled = state.canRedo,
                        modifier = Modifier.size(48.dp).testTag("compact-redo"),
                    ) {
                        Icon(painterResource(R.drawable.ic_redo), contentDescription = redoDescription)
                    }
                }
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(48.dp).testTag("compact-more"),
                    ) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = moreDescription)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (largeFont) {
                            CompactMenuItem(
                                stringResource(R.string.undo),
                                "compact-more-undo",
                                enabled = state.canUndo,
                            ) {
                                menuOpen = false
                                onUndo()
                            }
                            CompactMenuItem(
                                stringResource(R.string.redo),
                                "compact-more-redo",
                                enabled = state.canRedo,
                            ) {
                                menuOpen = false
                                onRedo()
                            }
                        }
                        CompactMenuItem(stringResource(R.string.add_page), "compact-more-add-page") {
                            menuOpen = false
                            onAddPage()
                        }
                        CompactMenuItem(stringResource(R.string.search), "compact-more-search") {
                            menuOpen = false
                            onSearch()
                        }
                        CompactMenuItem(stringResource(R.string.tool_pencil), "compact-more-pencil") {
                            menuOpen = false
                            onSelectPencil()
                        }
                        val fingerDrawing = state.notebook?.fingerDrawing == true
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.draw_with_finger)) },
                            trailingIcon = { Switch(checked = fingerDrawing, onCheckedChange = null) },
                            onClick = {
                                menuOpen = false
                                onFingerDrawing(!fingerDrawing)
                            },
                            modifier =
                                Modifier.testTag("compact-more-finger-drawing").semantics {
                                    role = Role.Switch
                                    toggleableState =
                                        if (fingerDrawing) ToggleableState.On else ToggleableState.Off
                                },
                        )
                        CompactMenuItem(stringResource(R.string.export_pdf), "compact-more-export") {
                            menuOpen = false
                            onExport()
                        }
                        CompactMenuItem(stringResource(R.string.settings), "compact-more-settings") {
                            menuOpen = false
                            onSettings()
                        }
                    }
                }
            },
        )
        if (state.failed) {
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

@Composable
internal fun CompactEditorPalette(
    state: EditorUiState,
    onSelectTool: (EditorTool) -> Unit,
    onEraserMode: (EraserMode) -> Unit,
    onAddText: () -> Unit,
    onAddImage: () -> Unit,
    onImportPdf: () -> Unit,
    onCleanShape: () -> Unit,
    onConvertHandwriting: () -> Unit = {},
    settings: AppSettings = AppSettings(),
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit = {},
    contentInsets: WindowInsets =
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
) {
    var insertOpen by rememberSaveable { mutableStateOf(false) }
    var eraserMenuOpen by rememberSaveable { mutableStateOf(false) }
    val selectedState = stringResource(R.string.selected)
    val notSelectedState = stringResource(R.string.not_selected)
    val tools =
        listOf(
            EditorTool.TYPE to "type",
            EditorTool.PEN to "pen",
            EditorTool.HIGHLIGHTER to "highlighter",
            EditorTool.ERASER to "eraser",
            EditorTool.LASSO to "lasso",
        )
    Surface(Modifier.testTag("compact-palette")) {
        Column {
            HorizontalDivider()
            if (state.tool == EditorTool.PEN || state.tool == EditorTool.PENCIL || state.tool == EditorTool.HIGHLIGHTER) {
                BrushOptions(
                    tool = state.tool,
                    settings = settings,
                    onUpdate = onUpdateSettings,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(contentInsets.only(WindowInsetsSides.Horizontal))
                            .horizontalScroll(rememberScrollState()),
                )
                HorizontalDivider()
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(contentInsets),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tools.forEach { (tool, tag) ->
                    val selectedTool =
                        state.tool == tool ||
                            (tool == EditorTool.PEN && state.tool == EditorTool.PENCIL)
                    val eraserState =
                        if (tool == EditorTool.ERASER) eraserModeLabel(state.eraserMode) else null
                    Box(Modifier.weight(1f)) {
                        Surface(
                            onClick = {
                                if (tool == EditorTool.ERASER) {
                                    eraserMenuOpen = true
                                } else {
                                    onSelectTool(tool)
                                }
                            },
                            color =
                                if (selectedTool) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                },
                            shape = RoundedCornerShape(10.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("compact-tool-$tag")
                                    .semantics {
                                        selected = selectedTool
                                        eraserState?.let { stateDescription = it }
                                    },
                        ) {
                            Box(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(toolIcon(tool)),
                                    contentDescription = toolLabel(tool),
                                    tint =
                                        if (selectedTool) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }
                        }
                        if (tool == EditorTool.ERASER) {
                            DropdownMenu(
                                expanded = eraserMenuOpen,
                                onDismissRequest = { eraserMenuOpen = false },
                            ) {
                                EraserMode.entries.forEach { mode ->
                                    val modeSelected = state.eraserMode == mode
                                    val modeTag = if (mode == EraserMode.SEGMENT) "segment" else "stroke"
                                    DropdownMenuItem(
                                        text = { Text(eraserModeLabel(mode)) },
                                        onClick = {
                                            eraserMenuOpen = false
                                            onSelectTool(EditorTool.ERASER)
                                            onEraserMode(mode)
                                        },
                                        modifier =
                                            Modifier
                                                .testTag("compact-eraser-$modeTag")
                                                .semantics {
                                                    selected = modeSelected
                                                    stateDescription =
                                                        if (modeSelected) selectedState else notSelectedState
                                                },
                                    )
                                }
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    Surface(
                        onClick = { insertOpen = true },
                        color = Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("compact-insert"),
                    ) {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = stringResource(R.string.insert),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    DropdownMenu(expanded = insertOpen, onDismissRequest = { insertOpen = false }) {
                        CompactMenuItem(stringResource(R.string.text_object), "compact-insert-text") {
                            insertOpen = false
                            onAddText()
                        }
                        CompactMenuItem(stringResource(R.string.tool_image), "compact-insert-image") {
                            insertOpen = false
                            onAddImage()
                        }
                        CompactMenuItem(stringResource(R.string.import_pdf), "compact-insert-pdf") {
                            insertOpen = false
                            onImportPdf()
                        }
                        if (state.selectedStrokeIds.isNotEmpty()) {
                            CompactMenuItem(
                                stringResource(R.string.convert_handwriting),
                                "compact-insert-convert",
                            ) {
                                insertOpen = false
                                onConvertHandwriting()
                            }
                            CompactMenuItem(stringResource(R.string.tool_shape), "compact-insert-shape") {
                                insertOpen = false
                                onCleanShape()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactMenuItem(
    label: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun BrushOptions(
    tool: EditorTool,
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlighter = tool == EditorTool.HIGHLIGHTER
    val currentWidth = if (highlighter) settings.highlighterWidth else settings.penWidth
    val currentColor = if (highlighter) settings.highlighterColorArgb else settings.penColorArgb
    val widthRange = if (highlighter) HIGHLIGHTER_WIDTH_RANGE else PEN_WIDTH_RANGE
    val widthLabel = stringResource(if (highlighter) R.string.highlighter_width else R.string.pen_width)
    val colors =
        if (highlighter) {
            listOf(
                Triple("yellow", R.string.brush_color_yellow, 0x66FFD54F),
                Triple("pink", R.string.brush_color_pink, 0x66F48FB1),
                Triple("green", R.string.brush_color_green, 0x6681C784),
                Triple("blue", R.string.brush_color_blue, 0x6664B5F6),
            )
        } else {
            listOf(
                Triple("black", R.string.brush_color_black, 0xFF202124.toInt()),
                Triple("blue", R.string.brush_color_blue, 0xFF3156D9.toInt()),
                Triple("red", R.string.brush_color_red, 0xFFD93F3F.toInt()),
                Triple("green", R.string.brush_color_green, 0xFF2E7D32.toInt()),
            )
        }
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrushWidthControl(
            label = widthLabel,
            value = currentWidth,
            range = widthRange,
            color = Color(currentColor),
        ) { width ->
            onUpdate { current ->
                if (highlighter) {
                    current.copy(highlighterWidth = width)
                } else {
                    current.copy(penWidth = width)
                }
            }
        }
        colors.forEach { (tag, labelResource, colorArgb) ->
            val selectedColor = currentColor == colorArgb
            val label = stringResource(labelResource)
            Surface(
                onClick = {
                    onUpdate { current ->
                        if (highlighter) {
                            current.copy(highlighterColorArgb = colorArgb)
                        } else {
                            current.copy(penColorArgb = colorArgb)
                        }
                    }
                },
                color = Color.Transparent,
                border = BorderStroke(if (selectedColor) 2.dp else 1.dp, if (selectedColor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                shape = CircleShape,
                modifier =
                    Modifier
                        .size(48.dp)
                        .testTag("brush-color-$tag")
                        .semantics {
                            selected = selectedColor
                            contentDescription = label
                        },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Surface(color = Color(colorArgb), shape = CircleShape, modifier = Modifier.size(24.dp)) {}
                }
            }
        }
        val smartShapesLabel = stringResource(R.string.smart_shapes)
        Surface(
            onClick = { onUpdate { current -> current.copy(shapeAssist = !current.shapeAssist) } },
            color = if (settings.shapeAssist) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(12.dp),
            modifier =
                Modifier
                    .heightIn(min = 48.dp)
                    .testTag("brush-shape-assist")
                    .semantics {
                        role = Role.Switch
                        toggleableState = if (settings.shapeAssist) ToggleableState.On else ToggleableState.Off
                        contentDescription = smartShapesLabel
                    },
        ) {
            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(smartShapesLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun BrushWidthControl(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    color: Color,
    onChange: (Float) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    val description = stringResource(R.string.setting_value, label, sliderValue.roundToInt())
    Column(
        Modifier.width(196.dp).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(description, style = MaterialTheme.typography.labelMedium)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .testTag("brush-width-preview")
                .semantics { contentDescription = description },
        ) {
            val progress = (sliderValue - range.start) / (range.endInclusive - range.start)
            val strokeWidth = 2.dp.toPx() + progress * (size.height - 4.dp.toPx())
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(8.dp.toPx(), size.height / 2f),
                end = androidx.compose.ui.geometry.Offset(size.width - 8.dp.toPx(), size.height / 2f),
                strokeWidth = strokeWidth,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onChange(sliderValue) },
            valueRange = range,
            modifier = Modifier.fillMaxWidth().testTag("brush-width-slider"),
        )
    }
}

@Composable
internal fun EditorToolBar(
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
    onConvertHandwriting: () -> Unit = {},
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
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
                color =
                    if (state.tool == tool) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                },
                shape = RoundedCornerShape(10.dp),
                modifier =
                    Modifier
                        .testTag("toolbar-tool-${tool.name.lowercase()}")
                        .selectable(
                            selected = state.tool == tool,
                            onClick = { onSelectTool(tool) },
                            role = Role.RadioButton,
                        ),
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
        if (state.tool == EditorTool.PEN || state.tool == EditorTool.PENCIL || state.tool == EditorTool.HIGHLIGHTER) {
            VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
            BrushOptions(state.tool, settings, onUpdateSettings)
        }
        if (state.tool == EditorTool.ERASER) {
            VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
            EraserMode.entries.forEach { mode ->
                Surface(
                    color =
                        if (state.eraserMode == mode) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier =
                        Modifier
                            .testTag(
                                if (mode == EraserMode.SEGMENT) {
                                    "toolbar-eraser-segment"
                                } else {
                                    "toolbar-eraser-stroke"
                                },
                            )
                            .selectable(
                                selected = state.eraserMode == mode,
                                onClick = { onEraserMode(mode) },
                                role = Role.RadioButton,
                            ),
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
        TextButton(onClick = onConvertHandwriting, enabled = state.selectedStrokeIds.isNotEmpty()) {
            Text(stringResource(R.string.convert_handwriting))
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
    onSelect: (PageTextMatch) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(state.searchQuery) }
    var closing by remember { mutableStateOf(false) }
    val visibleResults = if (state.searchQuery == query) state.searchResults else emptyList()
    LaunchedEffect(query) {
        delay(250)
        if (!closing) onQuery(query)
    }
    AlertDialog(
        onDismissRequest = {
            closing = true
            onDismiss()
        },
        title = { Text(stringResource(R.string.search_notebook)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(256) },
                    label = { Text(stringResource(R.string.search_page_text)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("search-query"),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    itemsIndexed(visibleResults, key = { _, result -> result.pageId }) { _, result ->
                        TextButton(
                            onClick = {
                                closing = true
                                onSelect(result)
                            },
                            modifier = Modifier.fillMaxWidth().testTag("search-result-${result.pageIndex}"),
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
                if (query.isNotBlank() && state.searchQuery == query) {
                    when {
                        state.searchFailed ->
                            Text(stringResource(R.string.search_failed), style = MaterialTheme.typography.bodyMedium)
                        visibleResults.isEmpty() ->
                            Text(
                                stringResource(R.string.no_search_results),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    closing = true
                    onDismiss()
                },
            ) { Text(stringResource(R.string.close)) }
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
internal fun MathCandidatesDialog(
    candidates: List<InkMathCandidate>,
    onSelect: (InkMathCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_math_result)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                candidates.forEachIndexed { index, candidate ->
                    TextButton(
                        onClick = { onSelect(candidate) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("math-candidate-$index"),
                    ) {
                        Text(
                            "${candidate.expression.dropLast(1).trim()} = ${candidate.result}",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("math-candidates-dismiss"),
            ) {
                Text(stringResource(R.string.dismiss))
            }
        },
    )
}

@Composable
internal fun HandwritingCandidatesDialog(
    candidates: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.convert_handwriting)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.convert_handwriting_hint))
                candidates.forEachIndexed { index, candidate ->
                    TextButton(
                        onClick = { onSelect(candidate) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("handwriting-candidate-$index"),
                    ) {
                        Text(candidate, modifier = Modifier.fillMaxWidth())
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

private fun toolIcon(tool: EditorTool): Int =
    when (tool) {
        EditorTool.TYPE -> R.drawable.ic_text_fields
        EditorTool.PEN, EditorTool.PENCIL -> R.drawable.ic_stylus
        EditorTool.HIGHLIGHTER -> R.drawable.ic_highlighter
        EditorTool.ERASER -> R.drawable.ic_eraser
        EditorTool.LASSO -> R.drawable.ic_lasso_select
    }

@Composable
private fun eraserModeLabel(mode: EraserMode): String =
    stringResource(
        when (mode) {
            EraserMode.SEGMENT -> R.string.eraser_segment
            EraserMode.STROKE -> R.string.eraser_stroke
        },
    )

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun EditorTopBar(
    title: String,
    failed: Boolean,
    onBack: () -> Unit,
    onAddPage: () -> Unit,
    onSettings: () -> Unit,
    onExport: () -> Unit,
) {
    val backDescription = stringResource(R.string.back)
    val addDescription = stringResource(R.string.add_page)
    val moreDescription = stringResource(R.string.more_options)
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Column {
        TopAppBar(
            modifier = Modifier.testTag("editor-top-bar"),
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("editor-top-bar-title"),
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = backDescription)
                }
            },
            actions = {
                IconButton(
                    onClick = onAddPage,
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = addDescription)
                }
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                    ) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = moreDescription)
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
            },
        )
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

private fun safeFileName(value: String): String =
    value.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(100).ifBlank { "SeliaSheets notebook" }
