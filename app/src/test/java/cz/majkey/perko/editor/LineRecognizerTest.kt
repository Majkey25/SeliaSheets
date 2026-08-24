package cz.majkey.perko.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineRecognizerTest {
    @Test
    fun nearlyStraightStrokeBecomesLine() {
        val points =
            listOf(
                CanvasPoint(0f, 0f),
                CanvasPoint(25f, 1f),
                CanvasPoint(50f, -1f),
                CanvasPoint(100f, 0f),
            )

        assertEquals(ShapeKind.LINE, recognizeLine(points))
    }

    @Test
    fun curvedStrokeDoesNotBecomeLine() {
        val points =
            listOf(
                CanvasPoint(0f, 0f),
                CanvasPoint(25f, 18f),
                CanvasPoint(50f, 25f),
                CanvasPoint(100f, 0f),
            )

        assertNull(recognizeLine(points))
    }
}
