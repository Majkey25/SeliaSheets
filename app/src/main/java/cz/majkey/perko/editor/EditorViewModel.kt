package cz.majkey.perko.editor

import android.app.Application
import androidx.ink.strokes.Stroke
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.majkey.perko.data.NotebookEntity
import cz.majkey.perko.data.PageEntity
import cz.majkey.perko.data.PerkoDatabase
import cz.majkey.perko.data.PerkoRepository
import cz.majkey.perko.data.StrokeEntity
import cz.majkey.perko.data.StrokePayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class EditorUiState(
    val notebook: NotebookEntity? = null,
    val pages: List<PageEntity> = emptyList(),
    val strokes: List<StrokeEntity> = emptyList(),
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
}

private data class EditorControls(
    val tool: EditorTool = EditorTool.PEN,
    val selectedStrokeIds: Set<String> = emptySet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val failed: Boolean = false,
)

internal class EditorViewModel(application: Application, private val notebookId: String) :
    AndroidViewModel(application) {
    private val repository = PerkoRepository(PerkoDatabase.get(application))
    private val selectedPageId = MutableStateFlow<String?>(null)
    private val controls = MutableStateFlow(EditorControls())
    private var historyPageId: String? = null
    private var pageHistory: PageHistory<List<StrokeEntity>>? = null

    val state =
        combine(
                repository.observeNotebook(notebookId),
                repository.observePages(notebookId),
                repository.observeStrokes(notebookId),
                selectedPageId,
                controls,
            ) { notebook, pages, strokes, selected, editorControls ->
                val validSelection = selected?.takeIf { id -> pages.any { it.id == id } }
                EditorUiState(
                    notebook = notebook,
                    pages = pages,
                    strokes = strokes,
                    selectedPageId = validSelection ?: pages.firstOrNull()?.id,
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
        resetHistory()
    }

    fun selectTool(tool: EditorTool) {
        controls.value =
            controls.value.copy(
                tool = tool,
                selectedStrokeIds = if (tool == EditorTool.LASSO) controls.value.selectedStrokeIds else emptySet(),
            )
    }

    fun addPage() = mutate {
        selectedPageId.value = repository.addPage(notebookId)
        resetHistory()
    }

    fun duplicatePage(id: String) = mutate {
        selectedPageId.value = repository.duplicatePage(id)
        resetHistory()
    }

    fun deletePage(id: String) = mutate {
        val wasSelected = state.value.selectedPage?.id == id
        repository.deletePage(id)
        if (wasSelected || historyPageId == id) resetHistory()
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
        history.push(repository.getStrokes(pageId))
        updateHistoryControls(history)
    }

    fun eraseStrokes(pageId: String, points: List<CanvasPoint>) = mutate {
        if (points.isEmpty()) return@mutate
        val history = history(pageId)
        val ids =
            history.current
                .map(StrokeEntity::toStrokePath)
                .filter { stroke -> points.any { point -> hitStroke(point, 16f, stroke) } }
                .mapTo(mutableSetOf(), StrokePath::id)
        if (ids.isEmpty()) return@mutate
        repository.deleteStrokes(pageId, ids)
        history.push(repository.getStrokes(pageId))
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
            history.current
                .filter { it.id in selectedIds }
                .flatMap { it.toStrokePath().points }
        if (selectedPoints.isEmpty()) return@mutate
        val page = requireNotNull(state.value.pages.firstOrNull { it.id == pageId })
        val dx = delta.x.coerceIn(-selectedPoints.minOf { it.x }, page.widthPoints - selectedPoints.maxOf { it.x })
        val dy = delta.y.coerceIn(-selectedPoints.minOf { it.y }, page.heightPoints - selectedPoints.maxOf { it.y })
        if (dx == 0f && dy == 0f) return@mutate
        val moved = history.current.map { stroke -> if (stroke.id in selectedIds) stroke.translated(dx, dy) else stroke }
        repository.replaceStrokes(pageId, moved)
        history.push(moved)
        updateHistoryControls(history)
    }

    fun undo() {
        val pageId = state.value.selectedPage?.id ?: return
        mutate {
            val history = history(pageId)
            val snapshot = history.undo() ?: return@mutate
            repository.replaceStrokes(pageId, snapshot)
            controls.value = controls.value.copy(selectedStrokeIds = emptySet())
            updateHistoryControls(history)
        }
    }

    fun redo() {
        val pageId = state.value.selectedPage?.id ?: return
        mutate {
            val history = history(pageId)
            val snapshot = history.redo() ?: return@mutate
            repository.replaceStrokes(pageId, snapshot)
            controls.value = controls.value.copy(selectedStrokeIds = emptySet())
            updateHistoryControls(history)
        }
    }

    private fun history(pageId: String): PageHistory<List<StrokeEntity>> {
        if (historyPageId != pageId || pageHistory == null) {
            historyPageId = pageId
            pageHistory = PageHistory(state.value.strokes.filter { it.pageId == pageId })
        }
        return requireNotNull(pageHistory)
    }

    private fun resetHistory() {
        historyPageId = null
        pageHistory = null
        controls.value =
            controls.value.copy(selectedStrokeIds = emptySet(), canUndo = false, canRedo = false)
    }

    private fun updateHistoryControls(history: PageHistory<List<StrokeEntity>>) {
        controls.value = controls.value.copy(canUndo = history.canUndo, canRedo = history.canRedo)
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            val didFail = runCatching { block() }.isFailure
            controls.value = controls.value.copy(failed = didFail)
        }
    }
}
