package com.majkeylab.seliadocs.editor

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.data.StrokeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkCodecTest {
    @Test
    fun pencilRoundTripKeepsDynamicFamilyAndInputs() {
        val inputs =
            MutableStrokeInputBatch()
                .add(InputToolType.STYLUS, 10f, 20f, 0L, 0.01f, 0.2f, 0.1f, 0f)
                .add(InputToolType.STYLUS, 30f, 40f, 16L, 0.01f, 0.9f, 1.1f, 1.4f)
                .toImmutable()
        val original =
            Stroke(
                InkCodec.createBrush(BrushKind.PENCIL, 0xFF202124.toInt(), 4f),
                inputs,
            )

        val restored = InkCodec.decode(InkCodec.encode(original))

        assertEquals(BrushKind.PENCIL, InkCodec.encode(restored).brushKind)
        assertEquals(SeliaInkBrushes.pencil, restored.brush.family)
        assertEquals(0.2f, restored.inputs[0].pressure, 0.01f)
        assertEquals(0.9f, restored.inputs[1].pressure, 0.01f)
        assertEquals(1.1f, restored.inputs[1].tiltRadians, 0.01f)
        assertEquals(1.4f, restored.inputs[1].orientationRadians, 0.01f)
    }

    @Test
    fun encodedStrokeRoundTripsBrushAndInputs() {
        val inputs =
            MutableStrokeInputBatch()
                .add(InputToolType.STYLUS, 10f, 20f, 0L, 0.01f, 0.4f, 0.2f, 0.3f)
                .add(InputToolType.STYLUS, 30f, 45f, 16L, 0.01f, 0.8f, 0.4f, 0.7f)
                .toImmutable()
        val brush =
            Brush.createWithColorIntArgb(
                StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1),
                0xFF202124.toInt(),
                4f,
                0.1f,
            )
        val encoded = InkCodec.encode(Stroke(brush, inputs))

        val decoded = InkCodec.decode(encoded)

        assertEquals(BrushKind.PRESSURE_PEN, encoded.brushKind)
        assertEquals(0xFF202124.toInt(), decoded.brush.colorIntArgb)
        assertEquals(2, decoded.inputs.size)
        assertEquals(0.8f, decoded.inputs[1].pressure, 0.001f)
        assertEquals(0.4f, decoded.inputs[1].tiltRadians, 0.001f)
        assertEquals(0.7f, decoded.inputs[1].orientationRadians, 0.001f)
    }

    @Test
    fun translatedStrokeKeepsPressureAndTilt() {
        val inputs =
            MutableStrokeInputBatch()
                .add(InputToolType.STYLUS, 10f, 20f, 0L, 0.01f, 0.8f, 0.4f, 0.7f)
                .toImmutable()
        val encoded =
            InkCodec.encode(
                Stroke(
                    InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 4f),
                    inputs,
                ),
            )
        val entity =
            StrokeEntity(
                id = "stroke",
                pageId = "page",
                zIndex = 0,
                brushKind = encoded.brushKind.name,
                colorArgb = encoded.colorArgb,
                size = encoded.size,
                epsilon = encoded.epsilon,
                inputs = encoded.inputs,
            )

        val moved = entity.translated(5f, -4f).toInkStroke()

        assertEquals(15f, moved.inputs[0].x, 0.001f)
        assertEquals(16f, moved.inputs[0].y, 0.001f)
        assertEquals(0.8f, moved.inputs[0].pressure, 0.001f)
        assertEquals(0.4f, moved.inputs[0].tiltRadians, 0.001f)
    }

    @Test
    fun segmentEraserSplitsStrokeAndPreservesInputMetadata() {
        val inputs =
            MutableStrokeInputBatch()
                .add(InputToolType.STYLUS, 0f, 0f, 0L, 0.01f, 0.4f, 0.2f, 0.3f)
                .add(InputToolType.STYLUS, 25f, 0f, 10L, 0.01f, 0.5f, 0.25f, 0.35f)
                .add(InputToolType.STYLUS, 50f, 0f, 20L, 0.01f, 0.6f, 0.3f, 0.4f)
                .add(InputToolType.STYLUS, 75f, 0f, 30L, 0.01f, 0.7f, 0.35f, 0.45f)
                .add(InputToolType.STYLUS, 100f, 0f, 40L, 0.01f, 0.8f, 0.4f, 0.5f)
                .toImmutable()
        val encoded =
            InkCodec.encode(
                Stroke(
                    InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 4f),
                    inputs,
                ),
            )
        val entity =
            StrokeEntity(
                "stroke",
                "page",
                0,
                encoded.brushKind.name,
                encoded.colorArgb,
                encoded.size,
                encoded.epsilon,
                encoded.inputs,
            )
        var nextId = 0

        val fragments =
            entity.eraseSegments(
                listOf(CanvasPoint(50f, -20f), CanvasPoint(50f, 20f)),
                radius = 8f,
                idFactory = { "fragment-${nextId++}" },
            )

        assertEquals(2, fragments.size)
        val left = fragments.first().toInkStroke().inputs
        val right = fragments.last().toInkStroke().inputs
        assertTrue(left[left.size - 1].x < 50f)
        assertTrue(right[0].x > 50f)
        assertEquals(0.4f, left[0].pressure, 0.001f)
        assertEquals(0.8f, right[right.size - 1].pressure, 0.001f)
        assertEquals(0.4f, right[right.size - 1].tiltRadians, 0.001f)
    }
}
