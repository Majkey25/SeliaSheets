package com.majkeylab.seliadocs.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkMathCandidateGateTest {
    @Test
    fun uniqueCandidateIsEvaluatedAndNormalized() {
        assertEquals(
            InkMathDecision.Unique(InkMathCandidate("2*3=", "6")),
            decideInkMath(listOf(RecognitionCandidate(" 2 × 3 = "))),
        )
    }

    @Test
    fun equivalentCandidatesDeduplicateInFirstCandidateOrder() {
        assertEquals(
            InkMathDecision.Unique(InkMathCandidate("2*3=", "6")),
            decideInkMath(
                listOf(
                    RecognitionCandidate("2 × 3 ="),
                    RecognitionCandidate("2 * 3="),
                ),
            ),
        )
    }

    @Test
    fun distinctValidCandidatesAreAmbiguous() {
        assertEquals(
            InkMathDecision.Ambiguous(
                listOf(
                    InkMathCandidate("2+3=", "5"),
                    InkMathCandidate("2+4=", "6"),
                ),
            ),
            decideInkMath(
                listOf(
                    RecognitionCandidate("2 + 3 ="),
                    RecognitionCandidate("2 + 4 ="),
                ),
            ),
        )
    }

    @Test
    fun invalidCandidatesAreIgnored() {
        assertEquals(
            InkMathDecision.None,
            decideInkMath(
                listOf(
                    RecognitionCandidate("not math"),
                    RecognitionCandidate("2 + ="),
                    RecognitionCandidate("1 / 0 ="),
                    RecognitionCandidate("2 + 2"),
                    RecognitionCandidate("NaN ="),
                ),
            ),
        )
    }

    @Test
    fun multilineCandidatesAreRejectedBeforeWhitespaceNormalization() {
        assertEquals(
            InkMathDecision.None,
            decideInkMath(
                listOf(
                    RecognitionCandidate("2 +\n3 ="),
                    RecognitionCandidate("2 +\r3 ="),
                ),
            ),
        )
    }

    @Test
    fun boundedRequestAcceptsValidLimits() {
        val point = RecognitionPoint(1f, 2f, 0L)
        val strokes = List(32) { RecognitionStroke(listOf(point)) }
        val request = boundedRecognitionRequest(
            pageId = "page-1",
            pageWidth = 100f,
            pageHeight = 200f,
            fingerprints = strokes.mapIndexed { index, _ -> RecognitionFingerprint("stroke-$index", index) },
            strokes = strokes,
        )

        assertEquals(32, request?.strokes?.size)
        assertEquals(32, request?.fingerprints?.size)
    }

    @Test
    fun boundedRequestSupportsLargerExplicitConversionLimits() {
        val point = RecognitionPoint(1f, 2f, 0L)
        val strokes = List(33) { RecognitionStroke(listOf(point)) }
        val request = boundedRecognitionRequest(
            pageId = "page-1",
            pageWidth = 100f,
            pageHeight = 200f,
            fingerprints = strokes.mapIndexed { index, _ -> RecognitionFingerprint("stroke-$index", index) },
            strokes = strokes,
            maxStrokes = 128,
            maxPoints = 16_384,
        )

        assertEquals(33, request?.strokes?.size)
    }

    @Test
    fun boundedRequestRejectsNonFinitePoints() {
        val request = boundedRecognitionRequest(
            pageId = "page-1",
            pageWidth = 100f,
            pageHeight = 200f,
            fingerprints = listOf(RecognitionFingerprint("stroke-1", 1)),
            strokes = listOf(RecognitionStroke(listOf(RecognitionPoint(Float.NaN, 2f, 0L)))),
        )

        assertTrue(request == null)
    }

    @Test
    fun boundedRequestRejectsTimestampOrderViolations() {
        val request = boundedRecognitionRequest(
            pageId = "page-1",
            pageWidth = 100f,
            pageHeight = 200f,
            fingerprints = listOf(RecognitionFingerprint("stroke-1", 1)),
            strokes = listOf(
                RecognitionStroke(
                    listOf(
                        RecognitionPoint(1f, 2f, 10L),
                        RecognitionPoint(2f, 3f, 9L),
                    ),
                ),
            ),
        )

        assertTrue(request == null)
    }

    @Test
    fun boundedRequestRejectsFingerprintMismatchAndInvalidBounds() {
        val stroke = RecognitionStroke(listOf(RecognitionPoint(1f, 2f, 0L)))
        assertTrue(
            boundedRecognitionRequest(
                pageId = "page-1",
                pageWidth = 100f,
                pageHeight = 200f,
                fingerprints = emptyList(),
                strokes = listOf(stroke),
            ) == null,
        )
        assertTrue(
            boundedRecognitionRequest(
                pageId = " ",
                pageWidth = 100f,
                pageHeight = 200f,
                fingerprints = listOf(RecognitionFingerprint("stroke-1", 1)),
                strokes = listOf(stroke),
            ) == null,
        )
        assertTrue(
            boundedRecognitionRequest(
                pageId = "page-1",
                pageWidth = Float.POSITIVE_INFINITY,
                pageHeight = 200f,
                fingerprints = listOf(RecognitionFingerprint("stroke-1", 1)),
                strokes = listOf(stroke),
            ) == null,
        )
    }

    @Test
    fun boundedRequestEnforcesExactPointAndStrokeBounds() {
        val point = RecognitionPoint(1f, 2f, 0L)
        val validStrokes = listOf(RecognitionStroke(List(4096) { point }))
        assertEquals(
            4096,
            boundedRecognitionRequest(
                pageId = "page-1",
                pageWidth = 100f,
                pageHeight = 200f,
                fingerprints = listOf(RecognitionFingerprint("stroke-1", 1)),
                strokes = validStrokes,
            )?.strokes?.sumOf { it.points.size },
        )

        assertTrue(
            boundedRecognitionRequest(
                pageId = "page-1",
                pageWidth = 100f,
                pageHeight = 200f,
                fingerprints = listOf(RecognitionFingerprint("stroke-1", 1)),
                strokes = listOf(RecognitionStroke(List(4097) { point })),
            ) == null,
        )
        assertTrue(
            boundedRecognitionRequest(
                pageId = "page-1",
                pageWidth = 100f,
                pageHeight = 200f,
                fingerprints = listOf(RecognitionFingerprint("stroke-1", 1)),
                strokes = listOf(RecognitionStroke(emptyList())),
            ) == null,
        )
        assertTrue(
            boundedRecognitionRequest(
                pageId = "page-1",
                pageWidth = 100f,
                pageHeight = 200f,
                fingerprints =
                    listOf(
                        RecognitionFingerprint("stroke-1", 1),
                        RecognitionFingerprint("stroke-2", 2),
                    ),
                strokes =
                    listOf(
                        RecognitionStroke(listOf(point)),
                        RecognitionStroke(emptyList()),
                    ),
            ) == null,
        )

        val tooManyStrokes = List(33) { RecognitionStroke(listOf(point)) }
        assertTrue(
            boundedRecognitionRequest(
                pageId = "page-1",
                pageWidth = 100f,
                pageHeight = 200f,
                fingerprints = tooManyStrokes.mapIndexed { index, _ ->
                    RecognitionFingerprint("stroke-$index", index)
                },
                strokes = tooManyStrokes,
            ) == null,
        )
    }
}
