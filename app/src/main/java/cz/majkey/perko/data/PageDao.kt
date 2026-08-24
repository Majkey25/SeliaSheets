package cz.majkey.perko.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

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

    @Insert
    suspend fun insertElement(element: ElementEntity)

    @Update
    suspend fun updateElement(element: ElementEntity)

    @Delete
    suspend fun deleteElement(element: ElementEntity)

    @Query("SELECT * FROM elements WHERE pageId IN (:pageIds) ORDER BY zIndex")
    suspend fun getElements(pageIds: List<String>): List<ElementEntity>
}
