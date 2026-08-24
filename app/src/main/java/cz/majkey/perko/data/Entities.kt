package cz.majkey.perko.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

internal enum class CoverColor { PERIWINKLE, GRAPHITE, SAGE, SALMON, SAND }

internal enum class CoverPattern { SOLID, BAND, CORNERS, GRID }

internal enum class PaperTemplate { BLANK, RULED, GRID, DOT }

internal enum class PageOrientation { PORTRAIT, LANDSCAPE }

internal data class CreateNotebookRequest(
    val title: String,
    val coverColor: CoverColor,
    val coverPattern: CoverPattern,
    val paper: PaperTemplate,
    val orientation: PageOrientation,
    val fingerDrawing: Boolean,
)

@Entity(tableName = "notebooks")
internal data class NotebookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val coverColor: String,
    val coverPattern: String,
    val defaultPaper: String,
    val orientation: String,
    val fingerDrawing: Boolean,
    val favorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val trashedAt: Long?,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["notebookId"]),
        Index(value = ["notebookId", "pageIndex"], unique = true),
    ],
)
internal data class PageEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val pageIndex: Int,
    val paper: String,
    val widthPoints: Int,
    val heightPoints: Int,
)

@Entity(
    tableName = "strokes",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["pageId"])],
)
internal data class StrokeEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val zIndex: Int,
    val brushKind: String,
    val colorArgb: Int,
    val size: Float,
    val epsilon: Float,
    val inputs: ByteArray,
)

internal data class StrokePayload(
    val brushKind: String,
    val colorArgb: Int,
    val size: Float,
    val epsilon: Float,
    val inputs: ByteArray,
)

@Entity(
    tableName = "elements",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["pageId"])],
)
internal data class ElementEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val zIndex: Int,
    val kind: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val text: String?,
    val assetId: String?,
    val shapeKind: String?,
    val expression: String?,
    val resultText: String?,
)

internal data class NotebookContent(
    val notebook: NotebookEntity,
    val pages: List<PageEntity>,
    val strokes: List<StrokeEntity>,
    val elements: List<ElementEntity>,
)
