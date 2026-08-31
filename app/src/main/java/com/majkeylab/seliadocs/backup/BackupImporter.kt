package com.majkeylab.seliadocs.backup

import androidx.room.withTransaction
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.BlockEntity
import com.majkeylab.seliadocs.data.ChapterEntity
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.NotebookEntity
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PdfSourceEntity
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.data.StrokeEntity
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class BackupImporter(
    private val database: SeliaDocsDatabase,
    private val repository: SeliaDocsRepository,
    private val assets: AssetStore,
    private val validator: BackupValidator,
    private val stagingRoot: File,
    private val appVersion: String,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun restore(input: InputStream, mode: RestoreMode): RestoreSummary =
        validator.validate(input).use { backup ->
            LibraryMutationGate.withLock {
                var completed: RestoreSummary? = null
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val mappings = createMappings(backup, mode)
                        val oldAssets = if (mode == RestoreMode.REPLACE) assets.files() else emptyList()
                        val rollback = if (mode == RestoreMode.REPLACE) createRollback() else null
                        var installedAssets: List<File> = emptyList()
                        try {
                            installedAssets = installAssets(backup, mappings.assets)
                            insertRecords(backup.recordsFile, mappings, mode)
                        } catch (failure: Exception) {
                            installedAssets.forEach(File::delete)
                            rollback?.delete()
                            if (failure is CancellationException) throw failure
                            if (failure is BackupFailure) throw failure
                            throw BackupFailure.RestoreFailed(failure)
                        }
                        if (mode == RestoreMode.REPLACE) {
                            oldAssets.forEach(File::delete)
                        }
                        rollback?.delete()
                        RestoreSummary(
                            notebooks = backup.manifest.notebookCount,
                            pages = backup.manifest.pageCount,
                            assets = backup.manifest.assetCount,
                            remappedIds = mappings.remappedCount,
                        )
                            .also { completed = it }
                    }
                } catch (failure: CancellationException) {
                    completed ?: throw failure
                }
            }
        }

    private suspend fun createMappings(backup: StagedBackup, mode: RestoreMode): IdMappings {
        val notebooks = database.notebookDao()
        val pageContent = database.pageDao()
        val keepExisting = mode == RestoreMode.MERGE
        val existingNotebooks =
            if (keepExisting) {
                repository.getAllNotebooks().mapTo(mutableSetOf(), NotebookEntity::id)
            } else {
                emptySet()
            }
        val existingPages = if (keepExisting) notebooks.getAllPageIds().toSet() else emptySet()
        val existingChapters = if (keepExisting) notebooks.getAllChapterIds().toSet() else emptySet()
        val existingPdfSources = if (keepExisting) notebooks.getAllPdfSourceIds().toSet() else emptySet()
        val existingStrokes = if (keepExisting) pageContent.getAllStrokeIds().toSet() else emptySet()
        val existingElements = if (keepExisting) pageContent.getAllElementIds().toSet() else emptySet()
        val existingBlocks = if (keepExisting) pageContent.getAllBlockIds().toSet() else emptySet()
        val notebookMap = mapIds(backup.index.notebookIds, existingNotebooks)
        val chapterMap = mapIds(backup.index.chapterIds, existingChapters)
        val pdfSourceMap = mapIds(backup.index.pdfSourceIds, existingPdfSources)
        val pageMap = mapIds(backup.index.pageIds, existingPages)
        val strokeMap = mapIds(backup.index.strokeIds, existingStrokes)
        val elementMap = mapIds(backup.index.elementIds, existingElements)
        val blockMap = mapIds(backup.index.blockIds, existingBlocks)
        val assetMap = mapAssetIds(backup.index.assetIds, assets.files().mapTo(mutableSetOf(), File::getName))
        return IdMappings(notebookMap, chapterMap, pdfSourceMap, pageMap, strokeMap, elementMap, blockMap, assetMap)
    }

    private fun mapIds(imported: Set<String>, existing: Set<String>): Map<String, String> {
        val used = existing.toMutableSet()
        return imported.sorted().associateWith { id ->
            if (used.add(id)) id else nextUnique(used) { idFactory() }
        }
    }

    private fun mapAssetIds(imported: Set<String>, existing: Set<String>): Map<String, String> {
        val used = existing.toMutableSet()
        return imported.sorted().associateWith { id ->
            if (used.add(id)) {
                id
            } else {
                val suffix = id.substringAfterLast('.', "").takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
                nextUnique(used) { idFactory() + suffix }
            }
        }
    }

    private fun nextUnique(used: MutableSet<String>, candidate: () -> String): String {
        repeat(MAX_ID_ATTEMPTS) {
            val value = candidate()
            if (value.isNotBlank() && value.length <= MAX_ID_LENGTH && used.add(value)) return value
        }
        throw BackupFailure.RestoreFailed(IllegalStateException("Unique restore ID unavailable"))
    }

    private suspend fun createRollback(): File {
        if ((!stagingRoot.isDirectory && !stagingRoot.mkdirs()) || !stagingRoot.canWrite()) {
            throw BackupFailure.RestoreFailed(IOException("Rollback storage unavailable"))
        }
        val file = File(stagingRoot, "rollback-${idFactory()}.seliasheets")
        try {
            file.outputStream().buffered().use { output ->
                BackupExporter(repository, assets, appVersion).export(BackupScope.Library, output)
            }
            return file
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    private fun installAssets(backup: StagedBackup, mapping: Map<String, String>): List<File> {
        assets.prepare()
        val installed = mutableListOf<File>()
        try {
            backup.assetFiles.forEach { (sourceId, source) ->
                val destination = assets.file(mapping.getValue(sourceId))
                if (destination.exists()) throw BackupFailure.RestoreFailed(IOException("Asset collision"))
                val temporary = assets.file(".restore-${idFactory()}.tmp")
                try {
                    if (temporary.exists()) throw BackupFailure.RestoreFailed(IOException("Temporary asset collision"))
                    source.inputStream().buffered().use { input ->
                        temporary.outputStream().buffered().use { output ->
                            input.copyTo(output, COPY_BUFFER_SIZE)
                        }
                    }
                    if (!temporary.renameTo(destination)) {
                        throw BackupFailure.RestoreFailed(IOException("Asset move failed"))
                    }
                    installed += destination
                } finally {
                    temporary.delete()
                }
            }
            return installed
        } catch (failure: Exception) {
            installed.forEach(File::delete)
            throw failure
        }
    }

    private suspend fun insertRecords(
        recordsFile: File,
        mappings: IdMappings,
        mode: RestoreMode,
    ) {
        val notebooks = database.notebookDao()
        val pageContent = database.pageDao()
        database.withTransaction {
            if (mode == RestoreMode.REPLACE) notebooks.clearNotebooks()
            forEachRecord<BackupNotebook>(recordsFile) { record ->
                notebooks.insertNotebook(record.toEntity(mappings))
            }
            forEachRecord<BackupChapter>(recordsFile) { record ->
                notebooks.insertChapter(record.toEntity(mappings))
            }
            forEachRecord<BackupPdfSource>(recordsFile) { record ->
                notebooks.insertPdfSource(record.toEntity(mappings))
            }
            forEachRecord<BackupPage>(recordsFile) { record ->
                notebooks.insertPage(record.toEntity(mappings))
            }
            forEachRecord<BackupStroke>(recordsFile) { record ->
                pageContent.insertStroke(record.toEntity(mappings))
            }
            forEachRecord<BackupElement>(recordsFile) { record ->
                pageContent.insertElement(record.toEntity(mappings))
            }
            forEachRecord<BackupBlock>(recordsFile) { record ->
                pageContent.insertBlock(record.toEntity(mappings))
            }
        }
    }

    private suspend inline fun <reified T : BackupRecord> forEachRecord(
        file: File,
        crossinline consume: suspend (T) -> Unit,
    ) {
        file.reader(Charsets.UTF_8).use { reader ->
            for (record in BackupJson.records(reader)) {
                if (record is T) consume(record)
            }
        }
    }

    private fun BackupNotebook.toEntity(mappings: IdMappings) =
        NotebookEntity(
            id = mappings.notebooks.getValue(id),
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

    private fun BackupPage.toEntity(mappings: IdMappings) =
        PageEntity(
            id = mappings.pages.getValue(id),
            notebookId = mappings.notebooks.getValue(notebookId),
            pageIndex = pageIndex,
            paper = paper,
            widthPoints = widthPoints,
            heightPoints = heightPoints,
            chapterId = chapterId?.let(mappings.chapters::getValue),
            title = title,
            pageMode = pageMode,
            bookmarked = bookmarked,
            createdAt = createdAt,
            updatedAt = updatedAt,
            pdfSourceId = pdfSourceId?.let(mappings.pdfSources::getValue),
            pdfPageIndex = pdfPageIndex,
        )

    private fun BackupStroke.toEntity(mappings: IdMappings) =
        StrokeEntity(
            id = mappings.strokes.getValue(id),
            pageId = mappings.pages.getValue(pageId),
            zIndex = zIndex,
            brushKind = brushKind,
            colorArgb = colorArgb,
            size = size,
            epsilon = epsilon,
            inputs = inputs,
        )

    private fun BackupChapter.toEntity(mappings: IdMappings) =
        ChapterEntity(
            id = mappings.chapters.getValue(id),
            notebookId = mappings.notebooks.getValue(notebookId),
            title = title,
            colorArgb = colorArgb,
            orderIndex = orderIndex,
        )

    private fun BackupPdfSource.toEntity(mappings: IdMappings) =
        PdfSourceEntity(
            id = mappings.pdfSources.getValue(id),
            notebookId = mappings.notebooks.getValue(notebookId),
            assetId = mappings.assets.getValue(assetId),
            displayName = displayName,
            pageCount = pageCount,
            byteSize = byteSize,
            sha256 = sha256,
            createdAt = createdAt,
        )

    private fun BackupElement.toEntity(mappings: IdMappings) =
        ElementEntity(
            id = mappings.elements.getValue(id),
            pageId = mappings.pages.getValue(pageId),
            zIndex = zIndex,
            kind = kind,
            x = x,
            y = y,
            width = width,
            height = height,
            rotation = rotation,
            text = text,
            assetId = assetId?.let(mappings.assets::getValue),
            shapeKind = shapeKind,
            expression = expression,
            resultText = resultText,
            ocrRegions = ocrRegions,
        )

    private fun BackupBlock.toEntity(mappings: IdMappings) =
        BlockEntity(
            id = mappings.blocks.getValue(id),
            pageId = mappings.pages.getValue(pageId),
            orderIndex = orderIndex,
            kind = kind,
            text = text,
            checked = checked,
            indent = indent,
            alignment = alignment,
            payloadId = payloadId,
        )

    private data class IdMappings(
        val notebooks: Map<String, String>,
        val chapters: Map<String, String>,
        val pdfSources: Map<String, String>,
        val pages: Map<String, String>,
        val strokes: Map<String, String>,
        val elements: Map<String, String>,
        val blocks: Map<String, String>,
        val assets: Map<String, String>,
    ) {
        val remappedCount: Int
            get() =
                listOf(notebooks, chapters, pdfSources, pages, strokes, elements, blocks, assets)
                    .sumOf { mapping -> mapping.count { (source, target) -> source != target } }
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_ID_ATTEMPTS = 10_000
        const val MAX_ID_LENGTH = 1024
    }
}
