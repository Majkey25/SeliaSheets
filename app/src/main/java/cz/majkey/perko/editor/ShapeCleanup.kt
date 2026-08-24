package cz.majkey.perko.editor

import kotlin.math.abs
import kotlin.math.hypot

internal enum class ShapeKind { LINE, ARROW, ELLIPSE, RECTANGLE, TRIANGLE }

internal data class ShapeBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top
}

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
