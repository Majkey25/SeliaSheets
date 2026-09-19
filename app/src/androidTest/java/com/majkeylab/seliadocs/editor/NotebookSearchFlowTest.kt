package com.majkeylab.seliadocs.editor

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.PdfPageSpec
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.pdf.PdfTextBounds
import com.majkeylab.seliadocs.pdf.PdfTextSearchMatch
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotebookSearchFlowTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
    private val bounds = listOf(PdfTextBounds(0.1f, 0.2f, 0.4f, 0.25f))

    private suspend fun fixture(
        searcher: suspend (File, Int, String, Boolean) -> List<PdfTextSearchMatch>,
        verify: suspend (EditorViewModel, String, List<String>) -> Unit,
    ) {
        val notebook = repository.createNotebook(CreateNotebookRequest("Search QA ${UUID.randomUUID()}",
            CoverColor.PERIWINKLE, CoverPattern.SOLID, PaperTemplate.BLANK, PageOrientation.PORTRAIT, false))
        val store = AssetStore(File(application.filesDir, "assets"))
        store.prepare()
        val file = store.file("search-qa-${UUID.randomUUID()}.pdf")
        // The injected backend tests orchestration; native PDF parsing has separate device tests.
        file.writeText("search backend fixture")
        var editor: EditorViewModel? = null
        try {
            val imported = repository.importPdf(notebook, file.name, "Lecture.pdf", file.length(), "0".repeat(64),
                listOf(PdfPageSpec(600, 800), PdfPageSpec(600, 800)))
            val model = withContext(Dispatchers.Main) { EditorViewModel(application, notebook, pdfPageSearcher = searcher) }
            editor = model
            withTimeout(10_000) { model.state.first { it.pages.size == 3 } }
            verify(model, notebook, imported.pageIds)
        } finally {
            withContext(Dispatchers.Main) { editor?.viewModelScope?.cancel() }
            repository.deleteNotebook(notebook)
            file.delete()
        }
    }

    @Test fun pdfMatchesOpenExactPageAndRetainGeometry() = runBlocking {
        val visited = mutableListOf<Int>()
        fixture({ _, index, query, ocr ->
            assertEquals("chemistry", query)
            assertFalse(ocr)
            visited += index
            listOf(PdfTextSearchMatch(bounds, false))
        }) { editor, _, pages ->
            withContext(Dispatchers.Main) { editor.searchPageText("chemistry", false) }
            val result = withTimeout(10_000) { editor.state.first { it.searchQuery == "chemistry" && !it.searching } }
            assertEquals(listOf(0, 1), visited)
            assertEquals(pages, result.searchResults.map { it.page.pageId })
            withContext(Dispatchers.Main) { editor.openSearchResult(result.searchResults.last()) }
            val opened = withTimeout(10_000) { editor.state.first { it.selectedPage?.id == pages.last() && it.pdfSearchHighlight != null } }
            assertEquals(bounds, opened.pdfSearchHighlight?.selection?.bounds)
            assertTrue(opened.searchResults.isEmpty())
            withContext(Dispatchers.Main) { editor.searchPageText("chemistry", false) }
            val repeated = withTimeout(10_000) { editor.state.first { it.searchQuery == "chemistry" && !it.searching } }
            assertEquals(null, repeated.pdfSearchHighlight)
        }
    }

    @Test fun failedPdfSearchKeepsOrdinaryTextResultsAndReportsIncompleteSearch() = runBlocking {
        fixture({ _, _, _, _ -> throw IllegalStateException("Unreadable PDF") }) { editor, notebook, _ ->
            val page = repository.getPages(notebook).first()
            repository.updatePageText(page.id, "biology lecture")
            withContext(Dispatchers.Main) { editor.searchPageText("biology") }
            val result = withTimeout(10_000) { editor.state.first { it.searchQuery == "biology" && !it.searching } }
            assertEquals(page.id, result.searchResults.single().page.pageId)
            assertNotNull(result.searchMessage)
            assertFalse(result.searchFailed)
        }
    }

    @Test fun canceledSlowPdfQueryCannotOverwriteNewerResults() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        try {
            fixture({ _, index, query, _ ->
                if (query == "old" && index == 0) withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    returned.complete(Unit)
                }
                listOf(PdfTextSearchMatch(bounds, false))
            }) { editor, _, _ ->
                withContext(Dispatchers.Main) { editor.searchPageText("old") }
                withTimeout(10_000) { entered.await() }
                withContext(Dispatchers.Main) { editor.searchPageText("new") }
                withTimeout(10_000) { editor.state.first { it.searchQuery == "new" && !it.searching } }
                release.complete(Unit)
                withTimeout(10_000) { returned.await() }
                withContext(Dispatchers.Main) {
                    assertEquals("new", editor.state.value.searchQuery)
                    assertTrue(editor.state.value.searchResults.all { it.page.text == "new" })
                    editor.clearSearch()
                }
                withTimeout(10_000) { editor.state.first { it.searchQuery.isEmpty() && !it.searching } }
            }
        } finally { release.complete(Unit) }
    }

    @Test fun combinedSearchStopsAtOneHundredResults() = runBlocking {
        var calls = 0
        fixture({ _, _, _, _ ->
            calls++
            List(100) { PdfTextSearchMatch(bounds, false) }
        }) { editor, _, _ ->
            withContext(Dispatchers.Main) { editor.searchPageText("term") }
            val result = withTimeout(10_000) { editor.state.first { it.searchQuery == "term" && !it.searching } }
            assertEquals(100, result.searchResults.size)
            assertEquals(1, calls)
            assertNotNull(result.searchMessage)
        }
    }
}
