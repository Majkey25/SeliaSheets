package cz.majkey.perko.data

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

    @Update
    suspend fun updateStroke(stroke: StrokeEntity)

    @Delete
    suspend fun deleteStroke(stroke: StrokeEntity)

    @Query("SELECT * FROM strokes WHERE pageId IN (:pageIds) ORDER BY zIndex")
    suspend fun getStrokes(pageIds: List<String>): List<StrokeEntity>

    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY zIndex")
    suspend fun getStrokes(pageId: String): List<StrokeEntity>

    @Query("SELECT MAX(zIndex) FROM strokes WHERE pageId = :pageId")
    suspend fun getMaxStrokeZIndex(pageId: String): Int?

    @Query(
        """
        SELECT strokes.* FROM strokes
        INNER JOIN pages ON pages.id = strokes.pageId
        WHERE pages.notebookId = :notebookId
        ORDER BY strokes.zIndex
        """,
    )
    fun observeStrokes(notebookId: String): Flow<List<StrokeEntity>>

    @Insert
    suspend fun insertElement(element: ElementEntity)

    @Update
    suspend fun updateElement(element: ElementEntity)

    @Delete
    suspend fun deleteElement(element: ElementEntity)

    @Query("SELECT * FROM elements WHERE pageId IN (:pageIds) ORDER BY zIndex")
    suspend fun getElements(pageIds: List<String>): List<ElementEntity>
}
