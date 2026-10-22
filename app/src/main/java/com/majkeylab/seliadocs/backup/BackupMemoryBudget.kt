package com.majkeylab.seliadocs.backup

/** Conservative working-set estimates, scaled to the device rather than a document count. */
internal class BackupMemoryBudget(private val heapBytes: Long) {
    var indexBytes = 0L
        private set
    private var metadataBytes = 0L
    private val pageBytes = mutableMapOf<String, Long>()
    var notebookBytes = 0L
        private set

    fun record(record: BackupRecord) {
        // IDs, relationship collections and restore remapping coexist during validation/import.
        val idCharacters = when (record) {
            is BackupNotebook -> record.id.length.toLong()
            is BackupPage -> record.id.length.toLong() + record.notebookId.length + record.chapterId.orEmpty().length + record.pdfSourceId.orEmpty().length
            is BackupChapter -> record.id.length.toLong() + record.notebookId.length
            is BackupPdfSource -> record.id.length.toLong() + record.notebookId.length + record.assetId.length
            is BackupStroke -> record.id.length.toLong() + record.pageId.length
            is BackupElement -> record.id.length.toLong() + record.pageId.length + record.assetId.orEmpty().length + record.sourcePageId.orEmpty().length
            is BackupBlock -> record.id.length.toLong() + record.pageId.length + record.payloadId.orEmpty().length
        }
        indexBytes = add(indexBytes, 1024 + idCharacters * 2, heapBytes / 4, "recordIndex")
        val metadata = when (record) {
            is BackupNotebook -> 1024L + record.title.toByteArray(Charsets.UTF_8).size * 2L
            is BackupPage -> 1024L + record.title.orEmpty().length * 2L
            is BackupChapter -> 1024L + record.title.length * 2L
            is BackupPdfSource -> 1024L + record.displayName.length * 2L
            else -> 0L
        }
        metadataBytes = add(metadataBytes, metadata, heapBytes / 16, "metadata")
        if (record is BackupNotebook) notebookBytes += metadata
        if (record is BackupElement) {
            val characters = record.text.orEmpty().length.toLong() + record.expression.orEmpty().length +
                record.resultText.orEmpty().length + record.ocrRegions.orEmpty().length + record.annotationRects.orEmpty().length
            // Compose nodes, text layout and entity copies remain live together.
            page(record.pageId, 4096 + characters * 8)
        }
        if (record is BackupBlock) page(record.pageId, 4096 + record.text.orEmpty().length * 8L)
    }

    fun stroke(pageId: String, encodedBytes: Int, points: Int) {
        // Encoded history, decoded inputs and native stroke meshes all contribute.
        page(pageId, 2048L + encodedBytes * 2L + points * 128L)
    }

    fun image(pageId: String, previewPixels: Long) {
        // Preview plus orientation/decode copy; repeated elements each decode a bitmap.
        page(pageId, previewPixels * 8)
    }

    fun checkLibrary(existingNotebookBytes: Long, importedNotebookBytes: Long) {
        add(existingNotebookBytes, importedNotebookBytes, heapBytes / 16, "libraryMetadata")
    }

    fun checkIndex(existingBytes: Long, importedBytes: Long) {
        add(existingBytes, importedBytes, heapBytes / 4, "recordIndex")
    }

    private fun page(id: String, bytes: Long) {
        pageBytes[id] = add(pageBytes[id] ?: 0, bytes, heapBytes / 3, "pageContent")
    }

    private fun add(current: Long, bytes: Long, limit: Long, field: String): Long {
        if (bytes < 0 || current > limit || bytes > limit - current) throw BackupFailure.LimitExceeded(field)
        return current + bytes
    }
}
