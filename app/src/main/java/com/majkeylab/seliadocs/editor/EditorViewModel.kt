package com.majkeylab.seliadocs.editor

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.ink.strokes.Stroke
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.BlockEntity
import com.majkeylab.seliadocs.data.ChapterEntity
import com.majkeylab.seliadocs.data.ElementDraft
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.NotebookEntity
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PageTextMatch
import com.majkeylab.seliadocs.data.PdfSourceEntity
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.data.StrokeEntity
import com.majkeylab.seliadocs.data.StrokePayload
import com.majkeylab.seliadocs.pdf.PdfImporter
import com.majkeylab.seliadocs.pdf.PdfSandboxClient
import com.majkeylab.seliadocs.recognition.ImageOcrResult
import com.majkeylab.seliadocs.recognition.InkMathCandidate
import com.majkeylab.seliadocs.recognition.InkMathDecision
import com.majkeylab.seliadocs.recognition.InkTextRecognizer
import com.majkeylab.seliadocs.recognition.RecognitionFingerprint
import com.majkeylab.seliadocs.recognition.RecognitionLanguage
import com.majkeylab.seliadocs.recognition.RecognitionPoint
import com.majkeylab.seliadocs.recognition.RecognitionRequest
import com.majkeylab.seliadocs.recognition.RecognitionStroke
import com.majkeylab.seliadocs.recognition.boundedRecognitionRequest
import com.majkeylab.seliadocs.recognition.decodeImageOcrRegions
import com.majkeylab.seliadocs.recognition.decideInkMath
import com.majkeylab.seliadocs.recognition.encodeImageOcrRegions
import com.majkeylab.seliadocs.recognition.recognizeImage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class EditorUiState(
    val notebook: NotebookEntity? = null,
    val pages: List<PageEntity> = emptyList(),
    val chapters: List<ChapterEntity> = emptyList(),
    val pdfSources: List<PdfSourceEntity> = emptyList(),
    val strokes: List<StrokeEntity> = emptyList(),
    val elements: List<ElementEntity> = emptyList(),
    val blocks: List<BlockEntity> = emptyList(),
    val selectedPageId: String? = null,
    val tool: EditorTool = EditorTool.PEN,
    val selectedStrokeIds: Set<String> = emptySet(),
    val selectedElementId: String? = null,
    val eraserMode: EraserMode = EraserMode.SEGMENT,
    val smartShapePreviewId: String? = null,
    val searchQuery: String = "",
    val searchResults: List<PageTextMatch> = emptyList(),
    val searchFailed: Boolean = false,
    val ocrSearchHighlight: OcrSearchHighlight? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val failed: Boolean = false,
    val recognitionMessage: String? = null,
    val ambiguousMathCandidates: List<InkMathCandidate> = emptyList(),
    val handwritingCandidates: List<String> = emptyList(),
) {
    val selectedPage: PageEntity?
        get() = pages.firstOrNull { it.id == selectedPageId } ?: pages.firstOrNull()

    val selectedStrokes: List<StrokeEntity>
        get() = strokes.filter { it.pageId == selectedPage?.id }

    val selectedElements: List<ElementEntity>
        get() = elements.filter { it.pageId == selectedPage?.id }

    val selectedBlocks: List<BlockEntity>
        get() = blocks.filter { it.pageId == selectedPage?.id }

    val selectedElement: ElementEntity?
        get() = elements.firstOrNull { it.id == selectedElementId }

    val selectedPdfSource: PdfSourceEntity?
        get() = pdfSources.firstOrNull { it.id == selectedPage?.pdfSourceId }
}

internal data class OcrSearchHighlight(val elementId: String, val query: String)

private data class EditorContent(
    val pages: List<PageEntity>,
    val strokes: List<StrokeEntity>,
    val elements: List<ElementEntity>,
    val blocks: List<BlockEntity>,
)

private data class EditorStructure(
    val chapters: List<ChapterEntity>,
    val pdfSources: List<PdfSourceEntity>,
)

private data class PageSnapshot(
    val strokes: List<StrokeEntity>,
    val elements: List<ElementEntity>,
    val blocks: List<BlockEntity>,
)

internal fun estimatePageSnapshotWeight(
    strokes: List<StrokeEntity>,
    elements: List<ElementEntity>,
    blocks: List<BlockEntity>,
): Int {
    val fixedBytes =
        (strokes.size.toLong() + elements.size + blocks.size) * PAGE_HISTORY_ENTITY_OVERHEAD_BYTES
    val strokeBytes =
        strokes.sumOf { stroke ->
            stroke.inputs.size.toLong() +
                stroke.id.estimatedBytes() +
                stroke.pageId.estimatedBytes() +
                stroke.brushKind.estimatedBytes()
        }
    val elementBytes =
        elements.sumOf { element ->
            element.id.estimatedBytes() +
                element.pageId.estimatedBytes() +
                element.kind.estimatedBytes() +
                element.text.estimatedBytes() +
                element.assetId.estimatedBytes() +
                element.shapeKind.estimatedBytes() +
                element.expression.estimatedBytes() +
                element.resultText.estimatedBytes() +
                element.ocrRegions.estimatedBytes()
        }
    val blockBytes =
        blocks.sumOf { block ->
            block.id.estimatedBytes() +
                block.pageId.estimatedBytes() +
                block.kind.estimatedBytes() +
                block.text.estimatedBytes() +
                block.alignment.estimatedBytes() +
                block.payloadId.estimatedBytes()
        }
    return saturatePageSnapshotWeight(fixedBytes + strokeBytes + elementBytes + blockBytes)
}

internal fun saturatePageSnapshotWeight(bytes: Long): Int {
    require(bytes >= 0)
    return bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private fun String?.estimatedBytes(): Long = orEmpty().length * PAGE_HISTORY_BYTES_PER_CHAR

private const val PAGE_HISTORY_BYTES_PER_CHAR = 2L
private const val PAGE_HISTORY_ENTITY_OVERHEAD_BYTES = 64L

internal data class PagePreviewData(
    val strokes: List<StrokeEntity>,
    val elements: List<ElementEntity>,
    val blocks: List<BlockEntity>,
    val pdfBackground: ImageBitmap? = null,
)

private data class EditorControls(
    val tool: EditorTool = EditorTool.PEN,
    val selectedStrokeIds: Set<String> = emptySet(),
    val selectedElementId: String? = null,
    val eraserMode: EraserMode = EraserMode.SEGMENT,
    val smartShapePreviewId: String? = null,
    val searchQuery: String = "",
    val searchResults: List<PageTextMatch> = emptyList(),
    val searchFailed: Boolean = false,
    val ocrSearchHighlight: OcrSearchHighlight? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val failed: Boolean = false,
    val recognitionMessage: String? = null,
    val ambiguousMathCandidates: List<InkMathCandidate> = emptyList(),
    val handwritingCandidates: List<String> = emptyList(),
)

private data class RecognitionSource(
    val id: String,
    val brushKind: String,
    val colorArgb: Int,
    val size: Float,
    val epsilon: Float,
    val inputs: ByteArray,
) {
    fun matches(stroke: StrokeEntity): Boolean =
        id == stroke.id &&
            brushKind == stroke.brushKind &&
            colorArgb == stroke.colorArgb &&
            size == stroke.size &&
            epsilon == stroke.epsilon &&
            inputs.contentEquals(stroke.inputs)
}

private data class RecognitionBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

private data class CapturedRecognition(
    val generation: Long,
    val language: RecognitionLanguage,
    val request: RecognitionRequest,
    val sources: List<RecognitionSource>,
    val bounds: RecognitionBounds,
)

private data class RecognitionPayload(
    val request: RecognitionRequest,
    val bounds: RecognitionBounds,
)

private data class PendingMathAmbiguity(
    val capture: CapturedRecognition,
    val candidates: List<InkMathCandidate>,
)

private data class PendingHandwritingConversion(
    val pageId: String,
    val sources: List<RecognitionSource>,
    val candidates: List<String>,
)

private data class CapturedHandwritingConversion(
    val generation: Long,
    val pageId: String,
    val tool: EditorTool,
    val selectedIds: Set<String>,
    val request: RecognitionRequest,
    val sources: List<RecognitionSource>,
)

private data class RecognitionBurstSession(
    val pageId: String,
    val language: RecognitionLanguage,
    val tool: EditorTool,
    val epoch: Long,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class EditorViewModel(
    application: Application,
    private val notebookId: String,
    initialTool: EditorTool = EditorTool.PEN,
    private val mutationAllowed: () -> Boolean = { true },
    private val recognizerProvider: suspend (RecognitionLanguage) -> InkTextRecognizer = {
        error("Handwriting recognizer is not configured.")
    },
    private val recognitionDelay: suspend () -> Unit = { delay(RECOGNITION_DEBOUNCE_MS) },
    private val recognitionWriteBoundary: suspend () -> Unit = {},
    private val recognitionCommitBoundary: suspend () -> Unit = {},
    private val imageOcrRecognizer: suspend (File) -> ImageOcrResult = ::recognizeImage,
) :
    AndroidViewModel(application) {
    private val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
    private val assets = AssetStore(File(application.filesDir, "assets"))
    private val imageImporter = ImageImporter(application.contentResolver, assets)
    private val pdfSandbox = PdfSandboxClient(application)
    private val pdfImporter = PdfImporter(application.contentResolver, assets, repository, pdfSandbox)
    private val selectedPageId = MutableStateFlow<String?>(null)
    private val controls = MutableStateFlow(EditorControls(tool = initialTool))
    private val pendingRecognitionStrokeIds = mutableListOf<String>()
    private var pendingRecognitionPageId: String? = null
    private var pendingRecognitionLanguage: RecognitionLanguage? = null
    private var recognitionJob: Job? = null
    private var candidateChoiceJob: Job? = null
    private var searchJob: Job? = null
    private val imageOcrInFlight = mutableSetOf<String>()
    private var imageOcrFeedback: Pair<String, Long>? = null
    private var latestSearchQuery = ""
    private var latestSearchIncludesImageOcr = true
    private var recognitionGeneration = 0L
    private var recognitionInvalidationEpoch = 0L
    private var appliedRecognitionGeneration: Long? = null
    private var recognitionCommitInProgress = false
    private var pendingMathAmbiguity: PendingMathAmbiguity? = null
    private var pendingHandwritingConversion: PendingHandwritingConversion? = null
    private var handwritingConversionJob: Job? = null
    private var recognitionBurstSession: RecognitionBurstSession? = null
    private val pageHistories =
        PageHistoryStore<PageSnapshot>(
            maxPages = PAGE_HISTORY_MAX_PAGES,
            maxWeight = PAGE_HISTORY_MAX_BYTES,
            weightOf = { snapshot ->
                estimatePageSnapshotWeight(snapshot.strokes, snapshot.elements, snapshot.blocks)
            },
        )
    private val pages =
        repository.observePages(notebookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val chapters =
        repository.observeChapters(notebookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val pdfSources =
        repository.observePdfSources(notebookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val structure = combine(chapters, pdfSources, ::EditorStructure)
    private val effectiveSelectedPageId =
        combine(pages, selectedPageId) { notebookPages, selected ->
                selected?.takeIf { id -> notebookPages.any { it.id == id } }
                    ?: notebookPages.firstOrNull()?.id
            }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val selectedPageContent =
        effectiveSelectedPageId.flatMapLatest { pageId ->
            if (pageId == null) {
                flowOf(EditorContent(emptyList(), emptyList(), emptyList(), emptyList()))
            } else {
                combine(
                    repository.observeStrokes(pageId),
                    repository.observeElements(pageId),
                    repository.observeBlocks(pageId),
                ) { strokes, elements, blocks -> EditorContent(emptyList(), strokes, elements, blocks) }
            }
        }
    private val content =
        combine(pages, selectedPageContent) { notebookPages, pageContent ->
            pageContent.copy(pages = notebookPages)
        }

    val state =
        combine(
                repository.observeNotebook(notebookId),
                content,
                effectiveSelectedPageId,
                structure,
                controls,
            ) { notebook, document, selected, notebookStructure, editorControls ->
                EditorUiState(
                    notebook = notebook,
                    pages = document.pages,
                    chapters = notebookStructure.chapters,
                    pdfSources = notebookStructure.pdfSources,
                    strokes = document.strokes,
                    elements = document.elements,
                    blocks = document.blocks,
                    selectedPageId = selected,
                    tool = editorControls.tool,
                    selectedStrokeIds = editorControls.selectedStrokeIds,
                    selectedElementId = editorControls.selectedElementId,
                    eraserMode = editorControls.eraserMode,
                    smartShapePreviewId = editorControls.smartShapePreviewId,
                    searchQuery = editorControls.searchQuery,
                    searchResults = editorControls.searchResults,
                    searchFailed = editorControls.searchFailed,
                    ocrSearchHighlight = editorControls.ocrSearchHighlight,
                    canUndo = editorControls.canUndo,
                    canRedo = editorControls.canRedo,
                    failed = editorControls.failed,
                    recognitionMessage = editorControls.recognitionMessage,
                    ambiguousMathCandidates = editorControls.ambiguousMathCandidates,
                    handwritingCandidates = editorControls.handwritingCandidates,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    fun selectPage(id: String) {
        controls.value = controls.value.copy(ocrSearchHighlight = null)
        if (state.value.selectedPage?.id == id) return
        clearRecognition()
        selectedPageId.value = id
        showHistoryControls(id)
    }

    fun selectPreviousPage() {
        val index = state.value.pages.indexOf(state.value.selectedPage)
        state.value.pages.getOrNull(index - 1)?.let { selectPage(it.id) }
    }

    fun selectNextPage() {
        val index = state.value.pages.indexOf(state.value.selectedPage)
        state.value.pages.getOrNull(index + 1)?.let { selectPage(it.id) }
    }

    fun setFingerDrawing(enabled: Boolean) = mutate {
        repository.setFingerDrawing(notebookId, enabled)
    }

    fun selectTool(tool: EditorTool) {
        if (controls.value.tool != tool) clearRecognition()
        controls.value =
            controls.value.copy(
                tool = tool,
                selectedStrokeIds = if (tool == EditorTool.LASSO) controls.value.selectedStrokeIds else emptySet(),
                selectedElementId = if (tool == EditorTool.LASSO) controls.value.selectedElementId else null,
                ocrSearchHighlight = null,
            )
    }

    fun addPage() = mutate {
        val id = repository.addPage(notebookId)
        selectedPageId.value = id
        showHistoryControls(id)
    }

    fun setEraserMode(mode: EraserMode) {
        if (controls.value.eraserMode != mode) clearRecognition()
        controls.value = controls.value.copy(eraserMode = mode)
    }

    fun createChapter(title: String) = mutate {
        repository.createChapter(notebookId, title, DEFAULT_CHAPTER_COLOR)
    }

    fun deleteChapter(id: String) = mutate {
        repository.deleteChapter(id)
    }

    fun renamePage(pageId: String, title: String?) = mutate {
        repository.renamePage(pageId, title)
    }

    fun setPageBookmarked(pageId: String, bookmarked: Boolean) = mutate {
        repository.setPageBookmarked(pageId, bookmarked)
    }

    fun assignPageToChapter(pageId: String, chapterId: String?) = mutate {
        repository.assignPageToChapter(pageId, chapterId)
    }

    fun duplicatePage(id: String) = mutate {
        val duplicateId = repository.duplicatePage(id)
        selectedPageId.value = duplicateId
        showHistoryControls(duplicateId)
    }

    fun deletePage(id: String) = mutate {
        val wasSelected = state.value.selectedPage?.id == id
        val assetIds = repository.getElements(id).mapNotNull { it.assetId }.distinct()
        val orphanedPdfAsset = repository.deletePage(id)
        assetIds.forEach { assetId ->
            if (repository.getAssetReferenceCount(assetId) == 0) {
                val file = assets.file(assetId)
                check(!file.exists() || file.delete()) { "Asset could not be deleted" }
            }
        }
        pageHistories.remove(id)
        orphanedPdfAsset?.let { assetId ->
            val file = assets.file(assetId)
            check(!file.exists() || file.delete()) { "PDF asset could not be deleted" }
        }
        if (wasSelected) showHistoryControls(null)
    }

    fun movePage(fromIndex: Int, toIndex: Int) = mutate {
        repository.movePage(notebookId, fromIndex, toIndex)
    }

    fun addStroke(
        pageId: String,
        stroke: Stroke,
        shapeAssist: Boolean = true,
        handwritingRecognition: Boolean = false,
        recognitionLanguage: RecognitionLanguage = RecognitionLanguage.CZECH,
    ) {
        val encoded = InkCodec.encode(stroke)
        val toolAtFinish =
            when (encoded.brushKind) {
                BrushKind.PENCIL -> EditorTool.PENCIL
                BrushKind.HIGHLIGHTER -> EditorTool.HIGHLIGHTER
                BrushKind.PRESSURE_PEN, BrushKind.MARKER -> EditorTool.PEN
            }
        val recognitionEligible =
            handwritingRecognition &&
                (toolAtFinish == EditorTool.PEN || toolAtFinish == EditorTool.PENCIL)
        val callbackEpoch =
            if (recognitionEligible) {
                if (recognitionCommitInProgress) {
                    null
                } else {
                    invalidateRecognitionForNewInk(pageId, recognitionLanguage, toolAtFinish)
                }
            } else {
                clearRecognition()
                recognitionInvalidationEpoch
            }
        mutate(cancelRecognition = false) {
            val effectiveCallbackEpoch =
                callbackEpoch ?: invalidateRecognitionForNewInk(pageId, recognitionLanguage, toolAtFinish)
            val history = history(pageId)
            val page = requireNotNull(state.value.pages.firstOrNull { it.id == pageId })
            val recognition =
                if (shapeAssist && (toolAtFinish == EditorTool.PEN || toolAtFinish == EditorTool.PENCIL)) {
                    withContext(Dispatchers.Default) {
                        recognizeHeldShape(
                            samples =
                                (0 until stroke.inputs.size).map { index ->
                                    val input = stroke.inputs[index]
                                    TimedCanvasPoint(
                                        CanvasPoint(input.x, input.y),
                                        input.elapsedTimeMillis,
                                    )
                                },
                            pageWidth = page.widthPoints.toFloat(),
                            pageHeight = page.heightPoints.toFloat(),
                        )
                    }
                } else {
                    null
                }
            val payload =
                StrokePayload(
                    brushKind = encoded.brushKind.name,
                    colorArgb = encoded.colorArgb,
                    size = encoded.size,
                    epsilon = encoded.epsilon,
                    inputs = encoded.inputs,
                )
            val rawStrokeId = repository.addStroke(pageId, payload)
            if (recognition != null) {
                history.push(snapshot(pageId))
                val id =
                    repository.replaceStrokesWithElement(
                        pageId,
                        setOf(rawStrokeId),
                        ElementDraft(
                            kind = ElementKind.SHAPE,
                            x = recognition.transform.x,
                            y = recognition.transform.y,
                            width = recognition.transform.width,
                            height = recognition.transform.height,
                            rotation = recognition.transform.rotation,
                            shapeKind = recognition.kind.name,
                        ),
                    )
                showSmartShapePreview(id)
            }
            history.push(snapshot(pageId))
            updateHistoryControls(history)
            if (
                recognition == null &&
                    recognitionEligible &&
                    effectiveCallbackEpoch == recognitionInvalidationEpoch &&
                    controls.value.tool == toolAtFinish
            ) {
                scheduleRecognition(
                    pageId,
                    rawStrokeId,
                    recognitionLanguage,
                    effectiveCallbackEpoch,
                    toolAtFinish,
                )
            } else if (recognition != null) {
                clearRecognition()
            }
        }
    }

    fun eraseStrokes(pageId: String, points: List<CanvasPoint>) = mutate {
        if (points.isEmpty()) return@mutate
        val history = history(pageId)
        val original = history.current.strokes
        val remainingIds =
            when (controls.value.eraserMode) {
                EraserMode.STROKE -> {
                    val ids =
                        withContext(Dispatchers.Default) {
                            original
                                .map(StrokeEntity::toStrokePath)
                                .filter { stroke -> hitStrokePath(points, ERASER_RADIUS, stroke) }
                                .mapTo(mutableSetOf(), StrokePath::id)
                        }
                    if (ids.isEmpty()) return@mutate
                    repository.deleteStrokes(pageId, ids)
                    original.asSequence().map(StrokeEntity::id).filterNot(ids::contains).toSet()
                }
                EraserMode.SEGMENT -> {
                    val updated =
                        withContext(Dispatchers.Default) {
                            original
                                .flatMap { stroke ->
                                    stroke.eraseSegments(points, ERASER_RADIUS) { UUID.randomUUID().toString() }
                                }
                                .mapIndexed { index, stroke -> stroke.copy(zIndex = index) }
                        }
                    if (updated.map(StrokeEntity::id) == original.map(StrokeEntity::id)) return@mutate
                    repository.replaceStrokes(pageId, updated)
                    updated.mapTo(mutableSetOf(), StrokeEntity::id)
                }
            }
        history.push(snapshot(pageId))
        controls.value = controls.value.copy(selectedStrokeIds = controls.value.selectedStrokeIds intersect remainingIds)
        updateHistoryControls(history)
    }

    fun selectStrokes(pageId: String, lasso: List<CanvasPoint>) {
        val strokes = state.value.strokes.filter { it.pageId == pageId }.map(StrokeEntity::toStrokePath)
        controls.value =
            controls.value.copy(
                selectedStrokeIds = selectStrokes(lasso, strokes),
                selectedElementId = null,
            )
    }

    fun selectContent(pageId: String, lasso: List<CanvasPoint>) {
        val elementId =
            selectElementWithLasso(
                lasso,
                state.value.elements.filter { it.pageId == pageId },
            )
        if (elementId != null) {
            selectElement(elementId)
        } else {
            selectStrokes(pageId, lasso)
        }
    }

    fun selectElement(id: String?) {
        if (controls.value.selectedElementId != id && imageOcrFeedback != null) {
            imageOcrFeedback = null
            controls.value = controls.value.copy(recognitionMessage = null)
        }
        controls.value =
            controls.value.copy(
                selectedElementId = id?.takeIf { candidate ->
                    state.value.elements.any { it.id == candidate }
                },
                selectedStrokeIds = emptySet(),
            )
    }

    fun updateSelectedElement(transform: ElementTransform) {
        val element = state.value.selectedElement ?: return
        val page = state.value.selectedPage ?: return
        mutate {
            val clamped =
                clampElementTransform(
                    transform,
                    page.widthPoints.toFloat(),
                    page.heightPoints.toFloat(),
                ) ?: return@mutate
            val history = history(page.id)
            repository.updateElement(
                element.copy(
                    x = clamped.x,
                    y = clamped.y,
                    width = clamped.width,
                    height = clamped.height,
                    rotation = clamped.rotation,
                ),
            )
            history.push(snapshot(page.id))
            updateHistoryControls(history)
        }
    }

    fun duplicateSelectedElement() {
        val selected = state.value.selectedElement ?: return
        mutate {
            val history = history(selected.pageId)
            val id = repository.duplicateElement(selected.id)
            history.push(snapshot(selected.pageId))
            controls.value = controls.value.copy(selectedElementId = id)
            updateHistoryControls(history)
        }
    }

    fun bringSelectedElementForward() {
        val selected = state.value.selectedElement ?: return
        mutate {
            val history = history(selected.pageId)
            repository.moveElementForward(selected.id)
            history.push(snapshot(selected.pageId))
            updateHistoryControls(history)
        }
    }

    fun deleteSelectedElement() {
        val selected = state.value.selectedElement ?: return
        mutate {
            val history = history(selected.pageId)
            repository.deleteElement(selected.id)
            history.push(snapshot(selected.pageId))
            controls.value = controls.value.copy(selectedElementId = null)
            updateHistoryControls(history)
        }
    }

    fun moveSelectedStrokes(pageId: String, delta: CanvasPoint) {
        val selectedIds = controls.value.selectedStrokeIds
        mutate {
            if (!delta.x.isFinite() || !delta.y.isFinite()) return@mutate
            if (selectedIds.isEmpty()) return@mutate
            val history = history(pageId)
            val selectedPoints =
                history.current.strokes
                    .filter { it.id in selectedIds }
                    .flatMap { it.toStrokePath().points }
            if (selectedPoints.isEmpty()) return@mutate
            val page = requireNotNull(state.value.pages.firstOrNull { it.id == pageId })
            val dx =
                delta.x.coerceIn(
                    -selectedPoints.minOf { it.x },
                    page.widthPoints - selectedPoints.maxOf { it.x },
                )
            val dy =
                delta.y.coerceIn(
                    -selectedPoints.minOf { it.y },
                    page.heightPoints - selectedPoints.maxOf { it.y },
                )
            if (dx == 0f && dy == 0f) return@mutate
            val moved =
                history.current.strokes.map { stroke ->
                    if (stroke.id in selectedIds) stroke.translated(dx, dy) else stroke
                }
            repository.replaceStrokes(pageId, moved)
            history.push(history.current.copy(strokes = moved))
            updateHistoryControls(history)
        }
    }

    fun addText(
        pageId: String,
        text: String,
        origin: CanvasPoint? = null,
        onComplete: (Boolean) -> Unit = {},
    ) = mutate(onComplete = onComplete) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return@mutate
        val page = requireNotNull(state.value.pages.firstOrNull { it.id == pageId })
        val history = history(pageId)
        val transform =
            requireNotNull(
                initialTextElementTransform(
                    page.widthPoints.toFloat(),
                    page.heightPoints.toFloat(),
                    origin ?: CanvasPoint(page.widthPoints * 0.15f, page.heightPoints * 0.2f),
                    normalized,
                ),
            ) { "Text does not fit on the page" }
        val id = repository.addElement(
            pageId,
            ElementDraft(
                kind = ElementKind.TEXT,
                x = transform.x,
                y = transform.y,
                width = transform.width,
                height = transform.height,
                text = normalized,
            ),
        )
        history.push(snapshot(pageId))
        controls.value =
            controls.value.copy(
                tool = EditorTool.LASSO,
                selectedStrokeIds = emptySet(),
                selectedElementId = id,
            )
        updateHistoryControls(history)
    }

    fun updatePageText(pageId: String, text: String) = mutate { savePageText(pageId, text) }

    fun flushPageTextBeforeClose(
        pageId: String?,
        text: String?,
        onComplete: (Boolean) -> Unit,
    ) {
        clearRecognition()
        viewModelScope.launch {
            val didFail = flushPageText(pageId, text)
            controls.value = controls.value.copy(failed = didFail)
            onComplete(!didFail)
        }
    }

    fun updateTextElement(
        elementId: String,
        text: String,
        onComplete: (Boolean) -> Unit = {},
    ) = mutate(onComplete = onComplete) {
        val normalized = text.trim()
        val element = requireNotNull(state.value.elements.firstOrNull { it.id == elementId })
        require(element.kind == ElementKind.TEXT.name)
        if (text == element.text) return@mutate
        val page = requireNotNull(state.value.pages.firstOrNull { it.id == element.pageId })
        val history = history(page.id)
        if (normalized.isEmpty()) {
            repository.deleteElement(element.id)
            history.push(snapshot(page.id))
            controls.value = controls.value.copy(selectedElementId = null, selectedStrokeIds = emptySet())
            updateHistoryControls(history)
            return@mutate
        }
        val transform =
            requireNotNull(
                resizedTextElementTransform(
                    element.transform(),
                    page.widthPoints.toFloat(),
                    page.heightPoints.toFloat(),
                    normalized,
                ),
            )
        repository.updateElement(
            element.copy(
                text = normalized,
                x = transform.x,
                y = transform.y,
                width = transform.width,
                height = transform.height,
                rotation = transform.rotation,
            ),
        )
        history.push(snapshot(page.id))
        controls.value = controls.value.copy(selectedElementId = element.id, selectedStrokeIds = emptySet())
        updateHistoryControls(history)
    }

    fun flushPageTextBeforeAction(pageId: String?, text: String?, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            if (!mutationAllowed()) {
                onComplete(false)
                return@launch
            }
            val didFail = flushPageText(pageId, text)
            if (didFail) controls.value = controls.value.copy(failed = true)
            onComplete(!didFail)
        }
    }

    private suspend fun flushPageText(pageId: String?, text: String?): Boolean =
        LibraryMutationGate.withLock {
            runCatching {
                if (
                    pageId != null &&
                        text != null &&
                        repository.getPages(notebookId).any { it.id == pageId }
                ) {
                    savePageText(pageId, text)
                }
            }.isFailure
        }

    private suspend fun savePageText(pageId: String, text: String) {
        val history = history(pageId)
        val current = history.current.blocks.singleOrNull()?.text.orEmpty()
        if (current == text) return
        repository.updatePageText(pageId, text)
        history.push(snapshot(pageId))
        updateHistoryControls(history)
    }

    fun searchPageText(query: String, includeImageOcr: Boolean = true) {
        latestSearchQuery = query
        latestSearchIncludesImageOcr = includeImageOcr
        searchJob?.cancel()
        if (query.isBlank()) {
            clearSearch()
            return
        }
        if (!mutationAllowed()) return
        searchJob =
            viewModelScope.launch {
                val result = runCatching {
                    repository.searchPageText(notebookId, query, includeImageOcr)
                }
                if (latestSearchQuery != query || latestSearchIncludesImageOcr != includeImageOcr) return@launch
                result
                    .onSuccess { matches ->
                        controls.value =
                            controls.value.copy(
                                searchQuery = query,
                                searchResults = matches,
                                searchFailed = false,
                            )
                    }.onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        controls.value =
                            controls.value.copy(
                                searchQuery = query,
                                searchResults = emptyList(),
                                searchFailed = true,
                            )
                    }
            }
    }

    fun clearSearch() {
        latestSearchQuery = ""
        latestSearchIncludesImageOcr = true
        searchJob?.cancel()
        searchJob = null
        controls.value =
            controls.value.copy(
                searchQuery = "",
                searchResults = emptyList(),
                searchFailed = false,
            )
    }

    fun openSearchResult(result: PageTextMatch) {
        val highlight =
            result.elementId?.let { elementId ->
                OcrSearchHighlight(elementId, latestSearchQuery.trim())
            }
        selectPage(result.pageId)
        controls.value =
            controls.value.copy(
                selectedStrokeIds = emptySet(),
                selectedElementId = result.elementId,
                ocrSearchHighlight = highlight,
            )
        result.elementId?.let(::regenerateMissingOcrRegions)
        clearSearch()
    }

    fun recognizeSelectedImage() {
        val element = state.value.selectedElement?.takeIf { it.kind == ElementKind.IMAGE.name } ?: return
        if (!mutationAllowed()) return
        clearRecognition()
        imageOcrFeedback = element.id to recognitionGeneration
        publishImageOcrMessage(element.id, R.string.image_ocr_running)
        regenerateMissingOcrRegions(element.id)
    }

    private fun publishImageOcrMessage(elementId: String, message: Int) {
        if (
            imageOcrFeedback == (elementId to recognitionGeneration) &&
                controls.value.selectedElementId == elementId && mutationAllowed()
        ) {
            controls.value = controls.value.copy(recognitionMessage = getApplication<Application>().getString(message))
        }
    }

    private fun regenerateMissingOcrRegions(elementId: String) {
        if (!mutationAllowed() || !imageOcrInFlight.add(elementId)) return
        viewModelScope.launch {
            try {
                val element = repository.getElement(elementId) ?: return@launch
                if (element.kind != ElementKind.IMAGE.name) return@launch
                if (decodeImageOcrRegions(element.ocrRegions).isNotEmpty()) {
                    publishImageOcrMessage(elementId, R.string.image_ocr_ready)
                    return@launch
                }
                val assetId = element.assetId ?: return@launch
                val recognized = imageOcrRecognizer(assets.file(assetId))
                currentCoroutineContext().ensureActive()
                val text = recognized.text.trim().take(10_000).takeIf(String::isNotEmpty)
                val regions = encodeImageOcrRegions(recognized.regions).takeIf(String::isNotEmpty)
                if (text == null && regions == null) {
                    publishImageOcrMessage(elementId, R.string.recognition_no_text)
                    return@launch
                }
                LibraryMutationGate.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!mutationAllowed()) return@withLock
                    val current = repository.getElement(elementId)
                    if (
                        current != null &&
                            (current.assetId != assetId || decodeImageOcrRegions(current.ocrRegions).isNotEmpty())
                    ) {
                        return@withLock
                    }
                    if (current != null) {
                        repository.updateElement(current.copy(text = text ?: current.text, ocrRegions = regions))
                    }
                    pageHistories.existing(element.pageId)?.let { history ->
                        history.amend { snapshot ->
                            snapshot.copy(
                                elements = snapshot.elements.map { saved ->
                                    if (saved.id == elementId && saved.assetId == assetId) {
                                        saved.copy(text = text ?: saved.text, ocrRegions = regions)
                                    } else {
                                        saved
                                    }
                                },
                            )
                        }
                        if (state.value.selectedPageId == element.pageId) updateHistoryControls(history)
                    }
                    if (current != null) publishImageOcrMessage(elementId, R.string.image_ocr_ready)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Log.e(TAG, "Image text recognition failed", failure)
                publishImageOcrMessage(elementId, R.string.image_ocr_failed)
            } finally {
                imageOcrInFlight.remove(elementId)
            }
        }
    }

    fun importImage(
        pageId: String,
        uri: Uri,
        ocrEnabled: Boolean = true,
        onComplete: (Boolean) -> Unit = {},
    ) = mutate(onComplete = onComplete) {
        val page = requireNotNull(state.value.pages.firstOrNull { it.id == pageId })
        val asset = imageImporter.importImage(uri).getOrThrow()
        val history = history(pageId)
        val elementId =
            runCatching {
                val scale =
                    minOf(
                        page.widthPoints * 0.7f / asset.width,
                        page.heightPoints * 0.7f / asset.height,
                    )
                val width = asset.width * scale
                val height = asset.height * scale
                repository.addElement(
                    pageId,
                    ElementDraft(
                        kind = ElementKind.IMAGE,
                        x = (page.widthPoints - width) / 2f,
                        y = (page.heightPoints - height) / 2f,
                        width = width,
                        height = height,
                        assetId = asset.id,
                    ),
                )
            }
            .getOrElse {
                asset.file.delete()
                throw it
            }
        history.push(snapshot(pageId))
        controls.value =
            controls.value.copy(
                tool = EditorTool.LASSO,
                selectedStrokeIds = emptySet(),
                selectedElementId = elementId,
            )
        updateHistoryControls(history)
        if (ocrEnabled) regenerateMissingOcrRegions(elementId)
    }

    fun duplicateSelectedStrokes() {
        val page = state.value.selectedPage ?: return
        val selectedIds = controls.value.selectedStrokeIds
        if (selectedIds.isEmpty()) return
        mutate {
            val history = history(page.id)
            val selected = history.current.strokes.filter { it.id in selectedIds }
            val points = selected.flatMap { it.toStrokePath().points }
            if (points.isEmpty()) return@mutate
            val dx =
                when {
                    points.maxOf { it.x } + SELECTION_DUPLICATE_OFFSET <= page.widthPoints ->
                        SELECTION_DUPLICATE_OFFSET
                    points.minOf { it.x } - SELECTION_DUPLICATE_OFFSET >= 0f ->
                        -SELECTION_DUPLICATE_OFFSET
                    else -> 0f
                }
            val dy =
                when {
                    points.maxOf { it.y } + SELECTION_DUPLICATE_OFFSET <= page.heightPoints ->
                        SELECTION_DUPLICATE_OFFSET
                    points.minOf { it.y } - SELECTION_DUPLICATE_OFFSET >= 0f ->
                        -SELECTION_DUPLICATE_OFFSET
                    else -> 0f
                }
            val firstZ = (history.current.strokes.maxOfOrNull(StrokeEntity::zIndex) ?: -1) + 1
            val copies =
                selected.mapIndexed { index, stroke ->
                    stroke
                        .copy(id = UUID.randomUUID().toString(), zIndex = firstZ + index)
                        .translated(dx, dy)
                }
            val updated = history.current.strokes + copies
            repository.replaceStrokes(page.id, updated)
            history.push(history.current.copy(strokes = updated))
            controls.value =
                controls.value.copy(
                    selectedStrokeIds = copies.mapTo(mutableSetOf(), StrokeEntity::id),
                )
            updateHistoryControls(history)
        }
    }

    fun deleteSelectedStrokes() {
        val pageId = state.value.selectedPage?.id ?: return
        val selectedIds = controls.value.selectedStrokeIds
        if (selectedIds.isEmpty()) return
        mutate {
            val history = history(pageId)
            val existingIds =
                history.current.strokes.mapTo(mutableSetOf(), StrokeEntity::id) intersect selectedIds
            if (existingIds.isEmpty()) return@mutate
            repository.deleteStrokes(pageId, existingIds)
            history.push(
                history.current.copy(
                    strokes = history.current.strokes.filterNot { it.id in existingIds },
                ),
            )
            controls.value = controls.value.copy(selectedStrokeIds = emptySet())
            updateHistoryControls(history)
        }
    }

    fun recolorSelectedStrokes(colorArgb: Int) {
        val pageId = state.value.selectedPage?.id ?: return
        val selectedIds = controls.value.selectedStrokeIds
        if (selectedIds.isEmpty()) return
        mutate {
            val history = history(pageId)
            val rgb = colorArgb and 0x00FFFFFF
            if (history.current.strokes.none { it.id in selectedIds && (it.colorArgb and 0x00FFFFFF) != rgb }) {
                return@mutate
            }
            val updated =
                history.current.strokes.map { stroke ->
                    if (stroke.id in selectedIds) {
                        stroke.copy(
                            colorArgb = (stroke.colorArgb and 0xFF000000.toInt()) or rgb,
                        )
                    } else {
                        stroke
                    }
                }
            repository.replaceStrokes(pageId, updated)
            history.push(history.current.copy(strokes = updated))
            updateHistoryControls(history)
        }
    }

    fun transformSelectedStrokes(scale: Float, rotationDegrees: Float) {
        val page = state.value.selectedPage ?: return
        val selectedIds = controls.value.selectedStrokeIds
        mutate {
            val history = history(page.id)
            val updated =
                transformStrokeSelection(
                    history.current.strokes,
                    selectedIds,
                    page.widthPoints.toFloat(),
                    page.heightPoints.toFloat(),
                    scale,
                    rotationDegrees,
                ) ?: return@mutate
            repository.replaceStrokes(page.id, updated)
            history.push(history.current.copy(strokes = updated))
            updateHistoryControls(history)
        }
    }

    fun importPdf(uri: Uri, onComplete: (Boolean) -> Unit = {}) = mutate(onComplete = onComplete) {
        val imported = pdfImporter.import(notebookId, uri)
        val firstPageId = imported.pageIds.first()
        selectedPageId.value = firstPageId
        showHistoryControls(firstPageId)
    }

    fun assetFile(id: String): File = assets.file(id)

    suspend fun loadPagePreview(pageId: String): PagePreviewData {
        val page = state.value.pages.firstOrNull { it.id == pageId }
        val pdfBackground =
            page?.pdfSourceId?.let { sourceId ->
                val source = repository.getPdfSource(sourceId)
                val pageIndex = page.pdfPageIndex ?: return@let null
                pdfSandbox.renderPage(assets.requireFile(source.assetId), pageIndex, 150, 200).asImageBitmap()
            }
        return PagePreviewData(
            strokes = repository.getStrokes(pageId),
            elements = repository.getElements(pageId),
            blocks = repository.getBlocks(pageId),
            pdfBackground = pdfBackground,
        )
    }

    suspend fun renderPdfPage(pageId: String, width: Int, height: Int): ImageBitmap? {
        val page = state.value.pages.firstOrNull { it.id == pageId } ?: return null
        val sourceId = page.pdfSourceId ?: return null
        val source = repository.getPdfSource(sourceId)
        val pageIndex = page.pdfPageIndex ?: return null
        return pdfSandbox
            .renderPage(assets.requireFile(source.assetId), pageIndex, width, height)
            .asImageBitmap()
    }

    fun cleanSelectedShape(kind: ShapeKind) {
        val page = state.value.selectedPage ?: return
        mutate {
            val selectedIds = controls.value.selectedStrokeIds
            if (selectedIds.isEmpty()) return@mutate
            val history = history(page.id)
            val paths =
                history.current.strokes
                    .filter { it.id in selectedIds }
                    .map(StrokeEntity::toStrokePath)
            val box = shapeBox(paths) ?: return@mutate
            val draft =
                if (kind == ShapeKind.LINE || kind == ShapeKind.ARROW) {
                    val start = paths.first().points.first()
                    val end = paths.last().points.last()
                    val transform = segmentShapeTransform(start, end) ?: return@mutate
                    ElementDraft(
                        kind = ElementKind.SHAPE,
                        x = transform.x,
                        y = transform.y,
                        width = transform.width,
                        height = transform.height,
                        rotation = transform.rotation,
                        shapeKind = kind.name,
                    )
                } else {
                    val width = box.width.coerceAtLeast(24f).coerceAtMost(page.widthPoints.toFloat())
                    val height = box.height.coerceAtLeast(24f).coerceAtMost(page.heightPoints.toFloat())
                    ElementDraft(
                        kind = ElementKind.SHAPE,
                        x = box.left.coerceIn(0f, page.widthPoints - width),
                        y = box.top.coerceIn(0f, page.heightPoints - height),
                        width = width,
                        height = height,
                        shapeKind = kind.name,
                    )
                }
            repository.replaceStrokesWithElement(page.id, selectedIds, draft)
            history.push(snapshot(page.id))
            controls.value =
                controls.value.copy(selectedStrokeIds = emptySet(), selectedElementId = null)
            updateHistoryControls(history)
        }
    }

    fun recognizeSelectedHandwriting(language: RecognitionLanguage) {
        clearRecognition()
        val page = state.value.selectedPage ?: return
        val selectedIds = controls.value.selectedStrokeIds.toSet()
        val tool = controls.value.tool
        val generation = recognitionGeneration
        if (selectedIds.isEmpty() || !mutationAllowed()) return
        handwritingConversionJob = viewModelScope.launch {
            try {
                val capture =
                    LibraryMutationGate.withLock {
                        currentCoroutineContext().ensureActive()
                        if (
                            !mutationAllowed() ||
                            generation != recognitionGeneration ||
                            state.value.selectedPage?.id != page.id ||
                            controls.value.tool != tool ||
                            controls.value.selectedStrokeIds != selectedIds
                        ) {
                            return@withLock null
                        }
                        val strokesById = repository.getStrokes(page.id).associateBy(StrokeEntity::id)
                        val selected = selectedIds.map { strokesById[it] ?: return@withLock null }
                        val payload =
                            recognitionPayload(
                                page,
                                selected,
                                maxStrokes = MAX_CONVERSION_STROKES,
                                maxPoints = MAX_CONVERSION_POINTS,
                                maxDurationMillis = MAX_CONVERSION_DURATION_MS,
                            )
                        if (payload == null) {
                            controls.value =
                                controls.value.copy(
                                    recognitionMessage =
                                        getApplication<Application>().getString(
                                            R.string.recognition_selection_too_large,
                                        ),
                                )
                            return@withLock null
                        }
                        CapturedHandwritingConversion(
                            generation = generation,
                            pageId = page.id,
                            tool = tool,
                            selectedIds = selectedIds,
                            request = payload.request,
                            sources = selected.map { it.recognitionSource() },
                        )
                    } ?: return@launch
                val recognizer = recognizerProvider(language)
                val candidates =
                    try {
                        recognizer.recognize(capture.request)
                    } finally {
                        recognizer.close()
                    }
                        .map { it.text.trim().take(10_000) }
                        .filter(String::isNotEmpty)
                        .distinct()
                        .take(5)
                currentCoroutineContext().ensureActive()
                LibraryMutationGate.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!revalidateHandwritingConversion(capture)) return@withLock
                    if (candidates.isEmpty()) {
                        controls.value =
                            controls.value.copy(
                                recognitionMessage =
                                    getApplication<Application>().getString(R.string.recognition_no_text),
                            )
                        return@withLock
                    }
                    pendingHandwritingConversion =
                        PendingHandwritingConversion(
                            pageId = capture.pageId,
                            sources = capture.sources,
                            candidates = candidates,
                        )
                    controls.value =
                        controls.value.copy(
                            recognitionMessage = null,
                            handwritingCandidates = candidates,
                        )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (
                    generation != recognitionGeneration ||
                    state.value.selectedPage?.id != page.id ||
                    controls.value.tool != tool ||
                    controls.value.selectedStrokeIds != selectedIds ||
                    !mutationAllowed()
                ) {
                    return@launch
                }
                val message =
                    if (error.message?.contains("not downloaded", ignoreCase = true) == true) {
                        R.string.recognition_model_required
                    } else {
                        R.string.recognition_unavailable
                    }
                controls.value =
                    controls.value.copy(
                        recognitionMessage = getApplication<Application>().getString(message),
                        handwritingCandidates = emptyList(),
                    )
            }
        }
    }

    private suspend fun revalidateHandwritingConversion(capture: CapturedHandwritingConversion): Boolean {
        if (!isHandwritingConversionCurrent(capture)) return false
        val page = repository.getPages(notebookId).firstOrNull { it.id == capture.pageId } ?: return false
        if (
            page.widthPoints.toFloat() != capture.request.pageWidth ||
            page.heightPoints.toFloat() != capture.request.pageHeight
        ) {
            return false
        }
        val strokesById = repository.getStrokes(capture.pageId).associateBy(StrokeEntity::id)
        return capture.sources.all { source -> strokesById[source.id]?.let(source::matches) == true } &&
            isHandwritingConversionCurrent(capture)
    }

    private fun isHandwritingConversionCurrent(capture: CapturedHandwritingConversion): Boolean =
        mutationAllowed() &&
            capture.generation == recognitionGeneration &&
            state.value.selectedPage?.id == capture.pageId &&
            controls.value.tool == capture.tool &&
            controls.value.selectedStrokeIds == capture.selectedIds

    fun addHandwritingCandidateToPage(candidate: String) {
        val conversion = pendingHandwritingConversion ?: return
        if (candidate !in conversion.candidates) return
        pendingHandwritingConversion = null
        controls.value = controls.value.copy(handwritingCandidates = emptyList())
        mutate {
            val strokesById = repository.getStrokes(conversion.pageId).associateBy(StrokeEntity::id)
            if (conversion.sources.any { source -> strokesById[source.id]?.let(source::matches) != true }) {
                return@mutate
            }
            val existing = repository.getBlocks(conversion.pageId).singleOrNull()?.text.orEmpty().trimEnd()
            val updated = if (existing.isEmpty()) candidate else "$existing\n$candidate"
            val history = history(conversion.pageId)
            repository.updatePageText(conversion.pageId, updated)
            history.push(snapshot(conversion.pageId))
            controls.value = controls.value.copy(selectedStrokeIds = emptySet())
            updateHistoryControls(history)
        }
    }

    fun dismissHandwritingCandidates() {
        pendingHandwritingConversion = null
        controls.value = controls.value.copy(handwritingCandidates = emptyList())
    }

    fun addMath(pageId: String, expression: String) = mutate {
        val source = expression.trim().let { value -> if (value.endsWith('=')) value else "$value=" }
        val variables =
            mathVariablesFromText(
                repository.getBlocks(pageId).sortedBy(BlockEntity::orderIndex).joinToString("\n") {
                    it.text.orEmpty()
                },
            )
        val result = formatMathResult(evaluateExpression(source, variables).getOrThrow())
        val page = requireNotNull(state.value.pages.firstOrNull { it.id == pageId })
        val history = history(pageId)
        repository.addElement(
            pageId,
            ElementDraft(
                kind = ElementKind.MATH,
                x = page.widthPoints * 0.15f,
                y = page.heightPoints * 0.35f,
                width = page.widthPoints * 0.7f,
                height = 64f,
                expression = source,
                resultText = "${source.dropLast(1).trim()} = $result",
            ),
        )
        history.push(snapshot(pageId))
        updateHistoryControls(history)
    }

    fun chooseMathCandidate(candidate: InkMathCandidate) {
        val ambiguity = pendingMathAmbiguity ?: return
        if (candidate !in ambiguity.candidates) return
        pendingMathAmbiguity = null
        controls.value =
            controls.value.copy(
                recognitionMessage = null,
                ambiguousMathCandidates = emptyList(),
            )
        if (!mutationAllowed()) return
        candidateChoiceJob?.cancel()
        candidateChoiceJob =
            viewModelScope.launch {
                try {
                    LibraryMutationGate.withLock {
                        if (
                            mutationAllowed() &&
                                revalidateRecognition(ambiguity.capture)
                        ) {
                            insertRecognizedMath(ambiguity.capture, candidate)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    publishRecognitionMessage(
                        ambiguity.capture.generation,
                        getApplication<Application>().getString(R.string.recognition_unavailable),
                    )
                }
            }
    }

    fun dismissMathCandidates() {
        pendingMathAmbiguity = null
        controls.value = controls.value.copy(ambiguousMathCandidates = emptyList())
    }

    fun exportPdf(uri: Uri) {
        clearRecognition()
        if (!mutationAllowed()) return
        val application = getApplication<Application>()
        val resolver = application.contentResolver
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    withPdfExportSnapshot(
                        cacheDir = application.cacheDir,
                        assets = assets,
                        loadContent = {
                            check(mutationAllowed()) { "Library is unavailable for export" }
                            repository.loadNotebook(notebookId)
                        },
                    ) { content, exportAssets ->
                        writePdfToDestination(
                            cacheDir = application.cacheDir,
                            render = { output ->
                                PdfExporter(exportAssets).write(content, output) { source, page, width, height ->
                                    pdfSandbox.renderPage(
                                        exportAssets.requireFile(source.assetId),
                                        requireNotNull(page.pdfPageIndex),
                                        width,
                                        height,
                                    )
                                }
                            },
                            openDestination = { resolver.openOutputStream(uri, "rwt") },
                            deleteDestination = {
                                check(DocumentsContract.deleteDocument(resolver, uri)) {
                                    "Incomplete PDF destination could not be deleted"
                                }
                            },
                        )
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                controls.value = controls.value.copy(failed = true)
            }
        }
    }

    fun undo() {
        val pageId = state.value.selectedPage?.id ?: return
        mutate(cancelRecognition = false) {
            clearRecognition()
            val history = history(pageId)
            val snapshot =
                history.undo { previous ->
                    repository.replacePageContent(pageId, previous.strokes, previous.elements, previous.blocks)
                } ?: return@mutate
            controls.value =
                controls.value.copy(
                    selectedStrokeIds = emptySet(),
                    selectedElementId =
                        controls.value.selectedElementId?.takeIf { id ->
                            snapshot.elements.any { it.id == id }
                        },
                )
            updateHistoryControls(history)
        }
    }

    fun redo() {
        val pageId = state.value.selectedPage?.id ?: return
        mutate(cancelRecognition = false) {
            clearRecognition()
            val history = history(pageId)
            val snapshot =
                history.redo { next ->
                    repository.replacePageContent(pageId, next.strokes, next.elements, next.blocks)
                } ?: return@mutate
            controls.value =
                controls.value.copy(
                    selectedStrokeIds = emptySet(),
                    selectedElementId =
                        controls.value.selectedElementId?.takeIf { id ->
                            snapshot.elements.any { it.id == id }
                        },
                )
            updateHistoryControls(history)
        }
    }

    private suspend fun history(pageId: String): PageHistory<PageSnapshot> =
        pageHistories.existing(pageId) ?: pageHistories.history(pageId, snapshot(pageId))

    private fun showHistoryControls(pageId: String?) {
        val history = pageId?.let(pageHistories::existing)
        controls.value =
            controls.value.copy(
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                canUndo = history?.canUndo == true,
                canRedo = history?.canRedo == true,
            )
    }

    private fun updateHistoryControls(history: PageHistory<PageSnapshot>) {
        controls.value = controls.value.copy(canUndo = history.canUndo, canRedo = history.canRedo)
    }

    private suspend fun snapshot(pageId: String): PageSnapshot =
        PageSnapshot(
            repository.getStrokes(pageId),
            repository.getElements(pageId),
            repository.getBlocks(pageId),
        )

    private fun mutate(
        cancelRecognition: Boolean = true,
        onComplete: (Boolean) -> Unit = {},
        block: suspend () -> Unit,
    ) {
        if (cancelRecognition) clearRecognition()
        if (!mutationAllowed()) {
            onComplete(false)
            return
        }
        viewModelScope.launch {
            LibraryMutationGate.withLock {
                if (!mutationAllowed()) {
                    onComplete(false)
                    return@withLock
                }
                val didFail = runCatching { block() }.isFailure
                controls.value = controls.value.copy(failed = didFail)
                onComplete(!didFail)
            }
        }
    }

    private fun scheduleRecognition(
        pageId: String,
        strokeId: String,
        language: RecognitionLanguage,
        callbackEpoch: Long,
        toolAtFinish: EditorTool,
    ) {
        if (
            callbackEpoch != recognitionInvalidationEpoch ||
                controls.value.tool != toolAtFinish
        ) {
            return
        }
        recognitionJob?.cancel()
        if (pendingRecognitionPageId != pageId || pendingRecognitionLanguage != language) {
            pendingRecognitionStrokeIds.clear()
        }
        pendingRecognitionPageId = pageId
        pendingRecognitionLanguage = language
        pendingRecognitionStrokeIds += strokeId
        while (pendingRecognitionStrokeIds.size > MAX_RECOGNITION_STROKES) {
            pendingRecognitionStrokeIds.removeAt(0)
        }
        pendingMathAmbiguity = null
        recognitionGeneration++
        val generation = recognitionGeneration
        controls.value =
            controls.value.copy(
                recognitionMessage = null,
                ambiguousMathCandidates = emptyList(),
            )
        recognitionJob =
            viewModelScope.launch {
                recognitionDelay()
                recognizePendingBurst(generation)
            }
    }

    private suspend fun recognizePendingBurst(generation: Long) {
        val capture =
            try {
                LibraryMutationGate.withLock {
                    captureRecognition(generation)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                publishRecognitionMessage(
                    generation,
                    getApplication<Application>().getString(R.string.recognition_unavailable),
                )
                return
            } ?: return
        val candidates =
            try {
                val recognizer = recognizerProvider(capture.language)
                try {
                    recognizer.recognize(capture.request)
                } finally {
                    recognizer.close()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalStateException) {
                val message =
                    if (error.message?.contains("not downloaded", ignoreCase = true) == true) {
                        getApplication<Application>().getString(R.string.recognition_model_required)
                    } else {
                        getApplication<Application>().getString(R.string.recognition_unavailable)
                    }
                publishRecognitionMessage(generation, message)
                return
            } catch (_: Exception) {
                publishRecognitionMessage(
                    generation,
                    getApplication<Application>().getString(R.string.recognition_unavailable),
                )
                return
            }
        val variables =
            mathVariablesFromText(
                state.value.selectedBlocks.sortedBy(BlockEntity::orderIndex).joinToString("\n") { it.text.orEmpty() },
            )
        val decision = decideInkMath(candidates, variables)
        try {
            LibraryMutationGate.withLock {
                if (!revalidateRecognition(capture)) return@withLock
                when (decision) {
                    is InkMathDecision.Unique -> insertRecognizedMath(capture, decision.candidate)
                    is InkMathDecision.Ambiguous -> {
                        pendingMathAmbiguity = PendingMathAmbiguity(capture, decision.candidates)
                        controls.value =
                            controls.value.copy(
                                recognitionMessage = null,
                                ambiguousMathCandidates = decision.candidates,
                            )
                    }
                    InkMathDecision.None -> {
                        pendingMathAmbiguity = null
                        controls.value =
                            controls.value.copy(
                                recognitionMessage = null,
                                ambiguousMathCandidates = emptyList(),
                            )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            publishRecognitionMessage(
                generation,
                getApplication<Application>().getString(R.string.recognition_unavailable),
            )
        }
    }

    private suspend fun captureRecognition(generation: Long): CapturedRecognition? {
        if (generation != recognitionGeneration) return null
        val pageId = pendingRecognitionPageId ?: return null
        val language = pendingRecognitionLanguage ?: return null
        val strokeIds = pendingRecognitionStrokeIds.toList()
        pendingRecognitionStrokeIds.clear()
        pendingRecognitionPageId = null
        pendingRecognitionLanguage = null
        if (strokeIds.isEmpty() || state.value.selectedPage?.id != pageId) return null
        val page = repository.getPages(notebookId).firstOrNull { it.id == pageId } ?: return null
        val strokesById = repository.getStrokes(pageId).associateBy(StrokeEntity::id)
        val sourceStrokes = strokeIds.map { strokesById[it] ?: return null }
        val sources = sourceStrokes.map { it.recognitionSource() }
        val payload = recognitionPayload(page, sourceStrokes) ?: return null
        return CapturedRecognition(
            generation = generation,
            language = language,
            request = payload.request,
            sources = sources,
            bounds = payload.bounds,
        )
    }

    private fun recognitionPayload(
        page: PageEntity,
        sourceStrokes: List<StrokeEntity>,
        maxStrokes: Int = MAX_RECOGNITION_STROKES,
        maxPoints: Int = MAX_RECOGNITION_POINTS,
        maxDurationMillis: Long = MAX_RECOGNITION_DURATION_MS,
    ): RecognitionPayload? {
        if (sourceStrokes.size !in 1..maxStrokes) return null
        var pointCount = 0
        val recognitionStrokes =
            sourceStrokes.map { stroke ->
                val inputs = stroke.toInkStroke().inputs
                if (inputs.size > maxPoints - pointCount) return null
                pointCount += inputs.size
                RecognitionStroke(
                    (0 until inputs.size).map { index ->
                        val input = inputs[index]
                        RecognitionPoint(input.x, input.y, input.elapsedTimeMillis)
                    },
                )
            }
        val points = recognitionStrokes.flatMap(RecognitionStroke::points)
        val burstDuration = recognitionBurstDurationMillis(recognitionStrokes)
        if (points.isEmpty() || burstDuration == null || burstDuration > maxDurationMillis) {
            return null
        }
        val request =
            boundedRecognitionRequest(
                pageId = page.id,
                pageWidth = page.widthPoints.toFloat(),
                pageHeight = page.heightPoints.toFloat(),
                fingerprints = sourceStrokes.map { it.recognitionFingerprint() },
                strokes = recognitionStrokes,
                maxStrokes = maxStrokes,
                maxPoints = maxPoints,
            ) ?: return null
        return RecognitionPayload(
            request = request,
            bounds =
                RecognitionBounds(
                    left = points.minOf(RecognitionPoint::x),
                    top = points.minOf(RecognitionPoint::y),
                    right = points.maxOf(RecognitionPoint::x),
                    bottom = points.maxOf(RecognitionPoint::y),
                ),
        )
    }

    private suspend fun revalidateRecognition(capture: CapturedRecognition): Boolean {
        if (
            capture.generation != recognitionGeneration ||
                appliedRecognitionGeneration == capture.generation ||
                state.value.selectedPage?.id != capture.request.pageId ||
                repository.getPages(notebookId).none { it.id == capture.request.pageId }
        ) {
            return false
        }
        val strokesById = repository.getStrokes(capture.request.pageId).associateBy(StrokeEntity::id)
        return capture.sources.all { source ->
            strokesById[source.id]?.let(source::matches) == true
        } &&
            capture.generation == recognitionGeneration &&
            state.value.selectedPage?.id == capture.request.pageId
    }

    private suspend fun insertRecognizedMath(
        capture: CapturedRecognition,
        candidate: InkMathCandidate,
    ) =
        withContext(NonCancellable) {
            if (appliedRecognitionGeneration == capture.generation) return@withContext
            val page =
                repository.getPages(notebookId).firstOrNull { it.id == capture.request.pageId }
                    ?: return@withContext
            val pageWidth = page.widthPoints.toFloat()
            val pageHeight = page.heightPoints.toFloat()
            val width =
                maxOf(MATH_ELEMENT_MIN_WIDTH, capture.bounds.right - capture.bounds.left)
                    .coerceAtMost(pageWidth)
            val height = MATH_ELEMENT_HEIGHT.coerceAtMost(pageHeight)
            val rightX = capture.bounds.right + MATH_ELEMENT_GAP
            val fitsRight = rightX + width <= pageWidth
            val transform =
                clampElementTransform(
                    ElementTransform(
                        x = if (fitsRight) rightX else capture.bounds.left,
                        y = if (fitsRight) capture.bounds.top else capture.bounds.bottom + MATH_ELEMENT_GAP,
                        width = width,
                        height = height,
                        rotation = 0f,
                    ),
                    pageWidth,
                    pageHeight,
                ) ?: return@withContext
            val history = history(page.id)
            if (!mutationAllowed()) return@withContext
            val preInsert = snapshot(page.id)
            recognitionWriteBoundary()
            if (!revalidateRecognition(capture) || !mutationAllowed()) return@withContext
            recognitionCommitInProgress = true
            try {
                var inserted = false
                var rollbackAttempted = false
                try {
                    val insertedElement =
                        repository.addElementEntity(
                            page.id,
                            ElementDraft(
                                kind = ElementKind.MATH,
                                x = transform.x,
                                y = transform.y,
                                width = transform.width,
                                height = transform.height,
                                rotation = transform.rotation,
                                expression = candidate.expression,
                                resultText = "${candidate.expression.dropLast(1).trim()} = ${candidate.result}",
                            ),
                        )
                    inserted = true
                    recognitionCommitBoundary()
                    if (
                        capture.generation != recognitionGeneration ||
                            state.value.selectedPage?.id != capture.request.pageId ||
                            !mutationAllowed()
                    ) {
                        rollbackAttempted = true
                        try {
                            restoreRecognitionSnapshot(page.id, preInsert)
                        } catch (rollbackFailure: Exception) {
                            invalidateHistoryAfterRollbackFailure(page.id)
                            throw rollbackFailure
                        }
                        return@withContext
                    }
                    history.push(preInsert.copy(elements = preInsert.elements + insertedElement))
                } catch (failure: Exception) {
                    if (inserted && !rollbackAttempted) {
                        try {
                            restoreRecognitionSnapshot(page.id, preInsert)
                        } catch (rollbackFailure: Exception) {
                            failure.addSuppressed(rollbackFailure)
                            invalidateHistoryAfterRollbackFailure(page.id)
                        }
                    }
                    throw failure
                }
                appliedRecognitionGeneration = capture.generation
                pendingMathAmbiguity = null
                updateHistoryControls(history)
                controls.value =
                    controls.value.copy(
                        recognitionMessage = null,
                        ambiguousMathCandidates = emptyList(),
                    )
            } finally {
                recognitionCommitInProgress = false
            }
        }

    private suspend fun restoreRecognitionSnapshot(pageId: String, snapshot: PageSnapshot) {
        repository.replacePageContent(
            pageId,
            snapshot.strokes,
            snapshot.elements,
            snapshot.blocks,
        )
    }

    private suspend fun invalidateHistoryAfterRollbackFailure(pageId: String) {
        pageHistories.remove(pageId)
        try {
            if (repository.getPages(notebookId).any { it.id == pageId }) {
                pageHistories.history(pageId, snapshot(pageId))
            }
        } catch (_: Exception) {
            pageHistories.remove(pageId)
        }
        controls.value =
            controls.value.copy(
                selectedStrokeIds = emptySet(),
                selectedElementId = null,
                canUndo = false,
                canRedo = false,
                failed = true,
            )
    }

    private fun recognitionBurstDurationMillis(strokes: List<RecognitionStroke>): Long? {
        var total = 0L
        strokes.forEachIndexed { index, stroke ->
            val first = stroke.points.firstOrNull()?.timeMillis ?: return null
            if (first < 0L) return null
            var previous = first
            stroke.points.drop(1).forEach { point ->
                if (point.timeMillis < previous) return null
                previous = point.timeMillis
            }
            if (index > 0) {
                total = safeDurationSum(total, RECOGNITION_STROKE_SEPARATOR_MS) ?: return null
            }
            total = safeDurationSum(total, previous - first) ?: return null
        }
        return total
    }

    private fun safeDurationSum(current: Long, value: Long): Long? {
        if (value < 0L || current > Long.MAX_VALUE - value) return null
        return current + value
    }

    private fun publishRecognitionMessage(generation: Long, message: String) {
        if (generation != recognitionGeneration) return
        pendingMathAmbiguity = null
        controls.value =
            controls.value.copy(
                recognitionMessage = message,
                ambiguousMathCandidates = emptyList(),
            )
    }

    private fun clearRecognition() {
        recognitionInvalidationEpoch++
        recognitionGeneration++
        recognitionJob?.cancel()
        recognitionJob = null
        candidateChoiceJob?.cancel()
        candidateChoiceJob = null
        handwritingConversionJob?.cancel()
        handwritingConversionJob = null
        pendingRecognitionStrokeIds.clear()
        pendingRecognitionPageId = null
        pendingRecognitionLanguage = null
        pendingMathAmbiguity = null
        pendingHandwritingConversion = null
        recognitionBurstSession = null
        controls.value =
            controls.value.copy(
                recognitionMessage = null,
                ambiguousMathCandidates = emptyList(),
                handwritingCandidates = emptyList(),
            )
    }

    private fun invalidateRecognitionForNewInk(
        pageId: String,
        language: RecognitionLanguage,
        tool: EditorTool,
    ): Long {
        val session = recognitionBurstSession
        if (
            session == null ||
                session.pageId != pageId ||
                session.language != language ||
                session.tool != tool
        ) {
            recognitionInvalidationEpoch++
            pendingRecognitionStrokeIds.clear()
            pendingRecognitionPageId = null
            pendingRecognitionLanguage = null
            recognitionBurstSession =
                RecognitionBurstSession(
                    pageId = pageId,
                    language = language,
                    tool = tool,
                    epoch = recognitionInvalidationEpoch,
                )
        }
        recognitionGeneration++
        recognitionJob?.cancel()
        recognitionJob = null
        candidateChoiceJob?.cancel()
        candidateChoiceJob = null
        pendingMathAmbiguity = null
        controls.value =
            controls.value.copy(
                recognitionMessage = null,
                ambiguousMathCandidates = emptyList(),
            )
        return requireNotNull(recognitionBurstSession).epoch
    }

    private fun StrokeEntity.recognitionSource(): RecognitionSource =
        RecognitionSource(id, brushKind, colorArgb, size, epsilon, inputs.copyOf())

    private fun StrokeEntity.recognitionFingerprint(): RecognitionFingerprint {
        var hash = id.hashCode()
        hash = 31 * hash + brushKind.hashCode()
        hash = 31 * hash + colorArgb
        hash = 31 * hash + size.toBits()
        hash = 31 * hash + epsilon.toBits()
        hash = 31 * hash + inputs.contentHashCode()
        return RecognitionFingerprint(id, hash)
    }

    private fun showSmartShapePreview(id: String) {
        controls.value = controls.value.copy(smartShapePreviewId = id)
        viewModelScope.launch {
            delay(SMART_SHAPE_PREVIEW_MS)
            if (controls.value.smartShapePreviewId == id) {
                controls.value = controls.value.copy(smartShapePreviewId = null)
            }
        }
    }

    override fun onCleared() {
        recognitionJob?.cancel()
        candidateChoiceJob?.cancel()
        handwritingConversionJob?.cancel()
        searchJob?.cancel()
        // ponytail: orphan images wait for LibraryViewModel cleanup;
        // a global sweep can break another editor's live undo.
    }

    private companion object {
        const val TAG = "EditorViewModel"
        const val DEFAULT_CHAPTER_COLOR = 0xFF3156D9.toInt()
        const val ERASER_RADIUS = 16f
        const val PAGE_HISTORY_MAX_BYTES = 8 * 1024 * 1024
        const val PAGE_HISTORY_MAX_PAGES = 4
        const val SMART_SHAPE_PREVIEW_MS = 900L
        const val RECOGNITION_DEBOUNCE_MS = 1_000L
        const val MAX_RECOGNITION_STROKES = 32
        const val MAX_RECOGNITION_POINTS = 4_096
        const val MAX_RECOGNITION_DURATION_MS = 10_000L
        const val MAX_CONVERSION_STROKES = 128
        const val MAX_CONVERSION_POINTS = 16_384
        const val MAX_CONVERSION_DURATION_MS = 60_000L
        const val RECOGNITION_STROKE_SEPARATOR_MS = 1L
        const val MATH_ELEMENT_MIN_WIDTH = 160f
        const val MATH_ELEMENT_HEIGHT = 64f
        const val MATH_ELEMENT_GAP = 12f
        const val SELECTION_DUPLICATE_OFFSET = 12f
    }
}
