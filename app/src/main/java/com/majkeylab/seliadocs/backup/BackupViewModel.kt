package com.majkeylab.seliadocs.backup

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.majkeylab.seliadocs.BuildConfig
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.pdf.PdfSandboxClient
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val BACKUP_MIME_TYPE = "application/zip"

internal fun backupFileName(date: LocalDate = LocalDate.now()): String =
    "SeliaSheets-backup-$date.seliasheets"

internal data class BackupUiState(
    val notebooks: Int = 0,
    val pages: Int = 0,
    val running: Boolean = false,
    val status: String? = null,
    val failed: Boolean = false,
    val replacementGeneration: Long = 0,
)

internal class BackupViewModel @JvmOverloads constructor(
    application: Application,
    restoreImporter: BackupImporter? = null,
    private val replacementPreferences: SharedPreferences =
        application.getSharedPreferences(REPLACEMENT_PREFERENCES, Context.MODE_PRIVATE),
) : AndroidViewModel(application) {
    private val database = SeliaDocsDatabase.get(application)
    private val repository = SeliaDocsRepository(database)
    private val assets = AssetStore(File(application.filesDir, "assets"))
    private val stagingRoot = File(application.filesDir, "restore-staging")
    private val pdfSandbox = PdfSandboxClient(application)
    private val exporter = BackupExporter(repository, assets, BuildConfig.VERSION_NAME)
    private val importer =
        restoreImporter ?: BackupImporter(
            database = database,
            repository = repository,
            assets = assets,
            validator = BackupValidator(stagingRoot, pdfSandbox::inspect),
            stagingRoot = stagingRoot,
            appVersion = BuildConfig.VERSION_NAME,
        )
    private val resolver = application.contentResolver
    private val mutableState =
        MutableStateFlow(
            BackupUiState(
                replacementGeneration = replacementPreferences.getLong(REPLACEMENT_PRODUCED, 0),
            ),
        )
    @Volatile
    private var claimedReplacementGeneration: Long? = null
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                cleanupAbandonedRestores(stagingRoot)
                refreshCountsNow()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mutableState.update { it.copy(status = failureMessage(failure), failed = true) }
            }
        }
    }

    fun exportLibrary(uri: Uri) = runOperation("Creating backup…") {
        val application = getApplication<Application>()
        val summary =
            withContext(Dispatchers.IO) {
                writeBackupToDestination(
                    cacheDir = application.cacheDir,
                    export = { output -> exportUserLibrary(exporter) { output } },
                    openDestination = { resolver.openOutputStream(uri, "rwt") },
                    deleteDestination = {
                        check(DocumentsContract.deleteDocument(resolver, uri)) {
                            "Incomplete backup destination could not be deleted"
                        }
                    },
                )
            }
        "Backup created · ${summary.notebooks} notebooks · ${summary.pages} pages"
    }

    fun restore(uri: Uri, mode: RestoreMode) = runOperation("Checking backup…") {
        var completedSummary: RestoreSummary? = null
        val summary =
            try {
                withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { input -> importer.restore(input, mode) }
                        ?.also { completedSummary = it }
                        ?: error("Backup file unavailable")
                }
            } catch (failure: CancellationException) {
                completedSummary ?: throw failure
            }
        withContext(NonCancellable) {
            if (mode == RestoreMode.REPLACE) {
                publishReplacement()
            }
            refreshCountsNow()
            "Backup restored · ${summary.notebooks} notebooks · ${summary.pages} pages"
        }
    }

    fun hasPendingReplacement(): Boolean =
        mutableState.value.replacementGeneration >
            replacementPreferences.getLong(REPLACEMENT_ACKNOWLEDGED, 0)

    @Synchronized
    fun claimPendingReplacement(): Long? {
        val generation = mutableState.value.replacementGeneration
        if (!hasPendingReplacement() || claimedReplacementGeneration == generation) return null
        claimedReplacementGeneration = generation
        return generation
    }

    suspend fun acknowledgeReplacement(generation: Long) {
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                check(claimedReplacementGeneration == generation) { "Replacement revision was not claimed" }
                check(
                    replacementPreferences.edit()
                        .putLong(REPLACEMENT_ACKNOWLEDGED, generation)
                        .commit(),
                ) { "Replacement acknowledgement could not be saved" }
            }
        } catch (failure: Throwable) {
            releaseReplacementClaim(generation)
            throw failure
        }
    }

    @Synchronized
    fun releaseReplacementClaim(generation: Long) {
        if (claimedReplacementGeneration == generation) claimedReplacementGeneration = null
    }

    private fun runOperation(progress: String, operation: suspend () -> String) {
        if (mutableState.value.running) return
        mutableState.update { it.copy(running = true, status = progress, failed = false) }
        viewModelScope.launch {
            runCatching { operation() }
                .onSuccess { message ->
                    mutableState.update { it.copy(running = false, status = message, failed = false) }
                }
                .onFailure { failure ->
                    mutableState.update {
                        it.copy(running = false, status = failureMessage(failure), failed = true)
                    }
                }
        }
    }

    private suspend fun refreshCountsNow() {
        val counts =
            withContext(Dispatchers.IO) {
                val notebooks = repository.getAllNotebooks()
                notebooks.size to notebooks.sumOf { repository.getPages(it.id).size }
            }
        mutableState.update { it.copy(notebooks = counts.first, pages = counts.second) }
    }

    private suspend fun publishReplacement() {
        val generation =
            withContext(Dispatchers.IO) {
                val next = Math.addExact(replacementPreferences.getLong(REPLACEMENT_PRODUCED, 0), 1)
                check(
                    replacementPreferences.edit()
                        .putLong(REPLACEMENT_PRODUCED, next)
                        .commit(),
                ) { "Replacement revision could not be saved" }
                next
            }
        mutableState.update { it.copy(replacementGeneration = generation) }
    }

    private fun failureMessage(failure: Throwable): String =
        when (failure) {
            is BackupFailure.InvalidPath -> "This backup contains an unsafe file path."
            is BackupFailure.ChecksumMismatch -> "This backup is damaged or was modified."
            is BackupFailure.UnsupportedVersion -> "This backup was created by an unsupported version."
            is BackupFailure.LimitExceeded -> "This backup is too large to restore safely."
            is BackupFailure.MissingAsset -> "This backup is missing an image."
            else -> "The backup operation could not be completed."
        }
}

internal suspend fun exportUserLibrary(
    exporter: BackupExporter,
    outputFactory: () -> OutputStream,
): BackupSummary =
    LibraryMutationGate.withLock { exporter.export(BackupScope.Library, outputFactory) }

internal suspend fun cleanupAbandonedRestores(stagingRoot: File) {
    LibraryMutationGate.withLock {
        withContext(Dispatchers.IO) {
            if (!stagingRoot.exists() || Files.isSymbolicLink(stagingRoot.toPath())) return@withContext
            val root = stagingRoot.canonicalFile
            val entries = root.listFiles() ?: throw IOException("Restore staging could not be read")
            val restoreName = Regex("restore-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
            val rollbackName = Regex("rollback-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.seliasheets")
            for (entry in entries) {
                val path = entry.toPath()
                if (Files.isSymbolicLink(path) || entry.canonicalFile.parentFile != root) continue
                val paths = when {
                    restoreName.matches(entry.name) && Files.isDirectory(path, NOFOLLOW_LINKS) ->
                        Files.walk(path).use { it.iterator().asSequence().toList() }
                    rollbackName.matches(entry.name) && Files.isRegularFile(path, NOFOLLOW_LINKS) -> listOf(path)
                    else -> continue
                }
                if (paths.any { Files.isSymbolicLink(it) || !it.toFile().canonicalFile.toPath().startsWith(root.toPath()) }) {
                    continue
                }
                paths.asReversed().forEach(Files::delete)
            }
        }
    }
}

internal suspend fun writeBackupToDestination(
    cacheDir: File,
    export: suspend (OutputStream) -> BackupSummary,
    openDestination: () -> OutputStream?,
    deleteDestination: () -> Unit,
): BackupSummary {
    var temporaryBackup: File? = null
    var destinationOpened = false
    try {
        temporaryBackup = File.createTempFile("seliasheets-", ".seliasheets", cacheDir)
        val summary = temporaryBackup.outputStream().use { export(it) }
        val destination = openDestination() ?: error("Backup destination unavailable")
        destinationOpened = true
        destination.use { output -> temporaryBackup.inputStream().use { it.copyTo(output) } }
        return summary
    } catch (failure: Throwable) {
        if (destinationOpened) {
            try {
                deleteDestination()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
        }
        throw failure
    } finally {
        temporaryBackup?.delete()
    }
}

private const val REPLACEMENT_PREFERENCES = "backup-replacement-events"
internal const val REPLACEMENT_PRODUCED = "produced"
private const val REPLACEMENT_ACKNOWLEDGED = "acknowledged"
