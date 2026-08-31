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
        """
        WITH matches AS (
            SELECT pages.id AS pageId, pages.pageIndex AS pageIndex,
                SUBSTR(pages.title, 1, :snippetLength) AS text, 0 AS sourceOrder
            FROM pages
            WHERE pages.notebookId = :notebookId
              AND pages.title IS NOT NULL
              AND pages.title GLOB :globPattern
            UNION ALL
            SELECT pages.id, pages.pageIndex, SUBSTR(chapters.title, 1, :snippetLength), 1
            FROM pages
            INNER JOIN chapters ON chapters.id = pages.chapterId
            WHERE pages.notebookId = :notebookId
              AND chapters.notebookId = pages.notebookId
              AND chapters.title GLOB :globPattern
            UNION ALL
            SELECT pages.id, pages.pageIndex, SUBSTR(blocks.text, 1, :snippetLength), 2
            FROM pages
            INNER JOIN blocks ON blocks.pageId = pages.id
            WHERE pages.notebookId = :notebookId
              AND blocks.text IS NOT NULL
              AND blocks.text GLOB :globPattern
            UNION ALL
            SELECT pages.id, pages.pageIndex, SUBSTR(elements.text, 1, :snippetLength), 3
            FROM pages
            INNER JOIN elements ON elements.pageId = pages.id
            WHERE pages.notebookId = :notebookId
              AND elements.kind IN ('TEXT', 'IMAGE')
              AND elements.text IS NOT NULL
              AND elements.text GLOB :globPattern
            UNION ALL
            SELECT pages.id, pages.pageIndex, SUBSTR(elements.expression, 1, :snippetLength), 4
            FROM pages
            INNER JOIN elements ON elements.pageId = pages.id
            WHERE pages.notebookId = :notebookId
              AND elements.kind = 'MATH'
              AND elements.expression IS NOT NULL
              AND elements.expression GLOB :globPattern
            UNION ALL
            SELECT pages.id, pages.pageIndex, SUBSTR(elements.resultText, 1, :snippetLength), 5
            FROM pages
            INNER JOIN elements ON elements.pageId = pages.id
            WHERE pages.notebookId = :notebookId
              AND elements.kind = 'MATH'
              AND elements.resultText IS NOT NULL
              AND elements.resultText GLOB :globPattern
        ), priorities AS (
            SELECT pageId, pageIndex, MIN(sourceOrder) AS sourceOrder
            FROM matches
            GROUP BY pageId, pageIndex
        )
        SELECT matches.pageId, matches.pageIndex, MIN(matches.text) AS text
        FROM matches
        INNER JOIN priorities
            ON priorities.pageId = matches.pageId
            AND priorities.pageIndex = matches.pageIndex
            AND priorities.sourceOrder = matches.sourceOrder
        GROUP BY matches.pageId, matches.pageIndex
        ORDER BY matches.pageIndex
        LIMIT :limit
        """,
    )
    suspend fun searchPageText(
        notebookId: String,
        globPattern: String,
        snippetLength: Int,
        limit: Int,
    ): List<PageTextMatch>
}
