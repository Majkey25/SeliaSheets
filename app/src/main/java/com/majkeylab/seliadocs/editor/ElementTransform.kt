package com.majkeylab.seliadocs.editor

import com.majkeylab.seliadocs.data.ElementEntity

internal data class ElementTransform(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
)

internal fun validElementTransform(value: ElementTransform): ElementTransform? =
    value.takeIf {
        it.isFinite() &&
            it.width > 0f &&
            it.height > 0f
    }

internal fun clampElementTransform(
    proposed: ElementTransform,
    pageWidth: Float,
    pageHeight: Float,
    minimumSize: Float = 24f,
): ElementTransform? {
    if (!proposed.isFinite()) return null
    val width = proposed.width.coerceIn(minimumSize, pageWidth)
    val height = proposed.height.coerceIn(minimumSize, pageHeight)
    return proposed.copy(
        x = proposed.x.coerceIn(0f, pageWidth - width),
        y = proposed.y.coerceIn(0f, pageHeight - height),
        width = width,
        height = height,
        rotation = ((proposed.rotation % 360f) + 360f) % 360f,
    )
}

private fun ElementTransform.isFinite(): Boolean =
    x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() && rotation.isFinite()

internal fun selectElementAt(point: CanvasPoint, elements: List<ElementEntity>): String? =
    elements
        .asSequence()
        .filter { point.x in it.x..(it.x + it.width) && point.y in it.y..(it.y + it.height) }
        .maxByOrNull(ElementEntity::zIndex)
        ?.id

internal fun selectElementWithLasso(
    points: List<CanvasPoint>,
    elements: List<ElementEntity>,
): String? {
    if (points.isEmpty()) return null
    val left = points.minOf(CanvasPoint::x)
    val top = points.minOf(CanvasPoint::y)
    val right = points.maxOf(CanvasPoint::x)
    val bottom = points.maxOf(CanvasPoint::y)
    if (right - left <= 12f && bottom - top <= 12f) {
        return selectElementAt(points.last(), elements)
    }
    return elements
        .asSequence()
        .filter { element ->
            element.x + element.width / 2f in left..right &&
                element.y + element.height / 2f in top..bottom
        }
        .maxByOrNull(ElementEntity::zIndex)
        ?.id
}

internal fun ElementEntity.transform() = ElementTransform(x, y, width, height, rotation)
