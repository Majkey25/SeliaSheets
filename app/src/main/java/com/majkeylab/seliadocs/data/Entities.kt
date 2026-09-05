package com.majkeylab.seliadocs.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

internal enum class CoverColor { PERIWINKLE, GRAPHITE, SAGE, SALMON, SAND }

internal enum class CoverPattern { SOLID, BAND, CORNERS, GRID }

internal enum class PaperTemplate { BLANK, RULED, GRID, DOT }

internal enum class PageOrientation { PORTRAIT, LANDSCAPE }

internal enum class PageMode { PAPER, FLOW, PDF }

internal enum class BlockKind { PARAGRAPH, HEADING, CHECKLIST, BULLET, NUMBERED, QUOTE, CODE, DIVIDER }

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
        Index(value = ["chapterId"]),
        Index(value = ["pdfSourceId"]),
    ],
)
internal data class PageEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val pageIndex: Int,
    val paper: String,
    val widthPoints: Int,
    val heightPoints: Int,
    val chapterId: String? = null,
    val title: String? = null,
    val pageMode: String = PageMode.PAPER.name,
    val bookmarked: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val pdfSourceId: String? = null,
    val pdfPageIndex: Int? = null,
)

@Entity(
    tableName = "chapters",
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
        Index(value = ["notebookId", "orderIndex"], unique = true),
    ],
)
internal data class ChapterEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val title: String,
    val colorArgb: Int,
    val orderIndex: Int,
)

@Entity(
    tableName = "pdf_sources",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["notebookId"]), Index(value = ["assetId"], unique = true)],
)
internal data class PdfSourceEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val assetId: String,
    val displayName: String,
    val pageCount: Int,
    val byteSize: Long,
    val sha256: String,
    val createdAt: Long,
)

internal data class PdfPageSpec(val widthPoints: Int, val heightPoints: Int)

internal data class PdfImportResult(val sourceId: String, val pageIds: List<String>)

@Entity(
    tableName = "blocks",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["pageId"]),
        Index(value = ["pageId", "orderIndex"], unique = true),
    ],
)
internal data class BlockEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val orderIndex: Int,
    val kind: String,
    val text: String?,
    val checked: Boolean,
    val indent: Int,
    val alignment: String,
    val payloadId: String?,
)

@Entity(
    tableName = "text_marks",
    foreignKeys = [
        ForeignKey(
            entity = BlockEntity::class,
            parentColumns = ["id"],
            childColumns = ["blockId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["blockId"])],
)
internal data class TextMarkEntity(
    @PrimaryKey val id: String,
    val blockId: String,
    val start: Int,
    val end: Int,
    val kind: String,
    val value: String?,
)

internal data class PageTextMatch(
    val pageId: String,
    val pageIndex: Int,
    val text: String,
    val elementId: String? = null,
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

internal enum class ElementKind { TEXT, IMAGE, SHAPE, MATH }

internal const val TEXT_ELEMENT_MAX_LENGTH = 10_000

internal data class ElementDraft(
    val kind: ElementKind,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val text: String? = null,
    val assetId: String? = null,
    val shapeKind: String? = null,
    val expression: String? = null,
    val resultText: String? = null,
    val ocrRegions: String? = null,
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
    val ocrRegions: String? = null,
)

internal data class NotebookContent(
    val notebook: NotebookEntity,
    val pages: List<PageEntity>,
    val strokes: List<StrokeEntity>,
    val elements: List<ElementEntity>,
    val blocks: List<BlockEntity> = emptyList(),
    val chapters: List<ChapterEntity> = emptyList(),
    val pdfSources: List<PdfSourceEntity> = emptyList(),
)
