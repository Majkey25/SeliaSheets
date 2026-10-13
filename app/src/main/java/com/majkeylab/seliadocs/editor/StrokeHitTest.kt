package com.majkeylab.seliadocs.editor

import kotlin.math.abs
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
    for (index in 1 until stroke.points.size) {
        if (distanceToSegmentSquared(point, stroke.points[index - 1], stroke.points[index]) <= radiusSquared) {
            return true
        }
    }
    return false
}

internal fun hitStrokePath(eraser: List<CanvasPoint>, radius: Float, stroke: StrokePath): Boolean {
    require(radius >= 0f)
    if (eraser.isEmpty() || stroke.points.isEmpty()) return false
    if (eraser.size == 1) return hitStroke(eraser.single(), radius, stroke)
    if (stroke.points.size == 1) {
        for (index in 1 until eraser.size) {
            if (distanceToSegmentSquared(stroke.points.single(), eraser[index - 1], eraser[index]) <= radius * radius) {
                return true
            }
        }
        return false
    }
    for (eraserIndex in 1 until eraser.size) {
        for (strokeIndex in 1 until stroke.points.size) {
            if (
                segmentsWithinRadius(
                    eraser[eraserIndex - 1],
                    eraser[eraserIndex],
                    stroke.points[strokeIndex - 1],
                    stroke.points[strokeIndex],
                    radius,
                )
            ) {
                return true
            }
        }
    }
    return false
}

internal fun segmentsWithinRadius(
    firstStart: CanvasPoint,
    firstEnd: CanvasPoint,
    secondStart: CanvasPoint,
    secondEnd: CanvasPoint,
    radius: Float,
): Boolean {
    require(radius >= 0f)
    if (segmentsIntersect(firstStart, firstEnd, secondStart, secondEnd)) return true
    val radiusSquared = radius * radius
    return minOf(
        distanceToSegmentSquared(firstStart, secondStart, secondEnd),
        distanceToSegmentSquared(firstEnd, secondStart, secondEnd),
        distanceToSegmentSquared(secondStart, firstStart, firstEnd),
        distanceToSegmentSquared(secondEnd, firstStart, firstEnd),
    ) <= radiusSquared
}

internal fun CanvasPoint.inside(polygon: List<CanvasPoint>): Boolean {
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

internal fun distanceSquared(first: CanvasPoint, second: CanvasPoint): Float {
    val dx = first.x - second.x
    val dy = first.y - second.y
    return dx * dx + dy * dy
}

internal fun distanceToSegmentSquared(point: CanvasPoint, start: CanvasPoint, end: CanvasPoint): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return distanceSquared(point, start)
    val projection =
        max(0f, min(1f, ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared))
    return distanceSquared(point, CanvasPoint(start.x + projection * dx, start.y + projection * dy))
}

private fun segmentsIntersect(
    firstStart: CanvasPoint,
    firstEnd: CanvasPoint,
    secondStart: CanvasPoint,
    secondEnd: CanvasPoint,
): Boolean {
    val firstSideA = cross(firstStart, firstEnd, secondStart)
    val firstSideB = cross(firstStart, firstEnd, secondEnd)
    val secondSideA = cross(secondStart, secondEnd, firstStart)
    val secondSideB = cross(secondStart, secondEnd, firstEnd)
    if (abs(firstSideA) <= INTERSECTION_EPSILON && onSegment(firstStart, firstEnd, secondStart)) return true
    if (abs(firstSideB) <= INTERSECTION_EPSILON && onSegment(firstStart, firstEnd, secondEnd)) return true
    if (abs(secondSideA) <= INTERSECTION_EPSILON && onSegment(secondStart, secondEnd, firstStart)) return true
    if (abs(secondSideB) <= INTERSECTION_EPSILON && onSegment(secondStart, secondEnd, firstEnd)) return true
    return (firstSideA > 0f) != (firstSideB > 0f) && (secondSideA > 0f) != (secondSideB > 0f)
}

private fun cross(start: CanvasPoint, end: CanvasPoint, point: CanvasPoint): Float =
    (end.x - start.x) * (point.y - start.y) - (end.y - start.y) * (point.x - start.x)

private fun onSegment(start: CanvasPoint, end: CanvasPoint, point: CanvasPoint): Boolean =
    point.x in min(start.x, end.x)..max(start.x, end.x) &&
        point.y in min(start.y, end.y)..max(start.y, end.y)

private const val INTERSECTION_EPSILON = 0.0001f
