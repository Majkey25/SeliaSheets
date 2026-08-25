package com.majkeylab.seliadocs.editor

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.ink.strokes.Stroke
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.BlockEntity
import com.majkeylab.seliadocs.data.ChapterEntity
import com.majkeylab.seliadocs.data.ElementDraft
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.ElementKind
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
import java.io.File
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val failed: Boolean = false,
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
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val failed: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class EditorViewModel(
    application: Application,
    private val notebookId: String,
    initialTool: EditorTool = EditorTool.PEN,
) :
    AndroidViewModel(application) {
    private val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
    private val assets = AssetStore(File(application.filesDir, "assets"))
    private val imageImporter = ImageImporter(application.contentResolver, assets)
    private val pdfExporter = PdfExporter(assets)
    private val pdfSandbox = PdfSandboxClient(application)
    private val pdfImporter = PdfImporter(application.contentResolver, assets, repository, pdfSandbox)
    private val selectedPageId = MutableStateFlow<String?>(null)
    private val controls = MutableStateFlow(EditorControls(tool = initialTool))
    private val pageHistories = PageHistoryStore<PageSnapshot>()
    private val mutationMutex = Mutex()

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
                    canUndo = editorControls.canUndo,
                    canRedo = editorControls.canRedo,
                    failed = editorControls.failed,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    fun selectPage(id: String) {
        if (state.value.selectedPage?.id == id) return
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

    fun selectTool(tool: EditorTool) {
        controls.value =
            controls.value.copy(
                tool = tool,
                selectedStrokeIds = if (tool == EditorTool.LASSO) controls.value.selectedStrokeIds else emptySet(),
                selectedElementId = if (tool == EditorTool.LASSO) controls.value.selectedElementId else null,
            )
    }

    fun addPage() = mutate {
        val id = repository.addPage(notebookId)
        selectedPageId.value = id
        showHistoryControls(id)
    }

    fun setEraserMode(mode: EraserMode) {
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

    fun addStroke(pageId: String, stroke: Stroke, shapeAssist: Boolean = true) {
        val toolAtFinish = controls.value.tool
        mutate {
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
            val encoded = InkCodec.encode(stroke)
            val payload =
                StrokePayload(
                    brushKind = encoded.brushKind.name,
                    colorArgb = encoded.colorArgb,
                    size = encoded.size,
                    epsilon = encoded.epsilon,
                    inputs = encoded.inputs,
                )
            if (recognition == null) {
                repository.addStroke(pageId, payload)
            } else {
                val rawStrokeId = repository.addStroke(pageId, payload)
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
            // ponytail: retain orphan assets until history-safe GC exists; immediate deletion breaks Undo.
            history.push(snapshot(selected.pageId))
            controls.value = controls.value.copy(selectedElementId = null)
            updateHistoryControls(history)
        }
    }

    fun moveSelectedStrokes(pageId: String, delta: CanvasPoint) = mutate {
        if (!delta.x.isFinite() || !delta.y.isFinite()) return@mutate
        val selectedIds = controls.value.selectedStrokeIds
        if (selectedIds.isEmpty()) return@mutate
        val history = history(pageId)
        val selectedPoints =
            history.current.strokes
                .filter { it.id in selectedIds }
                .flatMap { it.toStrokePath().points }
        if (selectedPoints.isEmpty()) return@mutate
        val page = requireNotNull(state.value.pages.firstOrNull { it.id == pageId })
        val dx = delta.x.coerceIn(-selectedPoints.minOf { it.x }, page.widthPoints - selectedPoints.maxOf { it.x })
        val dy = delta.y.coerceIn(-selectedPoints.minOf { it.y }, page.heightPoints - selectedPoints.maxOf { it.y })
        if (dx == 0f && dy == 0f) return@mutate
        val moved =
            history.current.strokes.map { stroke ->
                if (stroke.id in selectedIds) stroke.translated(dx, dy) else stroke
            }
        repository.replaceStrokes(pageId, moved)
        history.push(history.current.copy(strokes = moved))
        updateHistoryControls(history)
    }

    fun addText(pageId: String, text: String) = mutate {
        val normalized = text.trim()
        if (normalized.isEmpty()) return@mutate
        val page = requireNotNull(state.value.pages.firstOrNull { it.id == pageId })
        val history = history(pageId)
        repository.addElement(
            pageId,
            ElementDraft(
                kind = ElementKind.TEXT,
                x = page.widthPoints * 0.15f,
                y = page.heightPoints * 0.2f,
                width = page.widthPoints * 0.7f,
                height = (64f + normalized.length / 40 * 24f).coerceAtMost(page.heightPoints * 0.5f),
                text = normalized,
            ),
        )
        history.push(snapshot(pageId))
        updateHistoryControls(history)
    }

    fun updatePageText(pageId: String, text: String) = mutate {
        val history = history(pageId)
        val current = history.current.blocks.singleOrNull()?.text.orEmpty()
        if (current == text) return@mutate
        repository.updatePageText(pageId, text)
        history.push(snapshot(pageId))
        updateHistoryControls(history)
    }

    fun searchPageText(query: String) = mutate {
        controls.value =
            controls.value.copy(
                searchQuery = query,
                searchResults = repository.searchPageText(notebookId, query),
            )
    }

    fun clearSearch() {
        controls.value = controls.value.copy(searchQuery = "", searchResults = emptyList())
    }

    fun importImage(pageId: String, uri: Uri) = mutate {
        val page = requireNotNull(state.value.pages.firstOrNull { it.id == pageId })
        val asset = imageImporter.importImage(uri).getOrThrow()
        val history = history(pageId)
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
            .onFailure {
                asset.file.delete()
                throw it
            }
        history.push(snapshot(pageId))
        updateHistoryControls(history)
    }

    fun importPdf(uri: Uri) = mutate {
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

    fun addMath(pageId: String, expression: String) = mutate {
        val source = expression.trim().let { value -> if (value.endsWith('=')) value else "$value=" }
        val result = formatMathResult(evaluateExpression(source).getOrThrow())
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

    fun exportPdf(uri: Uri) = mutate {
        val content = repository.loadNotebook(notebookId)
        val resolver = getApplication<Application>().contentResolver
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            resolver.openOutputStream(uri, "w").use { output ->
                pdfExporter.write(
                    content,
                    requireNotNull(output) { "PDF destination unavailable" },
                ) { source, page, width, height ->
                    pdfSandbox.renderPage(
                        assets.requireFile(source.assetId),
                        requireNotNull(page.pdfPageIndex),
                        width,
                        height,
                    )
                }
            }
        }
    }

    fun undo() {
        val pageId = state.value.selectedPage?.id ?: return
        mutate {
            val history = history(pageId)
            val snapshot = history.undo() ?: return@mutate
            repository.replacePageContent(pageId, snapshot.strokes, snapshot.elements, snapshot.blocks)
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
        mutate {
            val history = history(pageId)
            val snapshot = history.redo() ?: return@mutate
            repository.replacePageContent(pageId, snapshot.strokes, snapshot.elements, snapshot.blocks)
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

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutationMutex.withLock {
                val didFail = runCatching { block() }.isFailure
                controls.value = controls.value.copy(failed = didFail)
            }
        }
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

    private companion object {
        const val DEFAULT_CHAPTER_COLOR = 0xFF3156D9.toInt()
        const val ERASER_RADIUS = 16f
        const val SMART_SHAPE_PREVIEW_MS = 900L
    }
}
