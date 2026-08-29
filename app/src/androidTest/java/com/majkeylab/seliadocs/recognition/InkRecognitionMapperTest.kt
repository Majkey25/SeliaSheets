package com.majkeylab.seliadocs.recognition

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.vision.digitalink.common.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkRecognitionMapperTest {
    @Test
    fun buildsRecognitionContextForValidRequest() {
        validRequest(
            listOf(RecognitionStroke(listOf(RecognitionPoint(1f, 2f, 0L)))),
        ).toMlKitRecognitionContext()
    }

    @Test
    fun mapsStrokesInOrderWithUnchangedCoordinatesAndNondecreasingTimes() {
        val request = validRequest(
            strokes = listOf(
                RecognitionStroke(
                    listOf(
                        RecognitionPoint(1f, 2f, 0L),
                        RecognitionPoint(3f, 4f, 5L),
                    ),
                ),
                RecognitionStroke(
                    listOf(
                        RecognitionPoint(5f, 6f, 0L),
                        RecognitionPoint(7f, 8f, 2L),
                    ),
                ),
            ),
        )

        val ink = request.toMlKitInk()
        val strokes = ink.getStrokes()

        assertEquals(2, strokes.size)
        assertPoint(strokes[0].getPointsInGlobalCoordinates()[0], 1f, 2f)
        assertPoint(strokes[0].getPointsInGlobalCoordinates()[1], 3f, 4f)
        assertPoint(strokes[1].getPointsInGlobalCoordinates()[0], 5f, 6f)
        assertPoint(strokes[1].getPointsInGlobalCoordinates()[1], 7f, 8f)
        val times: List<Long> = strokes.flatMap { stroke ->
            stroke.getPointsInGlobalCoordinates().map { requireNotNull(it.getTimestamp()) }
        }
        assertTrue(times.zipWithNext().all { (first, second) -> first <= second })
        assertTrue(
            requireNotNull(strokes[1].getPointsInGlobalCoordinates().first().getTimestamp()) >
                requireNotNull(strokes[0].getPointsInGlobalCoordinates().last().getTimestamp()),
        )
    }

    @Test
    fun mapsMaximumValidatedRequestWithoutEmptyStrokes() {
        val strokes = List(32) { index ->
            RecognitionStroke(
                List(128) { pointIndex ->
                    RecognitionPoint(index.toFloat(), pointIndex.toFloat(), pointIndex.toLong())
                },
            )
        }
        val request = validRequest(strokes)

        val ink = request.toMlKitInk()
        val mappedStrokes = ink.getStrokes()

        assertEquals(32, mappedStrokes.size)
        assertFalse(mappedStrokes.any { it.getPointsInGlobalCoordinates().isEmpty() })
        assertEquals(4096, mappedStrokes.sumOf { it.getPointsInGlobalCoordinates().size })
    }

    @Test
    fun normalizesMaxTimestampBeforeLaterZeroTimestampStroke() {
        val request = validRequest(
            listOf(
                RecognitionStroke(listOf(RecognitionPoint(1f, 1f, Long.MAX_VALUE))),
                RecognitionStroke(listOf(RecognitionPoint(2f, 2f, 0L))),
            ),
        )

        val timestamps = request.toMlKitInk().getStrokes().flatMap { stroke ->
            stroke.getPointsInGlobalCoordinates().map { requireNotNull(it.getTimestamp()) }
        }

        assertTrue(timestamps.zipWithNext().all { (first, second) -> first <= second })
        assertTrue(timestamps[1] > timestamps[0])
    }

    @Test
    fun leavesRoomAfterMaximumRelativeStrokeForLaterStroke() {
        val request = validRequest(
            listOf(
                RecognitionStroke(
                    listOf(
                        RecognitionPoint(1f, 1f, 0L),
                        RecognitionPoint(2f, 2f, Long.MAX_VALUE),
                    ),
                ),
                RecognitionStroke(listOf(RecognitionPoint(3f, 3f, 0L))),
            ),
        )

        val strokes = request.toMlKitInk().getStrokes()
        val priorLast = requireNotNull(strokes[0].getPointsInGlobalCoordinates().last().getTimestamp())
        val laterFirst = requireNotNull(strokes[1].getPointsInGlobalCoordinates().first().getTimestamp())

        assertTrue(laterFirst > priorLast)
    }

    @Test
    fun rejectsEmptyAndCorruptInputAtTaskOneBoundary() {
        assertNull(
            boundedRecognitionRequest(
                pageId = "page",
                pageWidth = 100f,
                pageHeight = 100f,
                fingerprints = emptyList(),
                strokes = emptyList(),
            ),
        )
        assertNull(
            boundedRecognitionRequest(
                pageId = "page",
                pageWidth = 100f,
                pageHeight = 100f,
                fingerprints = listOf(RecognitionFingerprint("stroke", 1)),
                strokes = listOf(
                    RecognitionStroke(
                        listOf(RecognitionPoint(Float.NaN, 1f, 0L)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun mapperRejectsEmptyStrokeInsteadOfSilentlyDroppingIt() {
        val request =
            RecognitionRequest(
                pageId = "page",
                pageWidth = 100f,
                pageHeight = 100f,
                fingerprints =
                    listOf(
                        RecognitionFingerprint("stroke-1", 1),
                        RecognitionFingerprint("stroke-2", 2),
                    ),
                strokes =
                    listOf(
                        RecognitionStroke(listOf(RecognitionPoint(1f, 2f, 0L))),
                        RecognitionStroke(emptyList()),
                    ),
            )

        assertThrows(IllegalArgumentException::class.java) { request.toMlKitInk() }
    }

    private fun validRequest(strokes: List<RecognitionStroke>): RecognitionRequest {
        return requireNotNull(
            boundedRecognitionRequest(
                pageId = "page",
                pageWidth = 100f,
                pageHeight = 100f,
                fingerprints = strokes.indices.map { RecognitionFingerprint("stroke-$it", it) },
                strokes = strokes,
            ),
        )
    }

    private fun assertPoint(point: Point, x: Float, y: Float) {
        assertEquals(x, point.getX(), 0f)
        assertEquals(y, point.getY(), 0f)
    }
}
