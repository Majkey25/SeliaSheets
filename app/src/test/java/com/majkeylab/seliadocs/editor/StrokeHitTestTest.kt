package com.majkeylab.seliadocs.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeHitTestTest {
    private val square =
        listOf(
            CanvasPoint(0f, 0f),
            CanvasPoint(10f, 0f),
            CanvasPoint(10f, 10f),
            CanvasPoint(0f, 10f),
        )

    @Test
    fun lassoSelectsStrokeWithMajorityInside() {
        val inside = StrokePath("inside", listOf(CanvasPoint(2f, 2f), CanvasPoint(8f, 8f)))
        val outside = StrokePath("outside", listOf(CanvasPoint(12f, 2f), CanvasPoint(14f, 8f)))

        assertEquals(setOf("inside"), selectStrokes(square, listOf(inside, outside)))
    }

    @Test
    fun eraserUsesDistanceToStrokeSegments() {
        val stroke = StrokePath("line", listOf(CanvasPoint(0f, 0f), CanvasPoint(10f, 0f)))

        assertTrue(hitStroke(CanvasPoint(5f, 1f), 2f, stroke))
        assertFalse(hitStroke(CanvasPoint(5f, 3f), 2f, stroke))
    }

    @Test
    fun fastEraserCrossingHitsSparseStrokeSegment() {
        val stroke = StrokePath("line", listOf(CanvasPoint(0f, 50f), CanvasPoint(100f, 50f)))
        val eraser = listOf(CanvasPoint(50f, 0f), CanvasPoint(50f, 100f))

        assertTrue(hitStrokePath(eraser, 1f, stroke))
    }

    @Test
    fun eraserChecksLastSegmentOfMultiPointPaths() {
        val stroke = StrokePath("corner", listOf(CanvasPoint(0f, 0f), CanvasPoint(10f, 0f), CanvasPoint(10f, 10f)))
        val eraser = listOf(CanvasPoint(20f, 20f), CanvasPoint(20f, 5f), CanvasPoint(5f, 5f))

        assertTrue(hitStroke(CanvasPoint(10f, 5f), 0f, stroke))
        assertTrue(hitStrokePath(eraser, 0f, StrokePath("point", listOf(CanvasPoint(10f, 5f)))))
        assertTrue(hitStrokePath(eraser, 0f, stroke))
        assertFalse(hitStrokePath(eraser, 0f, StrokePath("miss", listOf(CanvasPoint(10f, 6f)))))
    }
}
