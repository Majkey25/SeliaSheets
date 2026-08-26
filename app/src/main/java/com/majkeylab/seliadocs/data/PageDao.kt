package com.majkeylab.seliadocs.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface PageDao {
    @Insert
    suspend fun insertStroke(stroke: StrokeEntity)

    @Insert
    suspend fun insertStrokes(strokes: List<StrokeEntity>)

    @Update
    suspend fun updateStroke(stroke: StrokeEntity)

    @Delete
    suspend fun deleteStroke(stroke: StrokeEntity)

    @Query("SELECT * FROM strokes WHERE pageId IN (SELECT id FROM pages WHERE notebookId = :notebookId) ORDER BY zIndex")
    suspend fun getStrokesForNotebook(notebookId: String): List<StrokeEntity>

    @Query("SELECT id FROM strokes")
    suspend fun getAllStrokeIds(): List<String>

    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY zIndex")
    suspend fun getStrokes(pageId: String): List<StrokeEntity>

    @Query("SELECT MAX(zIndex) FROM strokes WHERE pageId = :pageId")
    suspend fun getMaxStrokeZIndex(pageId: String): Int?

    @Query("DELETE FROM strokes WHERE pageId = :pageId AND id IN (:ids)")
    suspend fun deleteStrokes(pageId: String, ids: Set<String>)

    @Query("DELETE FROM strokes WHERE pageId = :pageId")
    suspend fun deleteStrokes(pageId: String)

    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY zIndex")
    fun observeStrokes(pageId: String): Flow<List<StrokeEntity>>

    @Insert
    suspend fun insertElement(element: ElementEntity)

    @Insert
    suspend fun insertElements(elements: List<ElementEntity>)

    @Update
    suspend fun updateElement(element: ElementEntity)

    @Delete
    suspend fun deleteElement(element: ElementEntity)

    @Query("DELETE FROM elements WHERE pageId = :pageId")
    suspend fun deleteElements(pageId: String)

    @Query("SELECT * FROM elements WHERE pageId IN (SELECT id FROM pages WHERE notebookId = :notebookId) ORDER BY zIndex")
    suspend fun getElementsForNotebook(notebookId: String): List<ElementEntity>

    @Query("SELECT id FROM elements")
    suspend fun getAllElementIds(): List<String>

    @Query("SELECT * FROM elements WHERE pageId = :pageId ORDER BY zIndex")
    suspend fun getElements(pageId: String): List<ElementEntity>

    @Query("SELECT * FROM elements WHERE id = :id")
    suspend fun getElement(id: String): ElementEntity?

    @Query("SELECT MAX(zIndex) FROM elements WHERE pageId = :pageId")
    suspend fun getMaxElementZIndex(pageId: String): Int?

    @Query("SELECT * FROM elements WHERE pageId = :pageId ORDER BY zIndex")
    fun observeElements(pageId: String): Flow<List<ElementEntity>>

    @Query("SELECT COUNT(*) FROM elements WHERE assetId = :assetId")
    suspend fun getAssetReferenceCount(assetId: String): Int

    @Query("SELECT DISTINCT assetId FROM elements WHERE assetId IS NOT NULL")
    suspend fun getAllElementAssetIds(): List<String>

    @Insert
    suspend fun insertBlock(block: BlockEntity)

    @Insert
    suspend fun insertBlocks(blocks: List<BlockEntity>)

    @Update
    suspend fun updateBlock(block: BlockEntity)

    @Query("DELETE FROM blocks WHERE pageId = :pageId")
    suspend fun deleteBlocks(pageId: String)

    @Query("SELECT * FROM blocks WHERE pageId = :pageId ORDER BY orderIndex")
    suspend fun getBlocks(pageId: String): List<BlockEntity>

    @Query("SELECT id FROM blocks")
    suspend fun getAllBlockIds(): List<String>

    @Query("SELECT * FROM blocks WHERE pageId IN (SELECT id FROM pages WHERE notebookId = :notebookId) ORDER BY pageId, orderIndex")
    suspend fun getBlocksForNotebook(notebookId: String): List<BlockEntity>

    @Query("SELECT * FROM blocks WHERE pageId = :pageId ORDER BY orderIndex")
    fun observeBlocks(pageId: String): Flow<List<BlockEntity>>

    @Query(
        """SELECT pages.id AS pageId, pages.pageIndex AS pageIndex, COALESCE(blocks.text, '') AS text
            FROM blocks
            INNER JOIN pages ON pages.id = blocks.pageId
            WHERE pages.notebookId = :notebookId
              AND blocks.text COLLATE NOCASE LIKE '%' || :escapedQuery || '%' ESCAPE '\'
            ORDER BY pages.pageIndex, blocks.orderIndex
            LIMIT 100
        """,
    )
    suspend fun searchPageText(notebookId: String, escapedQuery: String): List<PageTextMatch>
}
