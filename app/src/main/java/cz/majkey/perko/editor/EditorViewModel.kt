package cz.majkey.perko.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.majkey.perko.data.NotebookEntity
import cz.majkey.perko.data.PageEntity
import cz.majkey.perko.data.PerkoDatabase
import cz.majkey.perko.data.PerkoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class EditorUiState(
    val notebook: NotebookEntity? = null,
    val pages: List<PageEntity> = emptyList(),
    val selectedPageId: String? = null,
    val failed: Boolean = false,
) {
    val selectedPage: PageEntity?
        get() = pages.firstOrNull { it.id == selectedPageId } ?: pages.firstOrNull()
}

internal class EditorViewModel(application: Application, private val notebookId: String) :
    AndroidViewModel(application) {
    private val repository = PerkoRepository(PerkoDatabase.get(application))
    private val selectedPageId = MutableStateFlow<String?>(null)
    private val failed = MutableStateFlow(false)

    val state =
        combine(
                repository.observeNotebook(notebookId),
                repository.observePages(notebookId),
                selectedPageId,
                failed,
            ) { notebook, pages, selected, didFail ->
                val validSelection = selected?.takeIf { id -> pages.any { it.id == id } }
                EditorUiState(
                    notebook = notebook,
                    pages = pages,
                    selectedPageId = validSelection ?: pages.firstOrNull()?.id,
                    failed = didFail,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    fun selectPage(id: String) {
        selectedPageId.value = id
    }

    fun addPage() = mutate {
        selectedPageId.value = repository.addPage(notebookId)
    }

    fun duplicatePage(id: String) = mutate {
        selectedPageId.value = repository.duplicatePage(id)
    }

    fun deletePage(id: String) = mutate {
        repository.deletePage(id)
    }

    fun movePage(fromIndex: Int, toIndex: Int) = mutate {
        repository.movePage(notebookId, fromIndex, toIndex)
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            failed.value = runCatching { block() }.isFailure
        }
    }
}
