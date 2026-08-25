package com.majkeylab.seliadocs.editor

import android.app.Application
import android.net.Uri
import androidx.ink.strokes.Stroke
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.ElementDraft
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.NotebookEntity
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.data.StrokeEntity
import com.majkeylab.seliadocs.data.StrokePayload
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    val strokes: List<StrokeEntity> = emptyList(),
    val elements: List<ElementEntity> = emptyList(),
    val selectedPageId: String? = null,
    val tool: EditorTool = EditorTool.PEN,
    val selectedStrokeIds: Set<String> = emptySet(),
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
}

private data class EditorContent(
    val pages: List<PageEntity>,
    val strokes: List<StrokeEntity>,
    val elements: List<ElementEntity>,
)

private data class PageSnapshot(
    val strokes: List<StrokeEntity>,
    val elements: List<ElementEntity>,
)

private data class EditorControls(
    val tool: EditorTool = EditorTool.PEN,
    val selectedStrokeIds: Set<String> = emptySet(),
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
    private val selectedPageId = MutableStateFlow<String?>(null)
    private val controls = MutableStateFlow(EditorControls(tool = initialTool))
    private val pageHistories = PageHistoryStore<PageSnapshot>()

    private val pages =
        repository.observePages(notebookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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
                flowOf(EditorContent(emptyList(), emptyList(), emptyList()))
            } else {
                combine(
                    repository.observeStrokes(pageId),
                    repository.observeElements(pageId),
                ) { strokes, elements -> EditorContent(emptyList(), strokes, elements) }
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
                controls,
            ) { notebook, document, selected, editorControls ->
                EditorUiState(
                    notebook = notebook,
                    pages = document.pages,
                    strokes = document.strokes,
                    elements = document.elements,
                    selectedPageId = selected,
                    tool = editorControls.tool,
                    selectedStrokeIds = editorControls.selectedStrokeIds,
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
            )
    }

    fun addPage() = mutate {
        val id = repository.addPage(notebookId)
        selectedPageId.value = id
        showHistoryControls(id)
    }

    fun duplicatePage(id: String) = mutate {
        val duplicateId = repository.duplicatePage(id)
        selectedPageId.value = duplicateId
        showHistoryControls(duplicateId)
    }

    fun deletePage(id: String) = mutate {
        val wasSelected = state.value.selectedPage?.id == id
        val assetIds = repository.getElements(id).mapNotNull { it.assetId }.distinct()
        repository.deletePage(id)
        assetIds.forEach { assetId ->
            if (repository.getAssetReferenceCount(assetId) == 0) {
                val file = assets.file(assetId)
                check(!file.exists() || file.delete()) { "Asset could not be deleted" }
            }
        }
        pageHistories.remove(id)
        if (wasSelected) showHistoryControls(null)
    }

    fun movePage(fromIndex: Int, toIndex: Int) = mutate {
        repository.movePage(notebookId, fromIndex, toIndex)
    }

    fun addStroke(pageId: String, stroke: Stroke) = mutate {
        val history = history(pageId)
        val encoded = InkCodec.encode(stroke)
        repository.addStroke(
            pageId,
            StrokePayload(
                brushKind = encoded.brushKind.name,
                colorArgb = encoded.colorArgb,
                size = encoded.size,
                epsilon = encoded.epsilon,
                inputs = encoded.inputs,
            ),
        )
        history.push(snapshot(pageId))
        updateHistoryControls(history)
    }

    fun eraseStrokes(pageId: String, points: List<CanvasPoint>) = mutate {
        if (points.isEmpty()) return@mutate
        val history = history(pageId)
        val ids =
            history.current.strokes
                .map(StrokeEntity::toStrokePath)
                .filter { stroke -> points.any { point -> hitStroke(point, 16f, stroke) } }
                .mapTo(mutableSetOf(), StrokePath::id)
        if (ids.isEmpty()) return@mutate
        repository.deleteStrokes(pageId, ids)
        history.push(snapshot(pageId))
        controls.value = controls.value.copy(selectedStrokeIds = controls.value.selectedStrokeIds - ids)
        updateHistoryControls(history)
    }

    fun selectStrokes(pageId: String, lasso: List<CanvasPoint>) {
        val strokes = state.value.strokes.filter { it.pageId == pageId }.map(StrokeEntity::toStrokePath)
        controls.value = controls.value.copy(selectedStrokeIds = selectStrokes(lasso, strokes))
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

    fun assetFile(id: String): File = assets.file(id)

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
                    ElementDraft(
                        kind = ElementKind.SHAPE,
                        x = start.x,
                        y = start.y - 12f,
                        width = hypot(end.x - start.x, end.y - start.y).coerceAtLeast(4f),
                        height = 24f,
                        rotation = Math.toDegrees(atan2(end.y - start.y, end.x - start.x).toDouble()).toFloat(),
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
            controls.value = controls.value.copy(selectedStrokeIds = emptySet())
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
                pdfExporter.write(content, requireNotNull(output) { "PDF destination unavailable" })
            }
        }
    }

    fun undo() {
        val pageId = state.value.selectedPage?.id ?: return
        mutate {
            val history = history(pageId)
            val snapshot = history.undo() ?: return@mutate
            repository.replacePageContent(pageId, snapshot.strokes, snapshot.elements)
            controls.value = controls.value.copy(selectedStrokeIds = emptySet())
            updateHistoryControls(history)
        }
    }

    fun redo() {
        val pageId = state.value.selectedPage?.id ?: return
        mutate {
            val history = history(pageId)
            val snapshot = history.redo() ?: return@mutate
            repository.replacePageContent(pageId, snapshot.strokes, snapshot.elements)
            controls.value = controls.value.copy(selectedStrokeIds = emptySet())
            updateHistoryControls(history)
        }
    }

    private fun history(pageId: String): PageHistory<PageSnapshot> {
        return pageHistories.history(
            pageId,
            PageSnapshot(
                strokes = state.value.strokes.filter { it.pageId == pageId },
                elements = state.value.elements.filter { it.pageId == pageId },
            ),
        )
    }

    private fun showHistoryControls(pageId: String?) {
        val history = pageId?.let(pageHistories::existing)
        controls.value =
            controls.value.copy(
                selectedStrokeIds = emptySet(),
                canUndo = history?.canUndo == true,
                canRedo = history?.canRedo == true,
            )
    }

    private fun updateHistoryControls(history: PageHistory<PageSnapshot>) {
        controls.value = controls.value.copy(canUndo = history.canUndo, canRedo = history.canRedo)
    }

    private suspend fun snapshot(pageId: String): PageSnapshot =
        PageSnapshot(repository.getStrokes(pageId), repository.getElements(pageId))

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            val didFail = runCatching { block() }.isFailure
            controls.value = controls.value.copy(failed = didFail)
        }
    }
}
