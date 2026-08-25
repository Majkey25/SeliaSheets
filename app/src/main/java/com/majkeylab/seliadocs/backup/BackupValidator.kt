package com.majkeylab.seliadocs.backup

import android.util.JsonReader
import android.util.JsonToken
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

internal data class BackupIndex(
    val notebookIds: Set<String>,
    val pageIds: Set<String>,
    val strokeIds: Set<String>,
    val elementIds: Set<String>,
    val assetIds: Set<String>,
)

internal class StagedBackup(
    val directory: File,
    val manifest: BackupManifest,
    val index: BackupIndex,
    val recordsFile: File,
    val assetFiles: Map<String, File>,
) : AutoCloseable {
    override fun close() {
        directory.deleteRecursively()
    }
}

internal class BackupValidator(
    private val stagingRoot: File,
    private val maxEntryBytes: Long = 1024L * 1024 * 1024,
    private val maxExtractedBytes: () -> Long = {
        min(stagingRoot.usableSpace / 10 * 8, 8L * 1024 * 1024 * 1024)
    },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun validate(input: InputStream): StagedBackup =
        withContext(Dispatchers.IO) {
            val directory = createStagingDirectory()
            try {
                val extracted = extract(input, directory)
                verifyChecksums(extracted)
                val manifestFile =
                    extracted.files[MANIFEST_ENTRY]
                        ?: throw BackupFailure.MissingField(MANIFEST_ENTRY)
                val recordsFile =
                    extracted.files[RECORDS_ENTRY]
                        ?: throw BackupFailure.MissingField(RECORDS_ENTRY)
                val manifest = manifestFile.reader(Charsets.UTF_8).use(BackupJson::readManifest)
                val assetFiles = assetFiles(extracted.files)
                val index = validateRecords(recordsFile, manifest, assetFiles.keys)
                StagedBackup(directory, manifest, index, recordsFile, assetFiles)
            } catch (failure: CancellationException) {
                directory.deleteRecursively()
                throw failure
            } catch (failure: BackupFailure) {
                directory.deleteRecursively()
                throw failure
            } catch (failure: Exception) {
                directory.deleteRecursively()
                throw BackupFailure.Malformed(failure)
            }
        }

    private fun createStagingDirectory(): File {
        if ((!stagingRoot.isDirectory && !stagingRoot.mkdirs()) || !stagingRoot.canWrite()) {
            throw BackupFailure.Malformed()
        }
        val directory = File(stagingRoot, "restore-${idFactory()}")
        if (directory.exists() || !directory.mkdir()) throw BackupFailure.Malformed()
        return directory
    }

    private fun extract(input: InputStream, directory: File): ExtractedArchive {
        val files = linkedMapOf<String, File>()
        val hashes = linkedMapOf<String, String>()
        val destinations = mutableSetOf<String>()
        var totalBytes = 0L
        var entryCount = 0
        val totalLimit = maxExtractedBytes()
        ZipInputStream(BufferedInputStream(NonClosingInputStream(input))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) throw BackupFailure.LimitExceeded("entryCount")
                val destination = resolveEntry(directory, entry.name)
                if (!destinations.add(destination.path)) {
                    throw BackupFailure.DuplicateEntry(entry.name)
                }
                if (entry.isDirectory) {
                    if (!destination.mkdirs() && !destination.isDirectory) {
                        throw BackupFailure.Malformed()
                    }
                    zip.closeEntry()
                    continue
                }
                val parent = destination.parentFile ?: throw BackupFailure.InvalidPath(entry.name)
                if (!parent.mkdirs() && !parent.isDirectory) throw BackupFailure.Malformed()
                val digest = MessageDigest.getInstance("SHA-256")
                var entryBytes = 0L
                DigestOutputStream(destination.outputStream().buffered(), digest).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        entryBytes = checkedAdd(entryBytes, read.toLong(), maxEntryBytes, "entry")
                        totalBytes =
                            checkedAdd(
                                totalBytes,
                                read.toLong(),
                                totalLimit,
                                "extractedBytes",
                            )
                        output.write(buffer, 0, read)
                    }
                }
                files[entry.name] = destination
                hashes[entry.name] = digest.digest().toHex()
                zip.closeEntry()
            }
        }
        return ExtractedArchive(files, hashes)
    }

    private fun resolveEntry(directory: File, name: String): File {
        if (
            name.isBlank() ||
            name.indexOf('\u0000') >= 0 ||
            name.startsWith('/') ||
            name.startsWith('\\') ||
            DRIVE_PATH.matches(name) ||
            '\\' in name ||
            name.split('/').any { it == "." || it == ".." }
        ) {
            throw BackupFailure.InvalidPath(name)
        }
        val destination = File(directory, name).canonicalFile
        val prefix = directory.canonicalPath + File.separator
        if (!destination.path.startsWith(prefix)) throw BackupFailure.InvalidPath(name)
        return destination
    }

    private fun verifyChecksums(extracted: ExtractedArchive) {
        val checksumFile =
            extracted.files[CHECKSUMS_ENTRY]
                ?: throw BackupFailure.MissingField(CHECKSUMS_ENTRY)
        val expected = readChecksums(checksumFile)
        val actualNames = extracted.files.keys.filterNot { it == CHECKSUMS_ENTRY }.toSet()
        if (expected.keys != actualNames) {
            val entry = (expected.keys - actualNames).firstOrNull() ?: (actualNames - expected.keys).first()
            throw BackupFailure.ChecksumMismatch(entry)
        }
        expected.forEach { (entry, hash) ->
            val actual = extracted.hashes[entry]
            if (!hash.equals(actual, ignoreCase = true)) {
                throw BackupFailure.ChecksumMismatch(entry)
            }
        }
    }

    private fun readChecksums(file: File): Map<String, String> =
        file.reader(Charsets.UTF_8).use { input ->
            val reader = JsonReader(input)
            var algorithm: String? = null
            val entries = linkedMapOf<String, String>()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "algorithm" -> algorithm = reader.nextString()
                    "entries" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            if (entries.size >= MAX_ENTRIES) {
                                throw BackupFailure.LimitExceeded("checksumCount")
                            }
                            val name = reader.nextName()
                            if (entries.put(name, reader.nextString()) != null) {
                                throw BackupFailure.DuplicateEntry(name)
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT || algorithm != "SHA-256") {
                throw BackupFailure.Malformed()
            }
            entries
        }

    private fun assetFiles(files: Map<String, File>): Map<String, File> =
        buildMap {
            files.forEach { (entry, file) ->
                if (!entry.startsWith(ASSET_PREFIX)) return@forEach
                val id = entry.removePrefix(ASSET_PREFIX)
                if (!ASSET_ID.matches(id)) throw BackupFailure.InvalidPath(entry)
                put(id, file)
            }
        }

    private fun validateRecords(
        recordsFile: File,
        manifest: BackupManifest,
        stagedAssetIds: Set<String>,
    ): BackupIndex {
        val notebooks = linkedSetOf<String>()
        val pages = linkedSetOf<String>()
        val strokes = linkedSetOf<String>()
        val elements = linkedSetOf<String>()
        val pageNotebooks = mutableListOf<Pair<String, String>>()
        val pageIndexes = mutableMapOf<String, MutableList<Int>>()
        val strokePages = mutableListOf<Pair<String, String>>()
        val elementPages = mutableListOf<Pair<String, String>>()
        val referencedAssets = linkedSetOf<String>()
        recordsFile.reader(Charsets.UTF_8).use { input ->
            BackupJson.records(input).forEach { record ->
                when (record) {
                    is BackupNotebook -> addUnique(notebooks, record.id, "notebook")
                    is BackupPage -> {
                        addUnique(pages, record.id, "page")
                        pageNotebooks += record.id to record.notebookId
                        pageIndexes.getOrPut(record.notebookId, ::mutableListOf) += record.pageIndex
                    }
                    is BackupStroke -> {
                        addUnique(strokes, record.id, "stroke")
                        strokePages += record.id to record.pageId
                    }
                    is BackupElement -> {
                        addUnique(elements, record.id, "element")
                        elementPages += record.id to record.pageId
                        record.assetId?.let(referencedAssets::add)
                    }
                }
            }
        }
        if (notebooks.size != manifest.notebookCount) countFailure("notebookCount")
        if (pages.size != manifest.pageCount) countFailure("pageCount")
        pageNotebooks.firstOrNull { it.second !in notebooks }?.let {
            throw BackupFailure.InvalidRelationship("page:${it.first}")
        }
        strokePages.firstOrNull { it.second !in pages }?.let {
            throw BackupFailure.InvalidRelationship("stroke:${it.first}")
        }
        elementPages.firstOrNull { it.second !in pages }?.let {
            throw BackupFailure.InvalidRelationship("element:${it.first}")
        }
        notebooks.firstOrNull { id ->
            val indexes = pageIndexes[id].orEmpty()
            indexes.isEmpty() || indexes.sorted() != indexes.indices.toList()
        }
            ?.let { throw BackupFailure.InvalidRelationship("pageIndex:$it") }
        referencedAssets.firstOrNull { it !in stagedAssetIds }?.let {
            throw BackupFailure.MissingAsset(it)
        }
        if (referencedAssets != stagedAssetIds) {
            throw BackupFailure.InvalidRelationship("assets")
        }
        if (stagedAssetIds.size != manifest.assetCount) countFailure("assetCount")
        return BackupIndex(notebooks, pages, strokes, elements, referencedAssets)
    }

    private fun addUnique(target: MutableSet<String>, id: String, kind: String) {
        if (!target.add(id)) throw BackupFailure.InvalidRelationship("duplicate:$kind:$id")
    }

    private fun countFailure(field: String): Nothing =
        throw BackupFailure.InvalidRelationship(field)

    private fun checkedAdd(current: Long, added: Long, limit: Long, field: String): Long {
        if (limit < 0 || added > limit - current) throw BackupFailure.LimitExceeded(field)
        return current + added
    }

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private fun ByteArray.toHex(): String {
        val output = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = HEX[value ushr 4]
            output[index * 2 + 1] = HEX[value and 0x0f]
        }
        return output.concatToString()
    }

    private data class ExtractedArchive(
        val files: Map<String, File>,
        val hashes: Map<String, String>,
    )

    private companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val RECORDS_ENTRY = "records.jsonl"
        const val CHECKSUMS_ENTRY = "checksums.json"
        const val ASSET_PREFIX = "assets/"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_ENTRIES = 100_000
        const val HEX = "0123456789abcdef"
        val DRIVE_PATH = Regex("^[A-Za-z]:.*")
        val ASSET_ID = Regex("[A-Za-z0-9._-]+")
    }
}
