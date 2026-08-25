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
}
