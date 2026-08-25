package com.majkeylab.seliadocs.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface NotebookDao {
    @Insert
    suspend fun insertNotebook(notebook: NotebookEntity)

    @Update
    suspend fun updateNotebook(notebook: NotebookEntity)

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun getNotebook(id: String): NotebookEntity?

    @Query("SELECT * FROM notebooks ORDER BY createdAt, id")
    suspend fun getAllNotebooks(): List<NotebookEntity>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    fun observeNotebook(id: String): Flow<NotebookEntity?>

    @Query(
        """
        SELECT * FROM notebooks
        WHERE ((:trash = 1 AND trashedAt IS NOT NULL) OR (:trash = 0 AND trashedAt IS NULL))
          AND title LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY favorite DESC, updatedAt DESC
        """,
    )
    fun observeNotebooks(query: String, trash: Boolean): Flow<List<NotebookEntity>>

    @Delete
    suspend fun deleteNotebook(notebook: NotebookEntity)

    @Insert
    suspend fun insertPage(page: PageEntity)

    @Update
    suspend fun updatePage(page: PageEntity)

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getPage(id: String): PageEntity?

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY pageIndex")
    suspend fun getPages(notebookId: String): List<PageEntity>

    @Query("SELECT id FROM pages")
    suspend fun getAllPageIds(): List<String>

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY pageIndex")
    fun observePages(notebookId: String): Flow<List<PageEntity>>

    @Query("SELECT MAX(pageIndex) FROM pages WHERE notebookId = :notebookId")
    suspend fun getMaxPageIndex(notebookId: String): Int?

    @Query("SELECT COUNT(*) FROM pages WHERE notebookId = :notebookId")
    suspend fun getPageCount(notebookId: String): Int

    @Query("UPDATE pages SET pageIndex = pageIndex + :offset WHERE notebookId = :notebookId")
    suspend fun offsetPageIndexes(notebookId: String, offset: Int)

    @Query("UPDATE pages SET pageIndex = :pageIndex WHERE id = :id")
    suspend fun updatePageIndex(id: String, pageIndex: Int)

    @Delete
    suspend fun deletePage(page: PageEntity)

    @Query("DELETE FROM notebooks")
    suspend fun clearNotebooks()

    @Insert
    suspend fun insertChapter(chapter: ChapterEntity)

    @Update
    suspend fun updateChapter(chapter: ChapterEntity)

    @Delete
    suspend fun deleteChapter(chapter: ChapterEntity)

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getChapter(id: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE notebookId = :notebookId ORDER BY orderIndex")
    suspend fun getChapters(notebookId: String): List<ChapterEntity>

    @Query("SELECT id FROM chapters")
    suspend fun getAllChapterIds(): List<String>

    @Query("SELECT * FROM chapters WHERE notebookId = :notebookId ORDER BY orderIndex")
    fun observeChapters(notebookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT MAX(orderIndex) FROM chapters WHERE notebookId = :notebookId")
    suspend fun getMaxChapterIndex(notebookId: String): Int?

    @Query("UPDATE pages SET chapterId = NULL WHERE chapterId = :chapterId")
    suspend fun clearChapterFromPages(chapterId: String)

    @Insert
    suspend fun insertPdfSource(source: PdfSourceEntity)

    @Delete
    suspend fun deletePdfSource(source: PdfSourceEntity)

    @Query("SELECT * FROM pdf_sources WHERE id = :id")
    suspend fun getPdfSource(id: String): PdfSourceEntity?

    @Query("SELECT * FROM pdf_sources WHERE notebookId = :notebookId ORDER BY createdAt, id")
    suspend fun getPdfSources(notebookId: String): List<PdfSourceEntity>

    @Query("SELECT * FROM pdf_sources WHERE notebookId = :notebookId ORDER BY createdAt, id")
    fun observePdfSources(notebookId: String): Flow<List<PdfSourceEntity>>

    @Query("SELECT id FROM pdf_sources")
    suspend fun getAllPdfSourceIds(): List<String>

    @Query("SELECT assetId FROM pdf_sources")
    suspend fun getAllPdfAssetIds(): List<String>

    @Query("SELECT COUNT(*) FROM pages WHERE pdfSourceId = :sourceId")
    suspend fun getPdfPageReferenceCount(sourceId: String): Int
}
