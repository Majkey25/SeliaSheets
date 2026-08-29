package com.majkeylab.seliadocs.recognition

internal data class RecognitionPoint(val x: Float, val y: Float, val timeMillis: Long)

internal data class RecognitionStroke(val points: List<RecognitionPoint>)

internal data class RecognitionFingerprint(val strokeId: String, val payloadHash: Int)

internal data class RecognitionRequest(
    val pageId: String,
    val pageWidth: Float,
    val pageHeight: Float,
    val fingerprints: List<RecognitionFingerprint>,
    val strokes: List<RecognitionStroke>,
)

internal data class RecognitionCandidate(val text: String)

internal data class InkMathCandidate(val expression: String, val result: String)

internal sealed interface InkMathDecision {
    data class Unique(val candidate: InkMathCandidate) : InkMathDecision

    data class Ambiguous(val candidates: List<InkMathCandidate>) : InkMathDecision

    data object None : InkMathDecision
}
