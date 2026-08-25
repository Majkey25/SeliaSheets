package com.majkeylab.seliadocs.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

    @Test
    fun verticalSegmentRotatesAroundItsMidpoint() {
        val transform = requireNotNull(segmentShapeTransform(CanvasPoint(0f, 0f), CanvasPoint(0f, 100f)))

        assertEquals(-50f, transform.x, 0.001f)
        assertEquals(38f, transform.y, 0.001f)
        assertEquals(100f, transform.width, 0.001f)
        assertEquals(90f, transform.rotation, 0.001f)
    }

    @Test
    fun heldLineIsRecognizedButQuickLineIsNot() {
        val held =
            listOf(
                timed(0f, 0f, 0),
                timed(50f, 0f, 100),
                timed(100f, 0f, 200),
                timed(100f, 0f, 520),
                timed(100f, 0f, 620),
            )

        assertEquals(ShapeKind.LINE, recognizeHeldShape(held, 595f, 842f)?.kind)
        assertNull(recognizeHeldShape(held.take(3), 595f, 842f))
    }

    @Test
    fun heldArrowAndEllipseAreRecognized() {
        val arrow =
            listOf(
                timed(0f, 0f, 0),
                timed(50f, 0f, 80),
                timed(100f, 0f, 160),
                timed(82f, -16f, 220),
                timed(100f, 0f, 260),
                timed(82f, 16f, 320),
                timed(100f, 0f, 360),
                timed(100f, 0f, 700),
            )
        val ellipsePoints =
            (0..16).map { index ->
                val angle = 2.0 * PI * index / 16.0
                timed(
                    150f + 60f * cos(angle).toFloat(),
                    180f + 40f * sin(angle).toFloat(),
                    index * 20L,
                )
            }
        val ellipse = ellipsePoints + timed(210f, 180f, 650) + timed(210f, 180f, 720)

        assertEquals(ShapeKind.ARROW, recognizeHeldShape(arrow, 595f, 842f)?.kind)
        assertEquals(ShapeKind.ELLIPSE, recognizeHeldShape(ellipse, 595f, 842f)?.kind)
    }

    @Test
    fun heldRectangleAndTriangleAreRecognized() {
        val rectanglePath =
            listOf(
                CanvasPoint(80f, 80f),
                CanvasPoint(140f, 80f),
                CanvasPoint(200f, 80f),
                CanvasPoint(200f, 130f),
                CanvasPoint(200f, 180f),
                CanvasPoint(140f, 180f),
                CanvasPoint(80f, 180f),
                CanvasPoint(80f, 130f),
                CanvasPoint(80f, 80f),
            )
        val trianglePath =
            listOf(
                CanvasPoint(150f, 60f),
                CanvasPoint(180f, 110f),
                CanvasPoint(210f, 160f),
                CanvasPoint(150f, 160f),
                CanvasPoint(90f, 160f),
                CanvasPoint(120f, 110f),
                CanvasPoint(150f, 60f),
            )
        val rectangle = heldClosed(rectanglePath)
        val triangle = heldClosed(trianglePath)

        assertEquals(ShapeKind.RECTANGLE, recognizeHeldShape(rectangle, 595f, 842f)?.kind)
        assertEquals(ShapeKind.TRIANGLE, recognizeHeldShape(triangle, 595f, 842f)?.kind)
    }

    private fun heldClosed(points: List<CanvasPoint>): List<TimedCanvasPoint> {
        val timed = points.mapIndexed { index, point -> TimedCanvasPoint(point, index * 40L) }
        return timed + TimedCanvasPoint(points.last(), 700) + TimedCanvasPoint(points.last(), 760)
    }

    private fun timed(x: Float, y: Float, elapsed: Long) =
        TimedCanvasPoint(CanvasPoint(x, y), elapsed)
}
