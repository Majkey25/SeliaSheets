package com.majkeylab.seliadocs.data

import androidx.room.withTransaction
import com.majkeylab.seliadocs.editor.clampElementTransform
import com.majkeylab.seliadocs.editor.transform
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

    fun observeChapters(notebookId: String): Flow<List<ChapterEntity>> =
        notebooks.observeChapters(notebookId)

    fun observePdfSources(notebookId: String): Flow<List<PdfSourceEntity>> =
        notebooks.observePdfSources(notebookId)

    fun observeStrokes(pageId: String): Flow<List<StrokeEntity>> =
        pageContent.observeStrokes(pageId)

    fun observeElements(pageId: String): Flow<List<ElementEntity>> =
        pageContent.observeElements(pageId)

    fun observeBlocks(pageId: String): Flow<List<BlockEntity>> =
        pageContent.observeBlocks(pageId)

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
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        return notebookId
    }

    suspend fun getNotebook(id: String): NotebookEntity =
        requireNotNull(notebooks.getNotebook(id)) { "Notebook not found" }

    suspend fun getAllNotebooks(): List<NotebookEntity> = notebooks.getAllNotebooks()

    suspend fun getPages(notebookId: String): List<PageEntity> = notebooks.getPages(notebookId)

    suspend fun getChapters(notebookId: String): List<ChapterEntity> = notebooks.getChapters(notebookId)

    suspend fun getPdfSources(notebookId: String): List<PdfSourceEntity> = notebooks.getPdfSources(notebookId)

    suspend fun getPdfSource(id: String): PdfSourceEntity =
        requireNotNull(notebooks.getPdfSource(id)) { "PDF source not found" }

    suspend fun getStrokes(pageId: String): List<StrokeEntity> = pageContent.getStrokes(pageId)

    suspend fun getElements(pageId: String): List<ElementEntity> = pageContent.getElements(pageId)

    suspend fun getBlocks(pageId: String): List<BlockEntity> = pageContent.getBlocks(pageId)

    suspend fun searchPageText(notebookId: String, query: String): List<PageTextMatch> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        val escaped = normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return pageContent.searchPageText(notebookId, escaped)
    }

    suspend fun getAssetReferenceCount(assetId: String): Int =
        pageContent.getAssetReferenceCount(assetId)

    suspend fun getReferencedAssetIds(): Set<String> =
        (pageContent.getAllElementAssetIds() + notebooks.getAllPdfAssetIds()).toSet()

    suspend fun importPdf(
        notebookId: String,
        assetId: String,
        displayName: String,
        byteSize: Long,
        sha256: String,
        pages: List<PdfPageSpec>,
    ): PdfImportResult {
        require(assetId.isNotBlank() && displayName.isNotBlank() && byteSize > 0L)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(pages.isNotEmpty() && pages.size <= 2_000)
        require(pages.all { it.widthPoints in 1..14_400 && it.heightPoints in 1..14_400 })
        val sourceId = idFactory()
        val pageIds = List(pages.size) { idFactory() }
        val now = clock()
        database.withTransaction {
            val notebook = getNotebook(notebookId)
            notebooks.insertPdfSource(
                PdfSourceEntity(
                    id = sourceId,
                    notebookId = notebookId,
                    assetId = assetId,
                    displayName = displayName.trim().take(255),
                    pageCount = pages.size,
                    byteSize = byteSize,
                    sha256 = sha256,
                    createdAt = now,
                ),
            )
            val firstIndex = (notebooks.getMaxPageIndex(notebookId) ?: -1) + 1
            pages.forEachIndexed { index, spec ->
                notebooks.insertPage(
                    PageEntity(
                        id = pageIds[index],
                        notebookId = notebookId,
                        pageIndex = firstIndex + index,
                        paper = PaperTemplate.BLANK.name,
                        widthPoints = spec.widthPoints,
                        heightPoints = spec.heightPoints,
                        title = displayName.substringBeforeLast('.').take(160).takeIf(String::isNotBlank),
                        pageMode = PageMode.PDF.name,
                        createdAt = now,
                        updatedAt = now,
                        pdfSourceId = sourceId,
                        pdfPageIndex = index,
                    ),
                )
            }
            touch(notebook)
        }
        return PdfImportResult(sourceId, pageIds)
    }

    suspend fun updatePageText(pageId: String, text: String) {
        require(text.length <= PAGE_TEXT_MAX_LENGTH)
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            require(pageTextFits(text, page.widthPoints, page.heightPoints)) { "Page text exceeds printable area" }
            val blocks = pageContent.getBlocks(pageId)
            require(blocks.size <= 1) { "Paper page text must use one block" }
            val existing = blocks.firstOrNull()
            when {
                text.isEmpty() -> pageContent.deleteBlocks(pageId)
                existing == null ->
                    pageContent.insertBlock(
                        BlockEntity(
                            id = idFactory(),
                            pageId = pageId,
                            orderIndex = 0,
                            kind = BlockKind.PARAGRAPH.name,
                            text = text,
                            checked = false,
                            indent = 0,
                            alignment = "START",
                            payloadId = null,
                        ),
                    )
                else -> pageContent.updateBlock(existing.copy(text = text))
            }
            val now = clock()
            notebooks.updatePage(page.copy(updatedAt = now))
            touch(requireNotNull(notebooks.getNotebook(page.notebookId)))
        }
    }

    suspend fun createChapter(notebookId: String, title: String, colorArgb: Int): String {
        val normalized = title.trim()
        require(normalized.isNotEmpty() && normalized.length <= 120)
        val id = idFactory()
        database.withTransaction {
            val notebook = getNotebook(notebookId)
            notebooks.insertChapter(
                ChapterEntity(
                    id = id,
                    notebookId = notebookId,
                    title = normalized,
                    colorArgb = colorArgb,
                    orderIndex = (notebooks.getMaxChapterIndex(notebookId) ?: -1) + 1,
                ),
            )
            touch(notebook)
        }
        return id
    }

    suspend fun renameChapter(id: String, title: String) {
        val normalized = title.trim()
        require(normalized.isNotEmpty() && normalized.length <= 120)
        database.withTransaction {
            val chapter = requireNotNull(notebooks.getChapter(id)) { "Chapter not found" }
            notebooks.updateChapter(chapter.copy(title = normalized))
            touch(getNotebook(chapter.notebookId))
        }
    }

    suspend fun deleteChapter(id: String) {
        database.withTransaction {
            val chapter = requireNotNull(notebooks.getChapter(id)) { "Chapter not found" }
            notebooks.clearChapterFromPages(id)
            notebooks.deleteChapter(chapter)
            replaceChapterOrder(chapter.notebookId, notebooks.getChapters(chapter.notebookId))
            touch(getNotebook(chapter.notebookId))
        }
    }

    suspend fun assignPageToChapter(pageId: String, chapterId: String?) {
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            val chapter = chapterId?.let { requireNotNull(notebooks.getChapter(it)) { "Chapter not found" } }
            require(chapter == null || chapter.notebookId == page.notebookId) { "Chapter belongs to another notebook" }
            val now = clock()
            notebooks.updatePage(page.copy(chapterId = chapterId, updatedAt = now))
            touch(getNotebook(page.notebookId))
        }
    }

    suspend fun renamePage(pageId: String, title: String?) {
        val normalized = title?.trim()?.takeIf(String::isNotEmpty)
        require(normalized == null || normalized.length <= 160)
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            notebooks.updatePage(page.copy(title = normalized, updatedAt = clock()))
            touch(getNotebook(page.notebookId))
        }
    }

    suspend fun setPageBookmarked(pageId: String, bookmarked: Boolean) {
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            notebooks.updatePage(page.copy(bookmarked = bookmarked, updatedAt = clock()))
            touch(getNotebook(page.notebookId))
        }
    }

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

    suspend fun duplicateElement(id: String): String {
        val duplicateId = idFactory()
        database.withTransaction {
            val source = requireNotNull(pageContent.getElement(id)) { "Element not found" }
            val page = requireNotNull(notebooks.getPage(source.pageId)) { "Page not found" }
            val transform =
                requireNotNull(
                    clampElementTransform(
                        source.transform().copy(x = source.x + 12f, y = source.y + 12f),
                        page.widthPoints.toFloat(),
                        page.heightPoints.toFloat(),
                    ),
                )
            pageContent.insertElement(
                source.copy(
                    id = duplicateId,
                    zIndex = (pageContent.getMaxElementZIndex(source.pageId) ?: -1) + 1,
                    x = transform.x,
                    y = transform.y,
                    width = transform.width,
                    height = transform.height,
                    rotation = transform.rotation,
                ),
            )
            touch(requireNotNull(notebooks.getNotebook(page.notebookId)))
        }
        return duplicateId
    }

    suspend fun moveElementForward(id: String) {
        database.withTransaction {
            val selected = requireNotNull(pageContent.getElement(id)) { "Element not found" }
            val next =
                pageContent.getElements(selected.pageId)
                    .firstOrNull { element -> element.zIndex > selected.zIndex }
                    ?: return@withTransaction
            pageContent.updateElement(selected.copy(zIndex = next.zIndex))
            pageContent.updateElement(next.copy(zIndex = selected.zIndex))
            val page = requireNotNull(notebooks.getPage(selected.pageId)) { "Page not found" }
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
        blocks: List<BlockEntity>,
    ) {
        require(
            strokes.all { it.pageId == pageId } &&
                elements.all { it.pageId == pageId } &&
                blocks.all { it.pageId == pageId },
        )
        elements.forEach(::validateElement)
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            pageContent.deleteStrokes(pageId)
            pageContent.deleteElements(pageId)
            pageContent.deleteBlocks(pageId)
            if (strokes.isNotEmpty()) pageContent.insertStrokes(strokes)
            if (elements.isNotEmpty()) pageContent.insertElements(elements)
            if (blocks.isNotEmpty()) pageContent.insertBlocks(blocks)
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
                    createdAt = clock(),
                    updatedAt = clock(),
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
            val sourceBlocks = pageContent.getBlocks(pageId)
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
            sourceBlocks.forEach { block ->
                pageContent.insertBlock(block.copy(id = idFactory(), pageId = newId))
            }
            replacePageOrder(source.notebookId, pages)
            touch(requireNotNull(notebooks.getNotebook(source.notebookId)))
        }
        return newId
    }

    suspend fun deletePage(pageId: String): String? =
        database.withTransaction {
            val page = requireNotNull(notebooks.getPage(pageId)) { "Page not found" }
            require(notebooks.getPageCount(page.notebookId) > 1) { "A notebook needs one page" }
            notebooks.deletePage(page)
            replacePageOrder(page.notebookId, notebooks.getPages(page.notebookId))
            touch(requireNotNull(notebooks.getNotebook(page.notebookId)))
            page.pdfSourceId?.let { sourceId ->
                if (notebooks.getPdfPageReferenceCount(sourceId) == 0) {
                    val source = requireNotNull(notebooks.getPdfSource(sourceId))
                    notebooks.deletePdfSource(source)
                    return@withTransaction source.assetId
                }
            }
            null
        }

    suspend fun renameNotebook(id: String, title: String) {
        val normalized = title.trim()
        require(normalized.isNotEmpty())
        notebooks.updateNotebook(getNotebook(id).copy(title = normalized, updatedAt = clock()))
    }

    suspend fun setFingerDrawing(notebookId: String, enabled: Boolean) {
        database.withTransaction {
            touch(getNotebook(notebookId).copy(fingerDrawing = enabled))
        }
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

    suspend fun loadNotebook(id: String): NotebookContent =
        database.withTransaction {
            val notebook = getNotebook(id)
            val pages = getPages(id)
            NotebookContent(
                notebook = notebook,
                pages = pages,
                strokes = pageContent.getStrokesForNotebook(id),
                elements = pageContent.getElementsForNotebook(id),
                blocks = pageContent.getBlocksForNotebook(id),
                chapters = notebooks.getChapters(id),
                pdfSources = notebooks.getPdfSources(id),
            )
        }

    private suspend fun replacePageOrder(notebookId: String, pages: List<PageEntity>) {
        notebooks.offsetPageIndexes(notebookId, 10_000)
        pages.forEachIndexed { index, page -> notebooks.updatePageIndex(page.id, index) }
    }

    private suspend fun replaceChapterOrder(notebookId: String, chapters: List<ChapterEntity>) {
        if (chapters.isEmpty()) return
        notebooks.offsetChapterIndexes(notebookId, chapters.maxOf(ChapterEntity::orderIndex) + 1)
        chapters.forEachIndexed { index, chapter -> notebooks.updateChapterIndex(chapter.id, index) }
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
