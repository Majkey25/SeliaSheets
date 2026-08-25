package com.majkeylab.seliadocs.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.majkeylab.seliadocs.BuildConfig
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val BACKUP_MIME_TYPE = "application/zip"

internal fun backupFileName(date: LocalDate = LocalDate.now()): String =
    "SeliaDocs-backup-$date.seliadocs"

internal data class BackupUiState(
    val notebooks: Int = 0,
    val pages: Int = 0,
    val running: Boolean = false,
    val status: String? = null,
    val failed: Boolean = false,
)

internal class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SeliaDocsDatabase.get(application)
    private val repository = SeliaDocsRepository(database)
    private val assets = AssetStore(File(application.filesDir, "assets"))
    private val stagingRoot = File(application.filesDir, "restore-staging")
    private val exporter = BackupExporter(repository, assets, BuildConfig.VERSION_NAME)
    private val importer =
        BackupImporter(
            database = database,
            repository = repository,
            assets = assets,
            validator = BackupValidator(stagingRoot),
            stagingRoot = stagingRoot,
            appVersion = BuildConfig.VERSION_NAME,
        )
    private val resolver = application.contentResolver
    private val mutableState = MutableStateFlow(BackupUiState())
    val state = mutableState.asStateFlow()

    init {
        refreshCounts()
    }

    fun exportLibrary(uri: Uri) = runOperation("Creating backup…") {
        val summary =
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    exporter.export(BackupScope.Library, output)
                } ?: error("Backup destination unavailable")
            }
        "Backup created · ${summary.notebooks} notebooks · ${summary.pages} pages"
    }

    fun restore(uri: Uri, mode: RestoreMode) = runOperation("Checking backup…") {
        val summary =
            withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { input -> importer.restore(input, mode) }
                    ?: error("Backup file unavailable")
            }
        refreshCountsNow()
        "Backup restored · ${summary.notebooks} notebooks · ${summary.pages} pages"
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

    private fun refreshCounts() {
        viewModelScope.launch { refreshCountsNow() }
    }

    private suspend fun refreshCountsNow() {
        val counts =
            withContext(Dispatchers.IO) {
                val notebooks = repository.getAllNotebooks()
                notebooks.size to notebooks.sumOf { repository.getPages(it.id).size }
            }
        mutableState.update { it.copy(notebooks = counts.first, pages = counts.second) }
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
