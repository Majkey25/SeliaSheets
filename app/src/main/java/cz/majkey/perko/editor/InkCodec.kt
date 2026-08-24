package cz.majkey.perko.editor

import androidx.ink.brush.Brush
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInputBatch
import cz.majkey.perko.data.StrokeEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal enum class BrushKind { PRESSURE_PEN, MARKER, HIGHLIGHTER }

internal data class EncodedStroke(
    val brushKind: BrushKind,
    val colorArgb: Int,
    val size: Float,
    val epsilon: Float,
    val inputs: ByteArray,
)

internal object InkCodec {
    fun encode(stroke: Stroke): EncodedStroke {
        val bytes =
            ByteArrayOutputStream().use { output ->
                stroke.inputs.encode(output)
                output.toByteArray()
            }
        return EncodedStroke(
            brushKind = brushKind(stroke.brush),
            colorArgb = stroke.brush.colorIntArgb,
            size = stroke.brush.size,
            epsilon = stroke.brush.epsilon,
            inputs = bytes,
        )
    }

    fun decode(value: EncodedStroke): Stroke {
        val inputs =
            ByteArrayInputStream(value.inputs).use { input ->
                StrokeInputBatch.decode(input)
            }
        return Stroke(createBrush(value.brushKind, value.colorArgb, value.size, value.epsilon), inputs)
    }

    fun createBrush(
        kind: BrushKind,
        colorArgb: Int,
        size: Float,
        epsilon: Float = 0.1f,
    ): Brush =
        Brush.createWithColorIntArgb(brushFamily(kind), colorArgb, size, epsilon)

    private fun brushKind(brush: Brush): BrushKind =
        BrushKind.entries.firstOrNull { kind -> brush.family == brushFamily(kind) }
            ?: error("Unsupported brush family")

    private fun brushFamily(kind: BrushKind) =
        when (kind) {
            BrushKind.PRESSURE_PEN ->
                StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
            BrushKind.MARKER -> StockBrushes.marker(StockBrushes.MarkerVersion.V1)
            BrushKind.HIGHLIGHTER ->
                StockBrushes.highlighter(
                    SelfOverlap.DISCARD,
                    StockBrushes.HighlighterVersion.V1,
                )
        }
}

internal fun StrokeEntity.toInkStroke(): Stroke =
    InkCodec.decode(
        EncodedStroke(
            brushKind = BrushKind.valueOf(brushKind),
            colorArgb = colorArgb,
            size = size,
            epsilon = epsilon,
            inputs = inputs,
        ),
    )

internal fun StrokeEntity.toStrokePath(): StrokePath {
    val batch = toInkStroke().inputs
    return StrokePath(id, (0 until batch.size).map { index -> CanvasPoint(batch[index].x, batch[index].y) })
}

internal fun StrokeEntity.translated(dx: Float, dy: Float): StrokeEntity {
    val stroke = toInkStroke()
    val movedInputs =
        MutableStrokeInputBatch().apply {
            repeat(stroke.inputs.size) { index ->
                val input = stroke.inputs[index]
                add(
                    input.toolType,
                    input.x + dx,
                    input.y + dy,
                    input.elapsedTimeMillis,
                    input.strokeUnitLengthCm,
                    input.pressure,
                    input.tiltRadians,
                    input.orientationRadians,
                )
            }
        }
    val encoded = InkCodec.encode(Stroke(stroke.brush, movedInputs))
    return copy(inputs = encoded.inputs)
}
