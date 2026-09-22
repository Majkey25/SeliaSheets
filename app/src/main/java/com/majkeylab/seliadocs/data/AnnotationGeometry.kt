package com.majkeylab.seliadocs.data

internal const val MAX_ANNOTATION_RECTS = 2_000
internal const val MAX_ANNOTATION_DATA_LENGTH = 100_000

internal data class AnnotationRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && left < right && top < bottom)
    }
}

internal fun encodeAnnotationRects(rects: List<AnnotationRect>): String {
    require(rects.size in 1..MAX_ANNOTATION_RECTS)
    return rects.joinToString("\n") { "${it.left},${it.top},${it.right},${it.bottom}" }.also {
        require(it.length <= MAX_ANNOTATION_DATA_LENGTH)
    }
}

internal fun decodeAnnotationRects(encoded: String?): List<AnnotationRect> {
    if (encoded == null) return emptyList()
    require(encoded.length in 1..MAX_ANNOTATION_DATA_LENGTH)
    val lines = encoded.split('\n')
    require(lines.size in 1..MAX_ANNOTATION_RECTS)
    return lines.map { line ->
        val values = line.split(',')
        require(values.size == 4)
        AnnotationRect(values[0].toFloat(), values[1].toFloat(), values[2].toFloat(), values[3].toFloat())
    }
}

internal fun encodeSourceRect(rect: AnnotationRect): String = encodeAnnotationRects(listOf(rect))

internal fun decodeSourceRect(encoded: String): AnnotationRect = decodeAnnotationRects(encoded).single()

internal fun ElementKind.isPdfMarkup(): Boolean =
    this == ElementKind.HIGHLIGHT || this == ElementKind.UNDERLINE || this == ElementKind.STRIKEOUT

internal fun validateAnnotationFields(
    kind: ElementKind,
    colorArgb: Int?,
    annotationRects: String?,
    sourcePageId: String?,
    sourceRect: String?,
    strokeWidth: Float? = null,
) {
    if (kind.isPdfMarkup()) {
        require(colorArgb != null && colorArgb ushr 24 != 0)
        require(decodeAnnotationRects(annotationRects).isNotEmpty())
    } else if (kind == ElementKind.SHAPE) {
        require(colorArgb == null || colorArgb ushr 24 != 0)
        require(annotationRects == null)
    } else {
        require(colorArgb == null && annotationRects == null)
    }
    if (strokeWidth != null) require(kind == ElementKind.SHAPE && strokeWidth.isFinite() && strokeWidth > 0f && strokeWidth <= MAX_STROKE_WIDTH)
    require((sourcePageId == null) == (sourceRect == null))
    if (sourcePageId != null) {
        // Legacy page IDs are resolved only as Room keys, never opened as URLs or filesystem paths.
        require(sourcePageId.isNotBlank() && sourcePageId.length <= 1_024)
        decodeSourceRect(requireNotNull(sourceRect))
    }
}
