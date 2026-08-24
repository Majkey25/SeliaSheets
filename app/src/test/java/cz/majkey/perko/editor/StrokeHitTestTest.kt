package cz.majkey.perko.editor

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
}
