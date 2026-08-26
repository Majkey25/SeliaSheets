package com.majkeylab.seliadocs.editor

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PdfExportImageSampleTest {
    @Test
    fun largeImageUsesPowerOfTwoSample() {
        assertEquals(8, imageSampleSize(8_192, 8_192, 1_024, 1_024))
    }

    @Test
    fun smallerImageUsesFullResolution() {
        assertEquals(1, imageSampleSize(1_000, 800, 1_200, 900))
    }

    @Test
    fun pixelCeilingSamplesLargeImageEvenWhenTargetIsFourK() {
        assertEquals(2, imageSampleSize(8_000, 8_000, 4_096, 4_096))
    }

    @Test
    fun successfulRenderCompletesBeforeDestinationCopyAndDeletesTempFile() = runBlocking {
        val cache = Files.createTempDirectory("pdf-export-test-").toFile()
        val destination = ByteArrayOutputStream()
        var renderCompleted = false
        try {
            writePdfToDestination(
                cache,
                render = {
                    it.write(byteArrayOf(1, 2, 3))
                    renderCompleted = true
                },
                openDestination = {
                    assertEquals(true, renderCompleted)
                    destination
                },
                deleteDestination = { fail("Successful destination must not be deleted") },
            )

            assertArrayEquals(byteArrayOf(1, 2, 3), destination.toByteArray())
            assertEquals(emptyList<File>(), cache.listFiles().orEmpty().toList())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun failedRenderPreservesUnopenedDestinationAndDeletesTempFile() = runBlocking {
        val cache = Files.createTempDirectory("pdf-export-test-").toFile()
        var destinationOpened = false
        var destinationDeleted = false
        val expected = IllegalStateException("render failed")
        try {
            val actual =
                try {
                    writePdfToDestination(
                        cache,
                        render = { throw expected },
                        openDestination = {
                            destinationOpened = true
                            OutputStream.nullOutputStream()
                        },
                        deleteDestination = { destinationDeleted = true },
                    )
                    fail("Expected render failure")
                } catch (failure: IllegalStateException) {
                    failure
                }

            assertSame(expected, actual)
            assertFalse(destinationOpened)
            assertFalse(destinationDeleted)
            assertEquals(emptyList<File>(), cache.listFiles().orEmpty().toList())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun cancelledRenderPreservesUnopenedDestinationAndDeletesTempFile() = runBlocking {
        val cache = Files.createTempDirectory("pdf-export-test-").toFile()
        val expected = CancellationException("render cancelled")
        var destinationDeleted = false
        try {
            var actual: CancellationException? = null
            try {
                writePdfToDestination(
                    cache,
                    render = { throw expected },
                    openDestination = {
                        fail("Cancelled render must not open destination")
                        OutputStream.nullOutputStream()
                    },
                    deleteDestination = { destinationDeleted = true },
                )
                fail("Expected render cancellation")
            } catch (failure: CancellationException) {
                actual = failure
            }

            assertSame(expected, actual)
            assertFalse(destinationDeleted)
            assertEquals(emptyList<File>(), cache.listFiles().orEmpty().toList())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun failedCopyDeletesDestinationAndTempWithoutMaskingFailure() = runBlocking {
        val cache = Files.createTempDirectory("pdf-export-test-").toFile()
        val expected = IOException("copy failed")
        var destinationDeleted = false
        try {
            var actual: IOException? = null
            try {
                writePdfToDestination(
                    cache,
                    render = { it.write(byteArrayOf(1, 2, 3)) },
                    openDestination = {
                        object : OutputStream() {
                            override fun write(value: Int) = throw expected
                        }
                    },
                    deleteDestination = {
                        destinationDeleted = true
                        throw IllegalStateException("delete failed")
                    },
                )
                fail("Expected copy failure")
            } catch (failure: IOException) {
                actual = failure
            }

            assertSame(expected, actual)
            assertEquals("delete failed", actual?.suppressed?.single()?.message)
            assertEquals(true, destinationDeleted)
            assertEquals(emptyList<File>(), cache.listFiles().orEmpty().toList())
        } finally {
            cache.deleteRecursively()
        }
    }
}
