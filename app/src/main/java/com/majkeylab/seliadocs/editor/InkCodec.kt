package com.majkeylab.seliadocs.editor

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInputBatch
import com.majkeylab.seliadocs.data.StrokeEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong

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

internal fun StrokeEntity.eraseSegments(
    eraser: List<CanvasPoint>,
    radius: Float,
    idFactory: () -> String,
): List<StrokeEntity> {
    require(radius > 0f)
    if (eraser.isEmpty()) return listOf(this)
    val stroke = toInkStroke()
    val samples = denseSamples(stroke, (radius / 2f).coerceIn(2f, 8f))
    val erased =
        samples.map { sample ->
            val point = CanvasPoint(sample.x, sample.y)
            if (eraser.size == 1) {
                distanceSquared(point, eraser.single()) <= radius * radius
            } else {
                eraser.zipWithNext().any { (start, end) ->
                    distanceToSegmentSquared(point, start, end) <= radius * radius
                }
            }
        }
    if (erased.none { it }) return listOf(this)
    return sampleRuns(samples, erased)
        .filter { it.size >= 2 }
        .map { run ->
            val inputs =
                MutableStrokeInputBatch().apply {
                    run.forEach { sample ->
                        add(
                            sample.toolType,
                            sample.x,
                            sample.y,
                            sample.elapsedTimeMillis,
                            sample.strokeUnitLengthCm,
                            sample.pressure,
                            sample.tiltRadians,
                            sample.orientationRadians,
                        )
                    }
                }
            val encoded = InkCodec.encode(Stroke(stroke.brush, inputs))
            copy(id = idFactory(), inputs = encoded.inputs)
        }
}

private data class InkSample(
    val toolType: InputToolType,
    val x: Float,
    val y: Float,
    val elapsedTimeMillis: Long,
    val strokeUnitLengthCm: Float,
    val pressure: Float,
    val tiltRadians: Float,
    val orientationRadians: Float,
)

private fun denseSamples(stroke: Stroke, spacing: Float): List<InkSample> {
    if (stroke.inputs.size == 0) return emptyList()
    require(stroke.inputs.size <= MAX_DENSE_SAMPLES) { "Stroke is too dense for segment erasing" }
    val lengths =
        (0 until stroke.inputs.size - 1).map { index ->
            hypot(
                stroke.inputs[index + 1].x - stroke.inputs[index].x,
                stroke.inputs[index + 1].y - stroke.inputs[index].y,
            )
        }
    val additionalBudget = (MAX_DENSE_SAMPLES - stroke.inputs.size).coerceAtLeast(1)
    val effectiveSpacing = max(spacing, lengths.sum() / additionalBudget)
    val samples = mutableListOf(stroke.inputs[0].toSample())
    repeat(stroke.inputs.size - 1) { index ->
        val start = stroke.inputs[index]
        val end = stroke.inputs[index + 1]
        val remainingSegments = stroke.inputs.size - 2 - index
        val maxSteps = (MAX_DENSE_SAMPLES - samples.size - remainingSegments).coerceAtLeast(1)
        val steps = ceil(lengths[index] / effectiveSpacing).toInt().coerceIn(1, minOf(4_096, maxSteps))
        repeat(steps) { step ->
            val progress = (step + 1f) / steps
            samples +=
                InkSample(
                    toolType = start.toolType,
                    x = lerp(start.x, end.x, progress),
                    y = lerp(start.y, end.y, progress),
                    elapsedTimeMillis =
                        (start.elapsedTimeMillis +
                                (end.elapsedTimeMillis - start.elapsedTimeMillis) * progress.toDouble())
                            .roundToLong(),
                    strokeUnitLengthCm = lerp(start.strokeUnitLengthCm, end.strokeUnitLengthCm, progress),
                    pressure = lerp(start.pressure, end.pressure, progress),
                    tiltRadians = lerp(start.tiltRadians, end.tiltRadians, progress),
                    orientationRadians = lerp(start.orientationRadians, end.orientationRadians, progress),
                )
        }
    }
    return samples
}

private fun androidx.ink.strokes.StrokeInput.toSample() =
    InkSample(
        toolType,
        x,
        y,
        elapsedTimeMillis,
        strokeUnitLengthCm,
        pressure,
        tiltRadians,
        orientationRadians,
    )

private fun sampleRuns(samples: List<InkSample>, erased: List<Boolean>): List<List<InkSample>> {
    val runs = mutableListOf<MutableList<InkSample>>()
    samples.forEachIndexed { index, sample ->
        if (!erased[index]) {
            if (index == 0 || erased[index - 1]) runs.add(mutableListOf())
            runs.last() += sample
        }
    }
    return runs
}

private fun lerp(start: Float, end: Float, progress: Float): Float = start + (end - start) * progress

private const val MAX_DENSE_SAMPLES = 100_000
