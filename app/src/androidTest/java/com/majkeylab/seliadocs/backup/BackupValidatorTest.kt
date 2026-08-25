package com.majkeylab.seliadocs.backup

import android.content.Context
import android.util.JsonWriter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupValidatorTest {
    private lateinit var stagingRoot: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        stagingRoot = File(context.cacheDir, "backup-validation-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        stagingRoot.deleteRecursively()
    }

    @Test
    fun validArchiveStagesAndIndexesRecords() = runTest {
        val validator = validator()

        validator.validate(ByteArrayInputStream(validArchive())).use { backup ->
            assertEquals(1, backup.manifest.notebookCount)
            assertEquals(setOf("notebook"), backup.index.notebookIds)
            assertEquals(setOf("page"), backup.index.pageIds)
        }

        assertTrue(stagingRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun parentAndAbsolutePathsAreRejected() = runTest {
        assertFailure<BackupFailure.InvalidPath>(archive(listOf("../outside" to byteArrayOf(1))))
        assertFailure<BackupFailure.InvalidPath>(archive(listOf("/absolute" to byteArrayOf(1))))
    }

    @Test
    fun duplicateResolvedPathIsRejected() = runTest {
        assertFailure<BackupFailure.DuplicateEntry>(
            archive(
                listOf(
                    "assets/asset.bin" to byteArrayOf(1),
                    "assets//asset.bin" to byteArrayOf(2),
                ),
            ),
        )
    }

    @Test
    fun wrongChecksumIsRejected() = runTest {
        val entries = validContent()
        val checksums = entries.associate { it.first to sha256(it.second) }.toMutableMap()
        checksums["records.jsonl"] = "0".repeat(64)

        assertFailure<BackupFailure.ChecksumMismatch>(
            archive(entries + ("checksums.json" to checksumBytes(checksums))),
        )
    }

    @Test
    fun unsupportedVersionIsRejected() = runTest {
        val manifest =
            """{"formatVersion":2,"appVersion":"test","exportedAt":1,"notebookCount":1,"pageCount":1,"assetCount":0}"""
                .toByteArray()
        val records = validRecords()

        assertFailure<BackupFailure.UnsupportedVersion>(archiveWithChecksums(manifest, records))
    }

    @Test
    fun foreignKeyMismatchIsRejected() = runTest {
        val records = records(BackupPage("page", "missing", 0, "GRID", 595, 842))
        val manifest = manifestBytes(notebooks = 0, pages = 1, assets = 0)

        assertFailure<BackupFailure.InvalidRelationship>(archiveWithChecksums(manifest, records))
    }

    @Test
    fun missingReferencedAssetIsRejected() = runTest {
        val records =
            records(
                notebook(),
                page(),
                BackupElement(
                    "element",
                    "page",
                    0,
                    "IMAGE",
                    0f,
                    0f,
                    20f,
                    20f,
                    0f,
                    null,
                    "missing.png",
                    null,
                    null,
                    null,
                ),
            )
        val manifest = manifestBytes(notebooks = 1, pages = 1, assets = 1)

        assertFailure<BackupFailure.MissingAsset>(archiveWithChecksums(manifest, records))
    }

    @Test
    fun entryAndTotalExtractionLimitsAreEnforced() = runTest {
        val bytes = validArchive()
        assertFailure<BackupFailure.LimitExceeded>(
            bytes,
            BackupValidator(stagingRoot, maxEntryBytes = 10, maxExtractedBytes = { Long.MAX_VALUE }),
        )
        assertFailure<BackupFailure.LimitExceeded>(
            bytes,
            BackupValidator(stagingRoot, maxEntryBytes = Long.MAX_VALUE, maxExtractedBytes = { 10 }),
        )
    }

    @Test
    fun interruptedStreamIsRejectedAndCleaned() = runTest {
        val failure =
            runCatching {
                    validator().validate(FailingInputStream(validArchive(), failAfter = 80))
                }
                .exceptionOrNull()

        assertTrue(failure is BackupFailure)
        assertTrue(stagingRoot.listFiles().orEmpty().isEmpty())
    }

    private fun validator() =
        BackupValidator(
            stagingRoot = stagingRoot,
            maxEntryBytes = 1024 * 1024,
            maxExtractedBytes = { 4L * 1024 * 1024 },
        )

    private suspend inline fun <reified T : BackupFailure> assertFailure(
        bytes: ByteArray,
        validator: BackupValidator = validator(),
    ) {
        val failure = runCatching { validator.validate(ByteArrayInputStream(bytes)) }.exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, got $failure", failure is T)
        assertTrue(stagingRoot.listFiles().orEmpty().isEmpty())
    }

    private fun validArchive(): ByteArray {
        val content = validContent()
        return archive(content + ("checksums.json" to checksumBytes(content.associate { it.first to sha256(it.second) })))
    }

    private fun validContent(): List<Pair<String, ByteArray>> =
        listOf(
            "manifest.json" to manifestBytes(notebooks = 1, pages = 1, assets = 0),
            "records.jsonl" to validRecords(),
        )

    private fun validRecords(): ByteArray = records(notebook(), page())

    private fun notebook() =
        BackupNotebook(
            "notebook",
            "Physics",
            "PERIWINKLE",
            "SOLID",
            "GRID",
            "PORTRAIT",
            false,
            false,
            1L,
            1L,
            null,
        )

    private fun page() = BackupPage("page", "notebook", 0, "GRID", 595, 842)

    private fun manifestBytes(notebooks: Int, pages: Int, assets: Int): ByteArray {
        val output = StringWriter()
        BackupJson.writeManifest(
            output,
            BackupManifest(1, "test", 1L, notebooks, pages, assets),
        )
        return output.toString().toByteArray()
    }

    private fun records(vararg records: BackupRecord): ByteArray {
        val output = StringWriter()
        records.forEach { BackupJson.writeRecord(output, it) }
        return output.toString().toByteArray()
    }

    private fun archiveWithChecksums(manifest: ByteArray, records: ByteArray): ByteArray {
        val content = listOf("manifest.json" to manifest, "records.jsonl" to records)
        return archive(content + ("checksums.json" to checksumBytes(content.associate { it.first to sha256(it.second) })))
    }

    private fun checksumBytes(checksums: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
            writer.beginObject()
            writer.name("algorithm").value("SHA-256")
            writer.name("entries").beginObject()
            checksums.forEach { (name, hash) -> writer.name(name).value(hash) }
            writer.endObject()
            writer.endObject()
        }
        return output.toByteArray()
    }

    private fun archive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class FailingInputStream(
        private val bytes: ByteArray,
        private val failAfter: Int,
    ) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index >= failAfter) throw IOException("Interrupted")
            return if (index >= bytes.size) -1 else bytes[index++].toInt() and 0xff
        }
    }
}
