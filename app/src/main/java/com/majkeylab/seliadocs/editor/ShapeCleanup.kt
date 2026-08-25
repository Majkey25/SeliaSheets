package com.majkeylab.seliadocs.editor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal enum class ShapeKind { LINE, ARROW, ELLIPSE, RECTANGLE, TRIANGLE }

internal data class ShapeBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top
}

internal data class TimedCanvasPoint(val point: CanvasPoint, val elapsedTimeMillis: Long)

internal data class ShapeRecognition(val kind: ShapeKind, val transform: ElementTransform)

internal fun recognizeLine(points: List<CanvasPoint>): ShapeKind? {
    if (points.size < 2) return null
    val start = points.first()
    val end = points.last()
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = hypot(dx, dy)
    if (length < 4f) return null
    val maxDeviation =
        points.maxOf { point -> abs(dy * (point.x - start.x) - dx * (point.y - start.y)) / length }
    return ShapeKind.LINE.takeIf { maxDeviation <= length * 0.03f }
}

internal fun shapeBox(paths: List<StrokePath>): ShapeBox? {
    val points = paths.flatMap(StrokePath::points)
    if (points.isEmpty()) return null
    return ShapeBox(
        left = points.minOf(CanvasPoint::x),
        top = points.minOf(CanvasPoint::y),
        right = points.maxOf(CanvasPoint::x),
        bottom = points.maxOf(CanvasPoint::y),
    )
}

internal fun segmentShapeTransform(
    start: CanvasPoint,
    end: CanvasPoint,
    thickness: Float = 24f,
): ElementTransform? {
    val length = hypot(end.x - start.x, end.y - start.y)
    if (length < 4f || thickness <= 0f) return null
    val centerX = (start.x + end.x) / 2f
    val centerY = (start.y + end.y) / 2f
    return ElementTransform(
        x = centerX - length / 2f,
        y = centerY - thickness / 2f,
        width = length,
        height = thickness,
        rotation = Math.toDegrees(atan2(end.y - start.y, end.x - start.x).toDouble()).toFloat(),
    )
}

internal fun recognizeHeldShape(
    samples: List<TimedCanvasPoint>,
    pageWidth: Float,
    pageHeight: Float,
): ShapeRecognition? {
    if (samples.size < 4 || pageWidth <= 0f || pageHeight <= 0f) return null
    val duration = samples.last().elapsedTimeMillis - samples.first().elapsedTimeMillis
    if (duration < HOLD_DURATION_MS) return null
    val end = samples.last().point
    val farIndex = samples.indexOfLast { distance(it.point, end) > HOLD_RADIUS }
    if (farIndex < 1 || samples.last().elapsedTimeMillis - samples[farIndex].elapsedTimeMillis < HOLD_DWELL_MS) {
        return null
    }
    val drawingEnd = (farIndex + 1).coerceAtMost(samples.lastIndex)
    val points = samples.subList(0, drawingEnd + 1).map(TimedCanvasPoint::point)
    recognizeArrow(points)?.let { (start, tip) ->
        val transform = segmentShapeTransform(start, tip) ?: return@let
        return ShapeRecognition(
            ShapeKind.ARROW,
            clampElementTransform(transform, pageWidth, pageHeight) ?: return null,
        )
    }
    if (recognizeLine(points) == ShapeKind.LINE) {
        val transform = segmentShapeTransform(points.first(), points.last()) ?: return null
        return ShapeRecognition(
            ShapeKind.LINE,
            clampElementTransform(transform, pageWidth, pageHeight) ?: return null,
        )
    }
    val box = points.boundingBox() ?: return null
    if (box.width < MIN_SHAPE_SIZE || box.height < MIN_SHAPE_SIZE) return null
    val diagonal = hypot(box.width, box.height)
    if (distance(points.first(), points.last()) > max(CLOSE_DISTANCE, diagonal * 0.16f)) return null
    val kind = recognizeClosedShape(points, box) ?: return null
    val transform =
        clampElementTransform(
            ElementTransform(box.left, box.top, box.width, box.height, 0f),
            pageWidth,
            pageHeight,
        ) ?: return null
    return ShapeRecognition(kind, transform)
}

private fun recognizeArrow(points: List<CanvasPoint>): Pair<CanvasPoint, CanvasPoint>? {
    if (points.size < 6) return null
    val start = points.first()
    val tipIndex = points.indices.maxByOrNull { distance(points[it], start) } ?: return null
    if (tipIndex < 2 || tipIndex >= points.lastIndex - 1) return null
    val tip = points[tipIndex]
    val shaft = points.subList(0, tipIndex + 1)
    if (recognizeLine(shaft) != ShapeKind.LINE) return null
    val shaftLength = distance(start, tip)
    if (shaftLength < 30f) return null
    val head = points.subList(tipIndex + 1, points.size)
    val headDistances = head.map { distance(it, tip) }
    if (headDistances.maxOrNull().orZero() !in (shaftLength * 0.08f)..(shaftLength * 0.4f)) return null
    val dx = tip.x - start.x
    val dy = tip.y - start.y
    val sides =
        head.filterIndexed { index, _ -> headDistances[index] >= shaftLength * 0.06f }
            .map { point -> dx * (point.y - tip.y) - dy * (point.x - tip.x) }
    if (sides.none { it < 0f } || sides.none { it > 0f }) return null
    return start to tip
}

private fun recognizeClosedShape(points: List<CanvasPoint>, box: ShapeBox): ShapeKind? {
    val scale = min(box.width, box.height).coerceAtLeast(1f)
    val rectangleScore =
        points.map { point ->
            minOf(
                abs(point.x - box.left),
                abs(point.x - box.right),
                abs(point.y - box.top),
                abs(point.y - box.bottom),
            ) / scale
        }.average().toFloat()
    val triangle =
        listOf(
            CanvasPoint((box.left + box.right) / 2f, box.top),
            CanvasPoint(box.right, box.bottom),
            CanvasPoint(box.left, box.bottom),
        )
    val triangleScore =
        points.map { point ->
            minOf(
                distanceToSegmentSquared(point, triangle[0], triangle[1]),
                distanceToSegmentSquared(point, triangle[1], triangle[2]),
                distanceToSegmentSquared(point, triangle[2], triangle[0]),
            ).pow(0.5f) / scale
        }.average().toFloat()
    val centerX = (box.left + box.right) / 2f
    val centerY = (box.top + box.bottom) / 2f
    val radiusX = box.width / 2f
    val radiusY = box.height / 2f
    val ellipseScore =
        points.map { point ->
            abs(
                ((point.x - centerX) / radiusX).pow(2) +
                    ((point.y - centerY) / radiusY).pow(2) -
                    1f,
            )
        }.average().toFloat()
    return listOf(
            ShapeKind.RECTANGLE to rectangleScore / RECTANGLE_THRESHOLD,
            ShapeKind.TRIANGLE to triangleScore / TRIANGLE_THRESHOLD,
            ShapeKind.ELLIPSE to ellipseScore / ELLIPSE_THRESHOLD,
        )
        .minByOrNull { it.second }
        ?.takeIf { it.second <= 1f }
        ?.first
}

private fun List<CanvasPoint>.boundingBox(): ShapeBox? =
    if (isEmpty()) {
        null
    } else {
        ShapeBox(
            minOf(CanvasPoint::x),
            minOf(CanvasPoint::y),
            maxOf(CanvasPoint::x),
            maxOf(CanvasPoint::y),
        )
    }

private fun distance(first: CanvasPoint, second: CanvasPoint): Float = hypot(first.x - second.x, first.y - second.y)

private fun Float?.orZero(): Float = this ?: 0f

private const val HOLD_DURATION_MS = 450L
private const val HOLD_DWELL_MS = 280L
private const val HOLD_RADIUS = 5f
private const val MIN_SHAPE_SIZE = 24f
private const val CLOSE_DISTANCE = 18f
private const val RECTANGLE_THRESHOLD = 0.09f
private const val TRIANGLE_THRESHOLD = 0.1f
private const val ELLIPSE_THRESHOLD = 0.24f
