package com.majkeylab.seliadocs.backup

import java.io.IOException

internal const val BACKUP_FORMAT_VERSION = 1

internal data class BackupManifest(
    val formatVersion: Int,
    val appVersion: String,
    val exportedAt: Long,
    val notebookCount: Int = 0,
    val pageCount: Int = 0,
    val assetCount: Int = 0,
    val featureFlags: Set<String> = emptySet(),
)

internal sealed interface BackupRecord

internal sealed interface BackupScope {
    data class Notebook(val id: String) : BackupScope

    data class Selected(val ids: Set<String>) : BackupScope

    data object Library : BackupScope
}

internal data class BackupSummary(
    val notebooks: Int,
    val pages: Int,
    val assets: Int,
    val bytesWritten: Long,
)

internal data class BackupNotebook(
    val id: String,
    val title: String,
    val coverColor: String,
    val coverPattern: String,
    val defaultPaper: String,
    val orientation: String,
    val fingerDrawing: Boolean,
    val favorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val trashedAt: Long?,
) : BackupRecord

internal data class BackupPage(
    val id: String,
    val notebookId: String,
    val pageIndex: Int,
    val paper: String,
    val widthPoints: Int,
    val heightPoints: Int,
) : BackupRecord

internal data class BackupStroke(
    val id: String,
    val pageId: String,
    val zIndex: Int,
    val brushKind: String,
    val colorArgb: Int,
    val size: Float,
    val epsilon: Float,
    val inputs: ByteArray,
) : BackupRecord

internal data class BackupElement(
    val id: String,
    val pageId: String,
    val zIndex: Int,
    val kind: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val text: String?,
    val assetId: String?,
    val shapeKind: String?,
    val expression: String?,
    val resultText: String?,
) : BackupRecord

internal sealed class BackupFailure(message: String, cause: Throwable? = null) :
    IOException(message, cause) {
    class UnsupportedVersion(val version: Int) :
        BackupFailure("Unsupported backup format version: $version")

    class UnknownRecordKind(val kind: String) :
        BackupFailure("Unknown backup record kind: $kind")

    class InvalidNumber(val field: String) :
        BackupFailure("Invalid numeric value for $field")

    class LimitExceeded(val field: String) :
        BackupFailure("Backup limit exceeded for $field")

    class MissingField(val field: String) :
        BackupFailure("Missing required backup field: $field")

    class InvalidPath(val entry: String) :
        BackupFailure("Invalid archive path: $entry")

    class DuplicateEntry(val entry: String) :
        BackupFailure("Duplicate archive entry: $entry")

    class ChecksumMismatch(val entry: String) :
        BackupFailure("Checksum mismatch: $entry")

    class InvalidRelationship(val field: String) :
        BackupFailure("Invalid backup relationship: $field")

    class MissingAsset(val assetId: String) :
        BackupFailure("Missing backup asset: $assetId")

    class Malformed(cause: Throwable? = null) : BackupFailure("Malformed backup data", cause)
}

internal sealed class BackupExportFailure(message: String) : IOException(message) {
    class MissingNotebook(val notebookId: String) :
        BackupExportFailure("Notebook is unavailable: $notebookId")

    class MissingAsset(val assetId: String) :
        BackupExportFailure("Asset is unavailable: $assetId")

    class SourceChanged : BackupExportFailure("Notebook data changed during backup")
}
