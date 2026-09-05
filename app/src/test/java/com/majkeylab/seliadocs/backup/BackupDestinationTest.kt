package com.majkeylab.seliadocs.backup

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupDestinationTest {
    @Test
    fun failedExportNeverOpensDestinationAndDeletesTempFile() = runBlocking {
        val cache = Files.createTempDirectory("backup-export-test-").toFile()
        val expected = IOException("export failed")
        var destinationOpened = false
        try {
            val actual =
                try {
                    writeBackupToDestination(
                        cache,
                        export = { throw expected },
                        openDestination = {
                            destinationOpened = true
                            OutputStream.nullOutputStream()
                        },
                        deleteDestination = { fail("Unopened destination must not be deleted") },
                    )
                    fail("Expected export failure")
                } catch (failure: IOException) {
                    failure
                }

            assertSame(expected, actual)
            assertFalse(destinationOpened)
            assertEquals(emptyList<java.io.File>(), cache.listFiles().orEmpty().toList())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun failedDestinationCopyDeletesPartialDestination() = runBlocking {
        val cache = Files.createTempDirectory("backup-export-test-").toFile()
        val expected = IOException("copy failed")
        var destinationDeleted = false
        try {
            val actual =
                try {
                    writeBackupToDestination(
                        cache,
                        export = { output ->
                            output.write(byteArrayOf(1, 2, 3))
                            BackupSummary(1, 1, 0, 3)
                        },
                        openDestination = {
                            object : OutputStream() {
                                override fun write(value: Int) = throw expected
                            }
                        },
                        deleteDestination = { destinationDeleted = true },
                    )
                    fail("Expected copy failure")
                } catch (failure: IOException) {
                    failure
                }

            assertSame(expected, actual)
            assertTrue(destinationDeleted)
            assertEquals(emptyList<java.io.File>(), cache.listFiles().orEmpty().toList())
        } finally {
            cache.deleteRecursively()
        }
    }
}
