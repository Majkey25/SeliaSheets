package com.majkeylab.seliadocs.editor

import kotlin.math.max
import kotlin.math.min

internal data class CanvasPoint(val x: Float, val y: Float)

internal data class StrokePath(val id: String, val points: List<CanvasPoint>)

internal fun selectStrokes(lasso: List<CanvasPoint>, strokes: List<StrokePath>): Set<String> {
    if (lasso.size < 3) return emptySet()
    return strokes
        .filter { stroke ->
            stroke.points.isNotEmpty() &&
                stroke.points.count { point -> point.inside(lasso) } * 2 >= stroke.points.size
        }
        .mapTo(mutableSetOf(), StrokePath::id)
}

internal fun hitStroke(point: CanvasPoint, radius: Float, stroke: StrokePath): Boolean {
    require(radius >= 0f)
    val radiusSquared = radius * radius
    if (stroke.points.size == 1) return distanceSquared(point, stroke.points.single()) <= radiusSquared
    return stroke.points.zipWithNext().any { (start, end) ->
        distanceToSegmentSquared(point, start, end) <= radiusSquared
    }
}

private fun CanvasPoint.inside(polygon: List<CanvasPoint>): Boolean {
    var inside = false
    var previous = polygon.last()
    for (current in polygon) {
        if ((current.y > y) != (previous.y > y) &&
            x < (previous.x - current.x) * (y - current.y) / (previous.y - current.y) + current.x
        ) {
            inside = !inside
        }
        previous = current
    }
    return inside
}

private fun distanceSquared(first: CanvasPoint, second: CanvasPoint): Float {
    val dx = first.x - second.x
    val dy = first.y - second.y
    return dx * dx + dy * dy
}

private fun distanceToSegmentSquared(point: CanvasPoint, start: CanvasPoint, end: CanvasPoint): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return distanceSquared(point, start)
    val projection =
        max(0f, min(1f, ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared))
    return distanceSquared(point, CanvasPoint(start.x + projection * dx, start.y + projection * dy))
}
