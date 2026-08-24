package cz.majkey.perko.editor

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkCodecTest {
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
}
