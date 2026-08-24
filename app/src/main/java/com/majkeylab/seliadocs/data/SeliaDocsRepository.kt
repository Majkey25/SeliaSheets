package com.majkeylab.seliadocs.data

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull

internal class SeliaDocsRepository(
    private val database: SeliaDocsDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val notebooks = database.notebookDao()
    private val pageContent = database.pageDao()

    fun observeNotebooks(query: String = "", trash: Boolean = false): Flow<List<NotebookEntity>> =
        notebooks.observeNotebooks(query.trim(), trash)

    fun observeNotebook(id: String): Flow<NotebookEntity> =
        notebooks.observeNotebook(id).filterNotNull()

    fun observePages(notebookId: String): Flow<List<PageEntity>> =
        notebooks.observePages(notebookId)

    fun observeStrokes(notebookId: String): Flow<List<StrokeEntity>> =
        pageContent.observeStrokes(notebookId)

    fun observeElements(notebookId: String): Flow<List<ElementEntity>> =
        pageContent.observeElements(notebookId)

    suspend fun createNotebook(request: CreateNotebookRequest): String {
        val title = request.title.trim()
        require(title.isNotEmpty())
        val notebookId = idFactory()
        val pageId = idFactory()
        val now = clock()
        val (width, height) = pageSize(request.orientation)
        database.withTransaction {
            notebooks.insertNotebook(
                NotebookEntity(
                    id = notebookId,
                    title = title,
                    coverColor = request.coverColor.name,
                    coverPattern = request.coverPattern.name,
                    defaultPaper = request.paper.name,
                    orientation = request.orientation.name,
                    fingerDrawing = request.fingerDrawing,
                    favorite = false,
                    createdAt = now,
                    updatedAt = now,
                    trashedAt = null,
                ),
            )
            notebooks.insertPage(
                PageEntity(
                    id = pageId,
                    notebookId = notebookId,
                    pageIndex = 0,
                    paper = request.paper.name,
                    widthPoints = width,
                    heightPoints = height,
                ),
            )
        }
        return notebookId
    }

    suspend fun getNotebook(id: String): NotebookEntity =
        requireNotNull(notebooks.getNotebook(id)) { "Notebook not found" }

    suspend fun getPages(notebookId: String): List<PageEntity> = notebooks.getPages(notebookId)

    suspend fun getStrokes(pageId: String): List<StrokeEntity> = pageContent.getStrokes(pageId)

    suspend fun getElements(pageId: String): List<ElementEntity> = pageContent.getElements(pageId)

    suspend fun getAssetReferenceCount(assetId: String): Int =
        pageContent.getAssetReferenceCount(assetId)

    suspend fun addStroke(pageId: String, payload: StrokePayload): String {
        val id = idFactory()
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            pageContent.insertStroke(
                StrokeEntity(
                    id = id,
                    pageId = pageId,
                    zIndex = (pageContent.getMaxStrokeZIndex(pageId) ?: -1) + 1,
                    brushKind = payload.brushKind,
                    colorArgb = payload.colorArgb,
                    size = payload.size,
                    epsilon = payload.epsilon,
                    inputs = payload.inputs,
                ),
            )
            touch(requireNotNull(notebooks.getNotebook(page.notebookId)))
        }
        return id
    }

    suspend fun deleteStrokes(pageId: String, ids: Set<String>) {
        if (ids.isEmpty()) return
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            pageContent.deleteStrokes(pageId, ids)
            touch(requireNotNull(notebooks.getNotebook(page.notebookId)))
        }
    }

    suspend fun replaceStrokes(pageId: String, strokes: List<StrokeEntity>) {
        require(strokes.all { it.pageId == pageId })
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            pageContent.deleteStrokes(pageId)
            if (strokes.isNotEmpty()) pageContent.insertStrokes(strokes)
            touch(requireNotNull(notebooks.getNotebook(page.notebookId)))
        }
    }

    suspend fun addElement(pageId: String, draft: ElementDraft): String {
        validateElement(draft)
        val id = idFactory()
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            pageContent.insertElement(
                elementFromDraft(
                    id,
                    pageId,
                    (pageContent.getMaxElementZIndex(pageId) ?: -1) + 1,
                    draft,
                ),
            )
            touch(requireNotNull(notebooks.getNotebook(page.notebookId)))
        }
        return id
    }

    suspend fun replaceStrokesWithElement(
        pageId: String,
        strokeIds: Set<String>,
        draft: ElementDraft,
    ): String {
        require(strokeIds.isNotEmpty())
        validateElement(draft)
        val id = idFactory()
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            val existingIds = pageContent.getStrokes(pageId).mapTo(mutableSetOf(), StrokeEntity::id)
            require(existingIds.containsAll(strokeIds)) { "Stroke not found" }
            pageContent.deleteStrokes(pageId, strokeIds)
            pageContent.insertElement(
                elementFromDraft(
                    id,
                    pageId,
                    (pageContent.getMaxElementZIndex(pageId) ?: -1) + 1,
                    draft,
                ),
            )
            touch(requireNotNull(notebooks.getNotebook(page.notebookId)))
        }
        return id
    }

    suspend fun updateElement(element: ElementEntity) {
        validateElement(element)
        database.withTransaction {
            requireNotNull(pageContent.getElement(element.id)) { "Element not found" }
            pageContent.updateElement(element)
            val page = requireNotNull(notebooks.getPage(element.pageId)) { "Page not found" }
            touch(requireNotNull(notebooks.getNotebook(page.notebookId)))
        }
    }

    suspend fun deleteElement(id: String): ElementEntity {
        val element = requireNotNull(pageContent.getElement(id)) { "Element not found" }
        database.withTransaction {
            pageContent.deleteElement(element)
            val page = requireNotNull(notebooks.getPage(element.pageId)) { "Page not found" }
            touch(requireNotNull(notebooks.getNotebook(page.notebookId)))
        }
        return element
    }

    suspend fun replaceElements(pageId: String, elements: List<ElementEntity>) {
        require(elements.all { it.pageId == pageId })
        elements.forEach(::validateElement)
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            pageContent.deleteElements(pageId)
            if (elements.isNotEmpty()) pageContent.insertElements(elements)
            touch(requireNotNull(notebooks.getNotebook(page.notebookId)))
        }
    }

    suspend fun replacePageContent(
        pageId: String,
        strokes: List<StrokeEntity>,
        elements: List<ElementEntity>,
    ) {
        require(strokes.all { it.pageId == pageId } && elements.all { it.pageId == pageId })
        elements.forEach(::validateElement)
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            pageContent.deleteStrokes(pageId)
            pageContent.deleteElements(pageId)
            if (strokes.isNotEmpty()) pageContent.insertStrokes(strokes)
            if (elements.isNotEmpty()) pageContent.insertElements(elements)
            touch(requireNotNull(notebooks.getNotebook(page.notebookId)))
        }
    }

    suspend fun addPage(notebookId: String): String {
        val notebook = getNotebook(notebookId)
        val id = idFactory()
        val orientation = PageOrientation.valueOf(notebook.orientation)
        val (width, height) = pageSize(orientation)
        database.withTransaction {
            val index = (notebooks.getMaxPageIndex(notebookId) ?: -1) + 1
            notebooks.insertPage(
                PageEntity(
                    id = id,
                    notebookId = notebookId,
                    pageIndex = index,
                    paper = notebook.defaultPaper,
                    widthPoints = width,
                    heightPoints = height,
                ),
            )
            touch(notebook)
        }
        return id
    }

    suspend fun movePage(notebookId: String, fromIndex: Int, toIndex: Int) {
        database.withTransaction {
            val pages = notebooks.getPages(notebookId).toMutableList()
            require(fromIndex in pages.indices && toIndex in pages.indices)
            pages.add(toIndex, pages.removeAt(fromIndex))
            replacePageOrder(notebookId, pages)
            touch(requireNotNull(notebooks.getNotebook(notebookId)))
        }
    }

    suspend fun duplicatePage(pageId: String): String {
        val newId = idFactory()
        database.withTransaction {
            val source = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            val sourceStrokes = pageContent.getStrokes(pageId)
            val sourceElements = pageContent.getElements(pageId)
            val pages = notebooks.getPages(source.notebookId).toMutableList()
            val duplicate = source.copy(id = newId, pageIndex = source.pageIndex + 1)
            pages.add(source.pageIndex + 1, duplicate)
            notebooks.insertPage(duplicate.copy(pageIndex = pages.size + 10_000))
            sourceStrokes.forEach { stroke ->
                pageContent.insertStroke(stroke.copy(id = idFactory(), pageId = newId))
            }
            sourceElements.forEach { element ->
                pageContent.insertElement(element.copy(id = idFactory(), pageId = newId))
            }
            replacePageOrder(source.notebookId, pages)
            touch(requireNotNull(notebooks.getNotebook(source.notebookId)))
        }
        return newId
    }

    suspend fun deletePage(pageId: String) {
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            require(notebooks.getPageCount(page.notebookId) > 1) { "A notebook needs one page" }
            notebooks.deletePage(page)
            replacePageOrder(page.notebookId, notebooks.getPages(page.notebookId))
            touch(requireNotNull(notebooks.getNotebook(page.notebookId)))
        }
    }

    suspend fun renameNotebook(id: String, title: String) {
        val normalized = title.trim()
        require(normalized.isNotEmpty())
        notebooks.updateNotebook(getNotebook(id).copy(title = normalized, updatedAt = clock()))
    }

    suspend fun setFavorite(id: String, favorite: Boolean) {
        notebooks.updateNotebook(getNotebook(id).copy(favorite = favorite, updatedAt = clock()))
    }

    suspend fun setTrashed(id: String, trashed: Boolean) {
        val now = clock()
        notebooks.updateNotebook(
            getNotebook(id).copy(trashedAt = now.takeIf { trashed }, updatedAt = now),
        )
    }

    suspend fun deleteNotebook(id: String) {
        notebooks.deleteNotebook(getNotebook(id))
    }

    suspend fun loadNotebook(id: String): NotebookContent {
        val notebook = getNotebook(id)
        val pages = getPages(id)
        val pageIds = pages.map(PageEntity::id)
        return NotebookContent(
            notebook = notebook,
            pages = pages,
            strokes = pageContent.getStrokes(pageIds),
            elements = pageContent.getElements(pageIds),
        )
    }

    private suspend fun replacePageOrder(notebookId: String, pages: List<PageEntity>) {
        notebooks.offsetPageIndexes(notebookId, 10_000)
        pages.forEachIndexed { index, page -> notebooks.updatePageIndex(page.id, index) }
    }

    private suspend fun touch(notebook: NotebookEntity) {
        notebooks.updateNotebook(notebook.copy(updatedAt = clock()))
    }

    private fun pageSize(orientation: PageOrientation): Pair<Int, Int> =
        if (orientation == PageOrientation.PORTRAIT) 595 to 842 else 842 to 595

    private fun validateElement(draft: ElementDraft) {
        require(
            draft.x.isFinite() &&
                draft.y.isFinite() &&
                draft.width.isFinite() &&
                draft.height.isFinite() &&
                draft.rotation.isFinite() &&
                draft.width > 0f &&
                draft.height > 0f,
        )
        when (draft.kind) {
            ElementKind.TEXT -> require(!draft.text.isNullOrBlank() && draft.text.length <= 10_000)
            ElementKind.IMAGE -> require(!draft.assetId.isNullOrBlank())
            ElementKind.SHAPE -> require(!draft.shapeKind.isNullOrBlank())
            ElementKind.MATH -> require(!draft.expression.isNullOrBlank() && !draft.resultText.isNullOrBlank())
        }
    }

    private fun validateElement(element: ElementEntity) {
        validateElement(
            ElementDraft(
                kind = ElementKind.valueOf(element.kind),
                x = element.x,
                y = element.y,
                width = element.width,
                height = element.height,
                rotation = element.rotation,
                text = element.text,
                assetId = element.assetId,
                shapeKind = element.shapeKind,
                expression = element.expression,
                resultText = element.resultText,
            ),
        )
    }

    private fun elementFromDraft(
        id: String,
        pageId: String,
        zIndex: Int,
        draft: ElementDraft,
    ) =
        ElementEntity(
            id = id,
            pageId = pageId,
            zIndex = zIndex,
            kind = draft.kind.name,
            x = draft.x,
            y = draft.y,
            width = draft.width,
            height = draft.height,
            rotation = draft.rotation,
            text = draft.text,
            assetId = draft.assetId,
            shapeKind = draft.shapeKind,
            expression = draft.expression,
            resultText = draft.resultText,
        )
}
