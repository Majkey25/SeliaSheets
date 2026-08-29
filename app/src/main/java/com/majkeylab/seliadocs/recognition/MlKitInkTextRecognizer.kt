package com.majkeylab.seliadocs.recognition

import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal class MlKitInkTextRecognizer(
    language: RecognitionLanguage,
) : InkTextRecognizer {
    private val recognizer: DigitalInkRecognizer = DigitalInkRecognition.getClient(
        DigitalInkRecognizerOptions.builder(digitalInkModel(language)).build(),
    )
    private val closed = AtomicBoolean()

    override suspend fun recognize(request: RecognitionRequest): List<RecognitionCandidate> {
        check(!closed.get()) { "Recognizer is closed." }
        val context = request.toMlKitRecognitionContext()
        return recognizer.recognize(request.toMlKitInk(), context).awaitTask().candidates
            .map { RecognitionCandidate(it.text) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) recognizer.close()
    }
}

internal fun RecognitionRequest.toMlKitRecognitionContext(): RecognitionContext =
    RecognitionContext.builder()
        .setPreContext("")
        .setWritingArea(WritingArea(pageWidth, estimatedLineHeight()))
        .build()

internal fun RecognitionRequest.toMlKitInk(): Ink {
    require(strokes.isNotEmpty() && strokes.all { it.points.isNotEmpty() })
    val ink = Ink.builder()
    var previousTime = -1L
    strokes.forEachIndexed { index, stroke ->
        val strokeStart = stroke.points.first().timeMillis
        val offset = if (previousTime < 0L) 0L else previousTime + 1L
        val remainingStrokes = (strokes.lastIndex - index).toLong()
        val availableDuration = Long.MAX_VALUE - remainingStrokes - offset
        val duration = stroke.points.last().timeMillis - strokeStart
        val outputStroke = Ink.Stroke.builder()
        stroke.points.forEach { point ->
            val time = offset + (point.timeMillis - strokeStart).fitIn(duration, availableDuration)
            outputStroke.addPoint(Ink.Point.create(point.x, point.y, time))
            previousTime = time
        }
        ink.addStroke(outputStroke.build())
    }
    return ink.build()
}

private fun Long.fitIn(duration: Long, availableDuration: Long): Long =
    if (duration <= availableDuration) this else {
        (toDouble() * availableDuration / duration).toLong().coerceAtMost(availableDuration)
    }

internal fun digitalInkModel(language: RecognitionLanguage): DigitalInkRecognitionModel {
    val identifier = requireNotNull(DigitalInkRecognitionModelIdentifier.fromLanguageTag(language.tag)) {
        "No digital ink model is available for ${language.tag}."
    }
    return DigitalInkRecognitionModel.builder(identifier).build()
}

private fun RecognitionRequest.estimatedLineHeight(): Float {
    val points = strokes.flatMap { it.points }
    return (points.maxOf { it.y } - points.minOf { it.y }).coerceAtLeast(1f)
}

internal suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    val delivered = AtomicBoolean()
    continuation.invokeOnCancellation { delivered.set(true) }
    addOnSuccessListener { value ->
        if (continuation.isActive && delivered.compareAndSet(false, true)) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive && delivered.compareAndSet(false, true)) {
            continuation.resumeWithException(error)
        }
    }
    addOnCanceledListener {
        if (delivered.compareAndSet(false, true)) continuation.cancel()
    }
}
