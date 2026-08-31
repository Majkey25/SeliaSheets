package com.majkeylab.seliadocs.recognition

import com.majkeylab.seliadocs.editor.evaluateExpression
import com.majkeylab.seliadocs.editor.formatMathResult

internal fun decideInkMath(
    candidates: List<RecognitionCandidate>,
    variables: Map<String, Double> = emptyMap(),
): InkMathDecision {
    val uniqueCandidates = candidates.mapNotNull { candidate ->
        if (candidate.text.any { it == '\r' || it == '\n' }) return@mapNotNull null
        val expression = candidate.text.trim()
            .filterNot { it == ' ' || it == '\t' }
            .replace('×', '*')
            .replace('÷', '/')
        if (!expression.endsWith('=')) return@mapNotNull null
        val result = evaluateExpression(expression, variables).getOrNull() ?: return@mapNotNull null
        InkMathCandidate(expression, formatMathResult(result))
    }.distinctBy { it.expression to it.result }

    return when (uniqueCandidates.size) {
        0 -> InkMathDecision.None
        1 -> InkMathDecision.Unique(uniqueCandidates.single())
        else -> InkMathDecision.Ambiguous(uniqueCandidates)
    }
}

internal fun boundedRecognitionRequest(
    pageId: String,
    pageWidth: Float,
    pageHeight: Float,
    fingerprints: List<RecognitionFingerprint>,
    strokes: List<RecognitionStroke>,
): RecognitionRequest? {
    if (pageId.isBlank() || !pageWidth.isFinite() || pageWidth <= 0f ||
        !pageHeight.isFinite() || pageHeight <= 0f ||
        strokes.size !in 1..32 || fingerprints.size != strokes.size
    ) {
        return null
    }

    val totalPoints = strokes.sumOf { it.points.size.toLong() }
    if (totalPoints !in 1L..4096L || strokes.any { stroke ->
            if (stroke.points.isEmpty()) return@any true
            var previousTimeMillis: Long? = null
            stroke.points.any { point ->
                val valid = point.x.isFinite() && point.y.isFinite() &&
                    point.timeMillis >= 0L &&
                    (previousTimeMillis?.let { point.timeMillis >= it } ?: true)
                previousTimeMillis = point.timeMillis
                !valid
            }
        }
    ) {
        return null
    }

    return RecognitionRequest(pageId, pageWidth, pageHeight, fingerprints, strokes)
}
