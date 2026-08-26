package com.majkeylab.seliadocs.backup

import android.graphics.BitmapFactory
import android.util.JsonReader
import android.util.JsonToken
import com.majkeylab.seliadocs.data.pageTextFits
import com.majkeylab.seliadocs.editor.BrushKind
import com.majkeylab.seliadocs.editor.EncodedStroke
import com.majkeylab.seliadocs.editor.InkCodec
import com.majkeylab.seliadocs.pdf.PdfDocumentInfo
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal data class BackupIndex(
    val notebookIds: Set<String>,
    val chapterIds: Set<String>,
    val pdfSourceIds: Set<String>,
    val pageIds: Set<String>,
    val strokeIds: Set<String>,
    val elementIds: Set<String>,
    val blockIds: Set<String>,
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
    private val inspectPdf: suspend (File) -> PdfDocumentInfo,
    private val maxEntryBytes: Long = 1024L * 1024 * 1024,
    private val maxExtractedBytes: () -> Long = {
        min(stagingRoot.usableSpace / 10 * 8, 8L * 1024 * 1024 * 1024)
    },
    private val maxRecords: Int = MAX_BACKUP_RECORDS,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun validate(input: InputStream): StagedBackup {
        var completed: StagedBackup? = null
        try {
            return withContext(Dispatchers.IO) {
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
                    val index = validateRecords(recordsFile, manifest, assetFiles)
                    StagedBackup(directory, manifest, index, recordsFile, assetFiles).also { completed = it }
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
        } catch (failure: CancellationException) {
            completed?.close()
            throw failure
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
                val entry =
                    try {
                        zip.nextEntry
                    } catch (failure: ZipException) {
                        if (failure.message.orEmpty().contains("path", ignoreCase = true)) {
                            throw BackupFailure.InvalidPath("archive-entry")
                        }
                        throw failure
                    } ?: break
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

    private suspend fun validateRecords(
        recordsFile: File,
        manifest: BackupManifest,
        stagedAssets: Map<String, File>,
    ): BackupIndex {
        val notebooks = linkedSetOf<String>()
        val chapters = linkedSetOf<String>()
        val pdfSources = linkedSetOf<String>()
        val pages = linkedSetOf<String>()
        val pageSizes = mutableMapOf<String, Pair<Int, Int>>()
        val strokes = linkedSetOf<String>()
        val elements = linkedSetOf<String>()
        val blocks = linkedSetOf<String>()
        val pageNotebooks = mutableMapOf<String, String>()
        val chapterNotebooks = mutableMapOf<String, String>()
        val chapterIndexes = mutableMapOf<String, MutableList<Int>>()
        val pageChapters = mutableListOf<Pair<String, String>>()
        val pdfSourceNotebooks = mutableMapOf<String, String>()
        val pdfSourcePageCounts = mutableMapOf<String, Int>()
        val pdfSourceRecords = mutableListOf<BackupPdfSource>()
        val pagePdfSources = mutableListOf<Pair<String, String>>()
        val pdfPageIndexes = mutableMapOf<String, MutableList<Int>>()
        val pageIndexes = mutableMapOf<String, MutableList<Int>>()
        val strokePages = mutableListOf<Pair<String, String>>()
        val elementPages = mutableListOf<Pair<String, String>>()
        val pageElements = mutableMapOf<String, MutableList<BackupElement>>()
        val blockPages = mutableListOf<Pair<String, String>>()
        val blockIndexes = mutableMapOf<String, MutableList<Int>>()
        val pageBlocks = mutableMapOf<String, MutableList<BackupBlock>>()
        val referencedAssets = linkedSetOf<String>()
        val imageAssetIds = linkedSetOf<String>()
        var recordCount = 0
        recordsFile.reader(Charsets.UTF_8).use { input ->
            BackupJson.records(input).forEach { record ->
                recordCount++
                if (recordCount > maxRecords) throw BackupFailure.LimitExceeded("recordCount")
                when (record) {
                    is BackupNotebook -> addUnique(notebooks, record.id, "notebook")
                    is BackupChapter -> {
                        addUnique(chapters, record.id, "chapter")
                        chapterNotebooks[record.id] = record.notebookId
                        chapterIndexes.getOrPut(record.notebookId, ::mutableListOf) += record.orderIndex
                    }
                    is BackupPdfSource -> {
                        addUnique(pdfSources, record.id, "pdfSource")
                        pdfSourceNotebooks[record.id] = record.notebookId
                        pdfSourcePageCounts[record.id] = record.pageCount
                        pdfSourceRecords += record
                        referencedAssets += record.assetId
                    }
                    is BackupPage -> {
                        addUnique(pages, record.id, "page")
                        pageSizes[record.id] = record.widthPoints to record.heightPoints
                        pageNotebooks[record.id] = record.notebookId
                        pageIndexes.getOrPut(record.notebookId, ::mutableListOf) += record.pageIndex
                        record.chapterId?.let { pageChapters += record.id to it }
                        if ((record.pdfSourceId == null) != (record.pdfPageIndex == null)) {
                            throw BackupFailure.InvalidRelationship("pdfPage:${record.id}")
                        }
                        record.pdfSourceId?.let { sourceId ->
                            pagePdfSources += record.id to sourceId
                            pdfPageIndexes.getOrPut(sourceId, ::mutableListOf) += requireNotNull(record.pdfPageIndex)
                        }
                    }
                    is BackupStroke -> {
                        addUnique(strokes, record.id, "stroke")
                        strokePages += record.id to record.pageId
                        validateStroke(record)
                    }
                    is BackupElement -> {
                        addUnique(elements, record.id, "element")
                        elementPages += record.id to record.pageId
                        pageElements.getOrPut(record.pageId, ::mutableListOf) += record
                        record.assetId?.let(referencedAssets::add)
                        if (record.kind == "IMAGE") record.assetId?.let(imageAssetIds::add)
                    }
                    is BackupBlock -> {
                        addUnique(blocks, record.id, "block")
                        blockPages += record.id to record.pageId
                        blockIndexes.getOrPut(record.pageId, ::mutableListOf) += record.orderIndex
                        pageBlocks.getOrPut(record.pageId, ::mutableListOf) += record
                    }
                }
            }
        }
        if (notebooks.size != manifest.notebookCount) countFailure("notebookCount")
        if (pages.size != manifest.pageCount) countFailure("pageCount")
        pageNotebooks.entries.firstOrNull { it.value !in notebooks }?.let {
            throw BackupFailure.InvalidRelationship("page:${it.key}")
        }
        chapterNotebooks.entries.firstOrNull { it.value !in notebooks }?.let {
            throw BackupFailure.InvalidRelationship("chapter:${it.key}")
        }
        pdfSourceNotebooks.entries.firstOrNull { it.value !in notebooks }?.let {
            throw BackupFailure.InvalidRelationship("pdfSource:${it.key}")
        }
        pagePdfSources.firstOrNull { it.second !in pdfSources }?.let {
            throw BackupFailure.InvalidRelationship("pdfPageSource:${it.first}")
        }
        pdfSourcePageCounts.forEach { (sourceId, expectedCount) ->
            val indexes = pdfPageIndexes[sourceId].orEmpty()
            if (indexes.isEmpty() || indexes.any { it !in 0 until expectedCount }) {
                throw BackupFailure.InvalidRelationship("pdfPageIndex:$sourceId")
            }
        }
        chapterIndexes.entries.firstOrNull { (_, indexes) ->
            indexes.sorted() != indexes.indices.toList()
        }
            ?.let { throw BackupFailure.InvalidRelationship("chapterIndex:${it.key}") }
        pageChapters.firstOrNull { it.second !in chapters }?.let {
            throw BackupFailure.InvalidRelationship("pageChapter:${it.first}")
        }
        pageChapters.firstOrNull { (pageId, chapterId) ->
            pageNotebooks.getValue(pageId) != chapterNotebooks.getValue(chapterId)
        }
            ?.let { throw BackupFailure.InvalidRelationship("pageChapter:${it.first}") }
        pagePdfSources.firstOrNull { (pageId, sourceId) ->
            pageNotebooks.getValue(pageId) != pdfSourceNotebooks.getValue(sourceId)
        }
            ?.let { throw BackupFailure.InvalidRelationship("pdfPageSource:${it.first}") }
        strokePages.firstOrNull { it.second !in pages }?.let {
            throw BackupFailure.InvalidRelationship("stroke:${it.first}")
        }
        elementPages.firstOrNull { it.second !in pages }?.let {
            throw BackupFailure.InvalidRelationship("element:${it.first}")
        }
        blockPages.firstOrNull { it.second !in pages }?.let {
            throw BackupFailure.InvalidRelationship("block:${it.first}")
        }
        blockIndexes.entries.firstOrNull { (_, indexes) ->
            indexes.size > 1 || indexes.sorted() != indexes.indices.toList()
        }
            ?.let { throw BackupFailure.InvalidRelationship("blockIndex:${it.key}") }
        pageBlocks.forEach { (pageId, records) ->
            val size = pageSizes[pageId] ?: return@forEach
            val text = records.sortedBy(BackupBlock::orderIndex).joinToString("\n") { it.text.orEmpty() }
            if (!pageTextFits(text, size.first, size.second)) {
                throw BackupFailure.InvalidRelationship("blockText:$pageId")
            }
        }
        pageElements.forEach { (pageId, records) ->
            val size = pageSizes[pageId] ?: return@forEach
            records.forEach { record ->
                validateElementBounds(
                    record,
                    size.first,
                    size.second,
                    strict = manifest.formatVersion >= 3,
                )
            }
        }
        if (blocks.isNotEmpty() && manifest.formatVersion < 2) {
            throw BackupFailure.InvalidRelationship("page-text-version")
        }
        if (blocks.isNotEmpty() && "page-text" !in manifest.featureFlags) {
            throw BackupFailure.InvalidRelationship("page-text-feature")
        }
        if (chapters.isNotEmpty() && manifest.formatVersion < 2) {
            throw BackupFailure.InvalidRelationship("chapters-version")
        }
        if (chapters.isNotEmpty() && "chapters" !in manifest.featureFlags) {
            throw BackupFailure.InvalidRelationship("chapters-feature")
        }
        if (pdfSources.isNotEmpty() && manifest.formatVersion < 3) {
            throw BackupFailure.InvalidRelationship("pdf-sources-version")
        }
        if (pdfSources.isNotEmpty() && "pdf-sources" !in manifest.featureFlags) {
            throw BackupFailure.InvalidRelationship("pdf-sources-feature")
        }
        notebooks.firstOrNull { id ->
            val indexes = pageIndexes[id].orEmpty()
            indexes.isEmpty() || indexes.sorted() != indexes.indices.toList()
        }
            ?.let { throw BackupFailure.InvalidRelationship("pageIndex:$it") }
        referencedAssets.firstOrNull { it !in stagedAssets }?.let {
            throw BackupFailure.MissingAsset(it)
        }
        if (referencedAssets != stagedAssets.keys) {
            throw BackupFailure.InvalidRelationship("assets")
        }
        imageAssetIds.forEach { assetId -> validateImageAsset(assetId, stagedAssets.getValue(assetId)) }
        pdfSourceRecords.forEach { source -> validatePdfAsset(source, stagedAssets.getValue(source.assetId)) }
        if (stagedAssets.size != manifest.assetCount) countFailure("assetCount")
        return BackupIndex(notebooks, chapters, pdfSources, pages, strokes, elements, blocks, referencedAssets)
    }

    private fun validateStroke(record: BackupStroke) {
        val stroke =
            runCatching {
                InkCodec.decode(
                    EncodedStroke(
                        BrushKind.valueOf(record.brushKind),
                        record.colorArgb,
                        record.size,
                        record.epsilon,
                        record.inputs,
                    ),
                )
            }.getOrElse { throw BackupFailure.InvalidRelationship("strokeData:${record.id}") }
        if (stroke.inputs.size == 0) throw BackupFailure.InvalidRelationship("strokeData:${record.id}")
    }

    private fun validateElementBounds(record: BackupElement, pageWidth: Int, pageHeight: Int, strict: Boolean) {
        if (
            record.width > MAX_LEGACY_ELEMENT_DIMENSION ||
            record.height > MAX_LEGACY_ELEMENT_DIMENSION ||
            abs(record.x) > MAX_LEGACY_ELEMENT_COORDINATE ||
            abs(record.y) > MAX_LEGACY_ELEMENT_COORDINATE
        ) {
            throw BackupFailure.InvalidRelationship("elementBounds:${record.id}")
        }
        if (!strict) return
        val radians = Math.toRadians(record.rotation.toDouble())
        val extentX = record.width / 2f * abs(cos(radians)).toFloat() + record.height / 2f * abs(sin(radians)).toFloat()
        val extentY = record.width / 2f * abs(sin(radians)).toFloat() + record.height / 2f * abs(cos(radians)).toFloat()
        val centerX = record.x + record.width / 2f
        val centerY = record.y + record.height / 2f
        if (
            centerX - extentX < -GEOMETRY_EPSILON ||
            centerY - extentY < -GEOMETRY_EPSILON ||
            centerX + extentX > pageWidth + GEOMETRY_EPSILON ||
            centerY + extentY > pageHeight + GEOMETRY_EPSILON
        ) {
            throw BackupFailure.InvalidRelationship("elementBounds:${record.id}")
        }
    }

    private fun validateImageAsset(assetId: String, file: File) {
        if (file.length() !in 1..MAX_IMAGE_BYTES) throw BackupFailure.InvalidRelationship("imageAsset:$assetId")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (
            bounds.outMimeType?.lowercase() !in IMAGE_MIME_TYPES ||
            bounds.outWidth !in 1..MAX_IMAGE_DIMENSION ||
            bounds.outHeight !in 1..MAX_IMAGE_DIMENSION ||
            bounds.outWidth.toLong() * bounds.outHeight * 4 > MAX_IMAGE_BYTES
        ) {
            throw BackupFailure.InvalidRelationship("imageAsset:$assetId")
        }
        var sample = 1
        while (bounds.outWidth / sample > 2_048 || bounds.outHeight / sample > 2_048) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw BackupFailure.InvalidRelationship("imageAsset:$assetId")
        decoded.recycle()
    }

    private suspend fun validatePdfAsset(source: BackupPdfSource, file: File) {
        if (file.length() != source.byteSize || file.length() !in 1..MAX_PDF_BYTES) {
            throw BackupFailure.InvalidRelationship("pdfAsset:${source.id}")
        }
        val header = ByteArray(PDF_HEADER.size)
        val read = file.inputStream().use { it.read(header) }
        if (read != header.size || !header.contentEquals(PDF_HEADER) || sha256(file) != source.sha256) {
            throw BackupFailure.InvalidRelationship("pdfAsset:${source.id}")
        }
        val info =
            runCatching { inspectPdf(file) }
                .getOrElse { throw BackupFailure.InvalidRelationship("pdfAsset:${source.id}") }
        if (info.pages.size != source.pageCount) {
            throw BackupFailure.InvalidRelationship("pdfPageCount:${source.id}")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
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
        const val MAX_IMAGE_DIMENSION = 16_384
        const val MAX_IMAGE_BYTES = 128L * 1024 * 1024
        const val MAX_PDF_BYTES = 256L * 1024 * 1024
        const val GEOMETRY_EPSILON = 0.01f
        const val MAX_LEGACY_ELEMENT_DIMENSION = 100_000f
        const val MAX_LEGACY_ELEMENT_COORDINATE = 1_000_000f
        const val HEX = "0123456789abcdef"
        val PDF_HEADER = "%PDF-".toByteArray(Charsets.US_ASCII)
        val IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic")
        val DRIVE_PATH = Regex("^[A-Za-z]:.*")
        val ASSET_ID = Regex("[A-Za-z0-9._-]+")
    }
}
