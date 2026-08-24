package cz.majkey.perko.data

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull

internal class PerkoRepository(
    private val database: PerkoDatabase,
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

    suspend fun addStroke(pageId: String, payload: StrokePayload): String {
        val id = idFactory()
        database.withTransaction {
            requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
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
        }
        return id
    }

    suspend fun deleteStrokes(pageId: String, ids: Set<String>) {
        if (ids.isEmpty()) return
        pageContent.deleteStrokes(pageId, ids)
    }

    suspend fun replaceStrokes(pageId: String, strokes: List<StrokeEntity>) {
        require(strokes.all { it.pageId == pageId })
        database.withTransaction {
            requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            pageContent.deleteStrokes(pageId)
            if (strokes.isNotEmpty()) pageContent.insertStrokes(strokes)
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
            val pages = notebooks.getPages(source.notebookId).toMutableList()
            val duplicate = source.copy(id = newId, pageIndex = source.pageIndex + 1)
            pages.add(source.pageIndex + 1, duplicate)
            notebooks.insertPage(duplicate.copy(pageIndex = pages.size + 10_000))
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
}
