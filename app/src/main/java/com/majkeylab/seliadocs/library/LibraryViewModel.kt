package com.majkeylab.seliadocs.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.NotebookEntity
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class LibraryUiState(
    val query: String = "",
    val trash: Boolean = false,
    val notebooks: List<NotebookEntity> = emptyList(),
    val failed: Boolean = false,
)

internal class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
    private val assets = AssetStore(File(application.filesDir, "assets"))
    private val query = MutableStateFlow("")
    private val trash = MutableStateFlow(false)
    private val failed = MutableStateFlow(false)
    private val activeNotebooks = repository.observeNotebooks()
    private val trashedNotebooks = repository.observeNotebooks(trash = true)

    init {
        mutate {
            val referenced = repository.getReferencedAssetIds()
            deleteUnusedAssets(assets.files().map(File::getName).filterNot(referenced::contains))
        }
    }

    val state =
        combine(query, trash, activeNotebooks, trashedNotebooks, failed) {
                search,
                showTrash,
                active,
                trashed,
                didFail,
            ->
                val visible = if (showTrash) trashed else active
                LibraryUiState(
                    query = search,
                    trash = showTrash,
                    notebooks =
                        visible.filter { notebook ->
                            notebook.title.contains(search.trim(), ignoreCase = true)
                        },
                    failed = didFail,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setTrash(value: Boolean) {
        trash.value = value
    }

    fun createNotebook(request: CreateNotebookRequest) = mutate {
        repository.createNotebook(request)
    }

    fun setFavorite(id: String, favorite: Boolean) = mutate {
        repository.setFavorite(id, favorite)
    }

    fun setTrashed(id: String, trashed: Boolean) = mutate {
        repository.setTrashed(id, trashed)
    }

    fun renameNotebook(id: String, title: String) = mutate {
        repository.renameNotebook(id, title)
    }

    fun deleteNotebook(id: String) = mutate {
        val content = repository.loadNotebook(id)
        val assetIds =
            (content.elements.mapNotNull { it.assetId } + content.pdfSources.map { it.assetId }).distinct()
        repository.deleteNotebook(id)
        deleteUnusedAssets(assetIds)
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            failed.value = runCatching { block() }.isFailure
        }
    }

    private suspend fun deleteUnusedAssets(assetIds: List<String>) {
        assetIds.forEach { assetId ->
            if (repository.getAssetReferenceCount(assetId) == 0) {
                val file = assets.file(assetId)
                check(!file.exists() || file.delete()) { "Asset could not be deleted" }
            }
        }
    }
}

internal fun normalizeTitle(raw: String, fallback: String): String = raw.trim().ifEmpty { fallback }
