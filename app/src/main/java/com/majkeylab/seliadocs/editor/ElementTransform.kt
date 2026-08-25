package com.majkeylab.seliadocs.editor

import com.majkeylab.seliadocs.data.ElementEntity
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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
    var width = proposed.width.coerceIn(minimumSize, pageWidth)
    var height = proposed.height.coerceIn(minimumSize, pageHeight)
    val rotation = ((proposed.rotation % 360f) + 360f) % 360f
    var (extentX, extentY) = rotatedExtents(width, height, rotation)
    val scale = min(1f, min(pageWidth / (extentX * 2f), pageHeight / (extentY * 2f)))
    if (scale < 1f) {
        width = (width * scale).coerceAtLeast(minimumSize.coerceAtMost(pageWidth))
        height = (height * scale).coerceAtLeast(minimumSize.coerceAtMost(pageHeight))
        val extents = rotatedExtents(width, height, rotation)
        extentX = extents.first
        extentY = extents.second
    }
    val centerX = (proposed.x + proposed.width.coerceAtLeast(minimumSize) / 2f).coerceIn(extentX, pageWidth - extentX)
    val centerY = (proposed.y + proposed.height.coerceAtLeast(minimumSize) / 2f).coerceIn(extentY, pageHeight - extentY)
    return proposed.copy(
        x = centerX - width / 2f,
        y = centerY - height / 2f,
        width = width,
        height = height,
        rotation = rotation,
    )
}

private fun ElementTransform.isFinite(): Boolean =
    x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() && rotation.isFinite()

internal fun selectElementAt(point: CanvasPoint, elements: List<ElementEntity>): String? =
    elements
        .asSequence()
        .filter { element ->
            val centerX = element.x + element.width / 2f
            val centerY = element.y + element.height / 2f
            val radians = Math.toRadians(element.rotation.toDouble())
            val cosine = cos(radians).toFloat()
            val sine = sin(radians).toFloat()
            val dx = point.x - centerX
            val dy = point.y - centerY
            val localX = dx * cosine + dy * sine
            val localY = -dx * sine + dy * cosine
            abs(localX) <= element.width / 2f && abs(localY) <= element.height / 2f
        }
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
            CanvasPoint(
                element.x + element.width / 2f,
                element.y + element.height / 2f,
            ).inside(points)
        }
        .maxByOrNull(ElementEntity::zIndex)
        ?.id
}

internal fun ElementEntity.transform() = ElementTransform(x, y, width, height, rotation)

private fun rotatedExtents(width: Float, height: Float, rotation: Float): Pair<Float, Float> {
    val radians = Math.toRadians(rotation.toDouble())
    val cosine = abs(cos(radians)).toFloat()
    val sine = abs(sin(radians)).toFloat()
    return (width / 2f * cosine + height / 2f * sine) to
        (width / 2f * sine + height / 2f * cosine)
}
