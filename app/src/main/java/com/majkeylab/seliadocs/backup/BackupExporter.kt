package com.majkeylab.seliadocs.backup

import android.util.JsonWriter
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.BlockEntity
import com.majkeylab.seliadocs.data.ChapterEntity
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.NotebookContent
import com.majkeylab.seliadocs.data.NotebookEntity
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PdfSourceEntity
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.data.StrokeEntity
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class BackupExporter(
    private val repository: SeliaDocsRepository,
    private val assets: AssetStore,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxRecords: Int = MAX_BACKUP_RECORDS,
) {
    suspend fun export(scope: BackupScope, output: OutputStream): BackupSummary =
        withContext(Dispatchers.IO) {
            writePlan(createPlan(scope), output)
        }

    suspend fun export(scope: BackupScope, outputFactory: () -> OutputStream): BackupSummary =
        withContext(Dispatchers.IO) {
            val plan = createPlan(scope)
            outputFactory().use { output -> writePlan(plan, output) }
        }

    private suspend fun writePlan(plan: ExportPlan, output: OutputStream): BackupSummary {
        val counting = CountingOutputStream(output)
        val zip = ZipOutputStream(counting)
        val checksums = linkedMapOf<String, String>()
        val written = WrittenCounts()

        checksums[RECORDS_ENTRY] =
            zip.writeHashedEntry(RECORDS_ENTRY) { entry ->
                val writer = OutputStreamWriter(entry, Charsets.UTF_8)
                plan.notebookIds.forEach { notebookId ->
                    val content = loadNotebook(notebookId)
                    BackupJson.writeRecord(writer, content.notebook.toBackup())
                    content.chapters.forEach { BackupJson.writeRecord(writer, it.toBackup()) }
                    content.pdfSources.forEach { source ->
                        BackupJson.writeRecord(writer, source.toBackup())
                        written.assetIds += source.assetId
                    }
                    content.pages.forEach { BackupJson.writeRecord(writer, it.toBackup()) }
                    content.strokes.forEach { BackupJson.writeRecord(writer, it.toBackup()) }
                    content.elements.forEach { element ->
                        BackupJson.writeRecord(writer, element.toBackup())
                        element.assetId?.let(written.assetIds::add)
                    }
                    content.blocks.forEach { BackupJson.writeRecord(writer, it.toBackup()) }
                    written.pages += content.pages.size
                }
                writer.flush()
            }
        if (written.pages != plan.pageCount || written.assetIds != plan.assetFiles.keys) {
            throw BackupExportFailure.SourceChanged()
        }

        val manifest =
            BackupManifest(
                formatVersion = BACKUP_FORMAT_VERSION,
                appVersion = appVersion,
                exportedAt = clock(),
                notebookCount = plan.notebookIds.size,
                pageCount = plan.pageCount,
                assetCount = plan.assetFiles.size,
                featureFlags = featureFlags(plan),
            )
        checksums[MANIFEST_ENTRY] =
            zip.writeHashedEntry(MANIFEST_ENTRY) { entry ->
                BackupJson.writeManifest(OutputStreamWriter(entry, Charsets.UTF_8), manifest)
            }

        plan.assetFiles.forEach { (id, file) ->
            val entryName = "assets/$id"
            checksums[entryName] =
                zip.writeHashedEntry(entryName) { entry ->
                    try {
                        file.inputStream().buffered().use { input ->
                            input.copyTo(entry, COPY_BUFFER_SIZE)
                        }
                    } catch (_: FileNotFoundException) {
                        throw BackupExportFailure.MissingAsset(id)
                    }
                }
        }
        zip.writeHashedEntry(CHECKSUMS_ENTRY) { entry -> writeChecksums(entry, checksums) }
        zip.finish()
        zip.flush()
        return BackupSummary(
            notebooks = plan.notebookIds.size,
            pages = plan.pageCount,
            assets = plan.assetFiles.size,
            bytesWritten = counting.count,
        )
    }

    private suspend fun createPlan(scope: BackupScope): ExportPlan {
        val notebooks = repository.getAllNotebooks()
        val available = notebooks.associateBy(NotebookEntity::id)
        val notebookIds =
            when (scope) {
                is BackupScope.Notebook ->
                    listOf(
                        available[scope.id]?.id
                            ?: throw BackupExportFailure.MissingNotebook(scope.id),
                    )
                is BackupScope.Selected -> {
                    val missing = scope.ids.firstOrNull { it !in available }
                    if (missing != null) throw BackupExportFailure.MissingNotebook(missing)
                    notebooks.map(NotebookEntity::id).filter(scope.ids::contains)
                }
                BackupScope.Library -> notebooks.map(NotebookEntity::id)
            }
        var pageCount = 0
        var hasBlocks = false
        var hasChapters = false
        var hasPdfSources = false
        var recordCount = notebookIds.size.toLong()
        if (recordCount > maxRecords) throw BackupFailure.LimitExceeded("recordCount")
        val assetIds = linkedSetOf<String>()
        notebookIds.forEach { notebookId ->
            val content = loadNotebook(notebookId)
            recordCount +=
                content.chapters.size.toLong() +
                    content.pdfSources.size +
                    content.pages.size +
                    content.strokes.size +
                    content.elements.size +
                    content.blocks.size
            if (recordCount > maxRecords) throw BackupFailure.LimitExceeded("recordCount")
            pageCount += content.pages.size
            hasBlocks = hasBlocks || content.blocks.isNotEmpty()
            hasChapters = hasChapters || content.chapters.isNotEmpty()
            hasPdfSources = hasPdfSources || content.pdfSources.isNotEmpty()
            content.elements.mapNotNullTo(assetIds, ElementEntity::assetId)
            content.pdfSources.mapTo(assetIds, PdfSourceEntity::assetId)
        }
        val assetFiles =
            assetIds.associateWith { id ->
                try {
                    assets.requireFile(id)
                } catch (_: Exception) {
                    throw BackupExportFailure.MissingAsset(id)
                }
            }
        return ExportPlan(notebookIds, pageCount, assetFiles, hasBlocks, hasChapters, hasPdfSources)
    }

    private suspend fun loadNotebook(id: String): NotebookContent =
        try {
            repository.loadNotebook(id)
        } catch (_: IllegalArgumentException) {
            throw BackupExportFailure.MissingNotebook(id)
        }

    private fun featureFlags(plan: ExportPlan): Set<String> =
        buildSet {
            add("editable")
            if (plan.assetFiles.isNotEmpty()) add("assets")
            if (plan.hasBlocks) add("page-text")
            if (plan.hasChapters) add("chapters")
            if (plan.hasPdfSources) add("pdf-sources")
        }

    private suspend fun ZipOutputStream.writeHashedEntry(
        name: String,
        write: suspend (OutputStream) -> Unit,
    ): String {
        putNextEntry(ZipEntry(name).apply { time = 0L })
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val output = DigestOutputStream(this, digest)
            write(output)
            output.flush()
        } finally {
            closeEntry()
        }
        return digest.digest().toHex()
    }

    private fun writeChecksums(output: OutputStream, checksums: Map<String, String>) {
        JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).apply {
            beginObject()
            name("algorithm").value("SHA-256")
            name("entries")
            beginObject()
            checksums.toSortedMap().forEach { (entryName, hash) -> name(entryName).value(hash) }
            endObject()
            endObject()
            flush()
        }
    }

    private fun NotebookEntity.toBackup() =
        BackupNotebook(
            id = id,
            title = title,
            coverColor = coverColor,
            coverPattern = coverPattern,
            defaultPaper = defaultPaper,
            orientation = orientation,
            fingerDrawing = fingerDrawing,
            favorite = favorite,
            createdAt = createdAt,
            updatedAt = updatedAt,
            trashedAt = trashedAt,
        )

    private fun PageEntity.toBackup() =
        BackupPage(
            id = id,
            notebookId = notebookId,
            pageIndex = pageIndex,
            paper = paper,
            widthPoints = widthPoints,
            heightPoints = heightPoints,
            chapterId = chapterId,
            title = title,
            pageMode = pageMode,
            bookmarked = bookmarked,
            createdAt = createdAt,
            updatedAt = updatedAt,
            pdfSourceId = pdfSourceId,
            pdfPageIndex = pdfPageIndex,
        )

    private fun ChapterEntity.toBackup() =
        BackupChapter(id, notebookId, title, colorArgb, orderIndex)

    private fun PdfSourceEntity.toBackup() =
        BackupPdfSource(id, notebookId, assetId, displayName, pageCount, byteSize, sha256, createdAt)

    private fun StrokeEntity.toBackup() =
        BackupStroke(id, pageId, zIndex, brushKind, colorArgb, size, epsilon, inputs)

    private fun ElementEntity.toBackup() =
        BackupElement(
            id = id,
            pageId = pageId,
            zIndex = zIndex,
            kind = kind,
            x = x,
            y = y,
            width = width,
            height = height,
            rotation = rotation,
            text = text,
            assetId = assetId,
            shapeKind = shapeKind,
            expression = expression,
            resultText = resultText,
        )

    private fun BlockEntity.toBackup() =
        BackupBlock(id, pageId, orderIndex, kind, text, checked, indent, alignment, payloadId)

    private data class ExportPlan(
        val notebookIds: List<String>,
        val pageCount: Int,
        val assetFiles: Map<String, File>,
        val hasBlocks: Boolean,
        val hasChapters: Boolean,
        val hasPdfSources: Boolean,
    )

    private class WrittenCounts {
        var pages = 0
        val assetIds = linkedSetOf<String>()
    }

    private class CountingOutputStream(private val output: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(value: Int) {
            output.write(value)
            count++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            output.write(buffer, offset, length)
            count += length
        }

        override fun flush() = output.flush()
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

    private companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val RECORDS_ENTRY = "records.jsonl"
        const val CHECKSUMS_ENTRY = "checksums.json"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val HEX = "0123456789abcdef"
    }
}
