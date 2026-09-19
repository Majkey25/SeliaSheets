package com.majkeylab.seliadocs.editor

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.backup.validTestStrokePayload
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.ElementDraft
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.data.StrokeEntity
import com.majkeylab.seliadocs.recognition.ImageOcrRegion
import com.majkeylab.seliadocs.recognition.ImageOcrResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorWorkspaceHistoryTest {
    @Test
    fun otherPaneInkSurvivesStaleUndo() = runBlocking {
        assertExternalEditPreserved { _, other, pageId ->
            val ink = validTestStrokePayload()
            val stroke = StrokeEntity("source", pageId, 0, ink.brushKind, ink.colorArgb, ink.size, ink.epsilon, ink.inputs).toInkStroke()
            complete { done -> other.addStroke(pageId, stroke, shapeAssist = false, onComplete = done) }
        }
    }

    @Test
    fun otherPaneElementsSurviveStaleUndo() = runBlocking {
        assertExternalEditPreserved { _, other, pageId ->
            complete { done -> other.addText(pageId, "Other pane", onComplete = done) }
        }
    }

    @Test
    fun otherPanePageTextSurvivesStaleUndo() = runBlocking {
        assertExternalEditPreserved { _, other, pageId ->
            onMain { other.updatePageText(pageId, "Other pane page text") }
            other.await { it.selectedBlocks.singleOrNull()?.text == "Other pane page text" }
        }
    }

    @Test
    fun linkedExcerptSurvivesStaleUndo() = runBlocking {
        assertExternalEditPreserved { repository, _, pageId ->
            LibraryMutationGate.withLock {
                repository.addLinkedExcerpt(pageId, ElementDraft(ElementKind.TEXT, 30f, 40f, 200f, 80f,
                    text = "Linked excerpt", sourcePageId = pageId, sourceRect = "0.1,0.2,0.8,0.3"))
            }
        }
    }

    @Test
    fun identicalReloadedStrokeBytesRetainValidUndo() = runBlocking {
        withEditors { repository, first, _, pageId ->
            val payload = validTestStrokePayload()
            val strokeId = LibraryMutationGate.withLock { repository.addStroke(pageId, payload) }
            complete { done -> first.addText(pageId, "Local text", onComplete = done) }
            first.await { it.canUndo && it.elements.size == 1 }
            LibraryMutationGate.withLock {
                val current = repository.getStrokes(pageId)
                repository.replaceStrokes(pageId, current.map { it.copy(inputs = it.inputs.copyOf()) })
            }
            onMain(first::undo)
            first.await { it.canRedo && it.elements.isEmpty() }
            val remaining = repository.getStrokes(pageId).single()
            assertEquals(strokeId, remaining.id)
            assertArrayEquals(payload.inputs, remaining.inputs)
        }
    }

    @Test
    fun ownOcrAmendKeepsUndoAndRecognizedMetadata() = runBlocking {
        withEditors { repository, first, _, pageId ->
            val image = LibraryMutationGate.withLock {
                repository.addElementEntity(pageId, ElementDraft(ElementKind.IMAGE, 20f, 30f, 200f, 100f, assetId = "ocr-fixture.png"))
            }
            complete { done -> first.addText(pageId, "Undo this text", onComplete = done) }
            first.await { it.canUndo && it.elements.size == 2 }
            onMain { first.selectElement(image.id); first.recognizeSelectedImage() }
            first.await { it.elements.any { element -> element.id == image.id && element.ocrRegions != null } && it.canUndo }
            onMain(first::undo)
            val restored = first.await { it.canRedo && it.elements.size == 1 }
            assertEquals(image.id, restored.elements.single().id)
            assertEquals("Recognized text", restored.elements.single().text)
            assertTrue(restored.elements.single().ocrRegions != null)
        }
    }

    @Test
    fun invalidatingOnePageDoesNotDiscardAnotherPagesHistory() = runBlocking {
        withEditors { repository, first, _, pageId ->
            complete { done -> first.addText(pageId, "Page one", onComplete = done) }
            val secondPage = LibraryMutationGate.withLock { repository.addPage(repository.getPage(pageId)!!.notebookId) }
            first.await { it.pages.size == 2 }
            onMain { first.selectPage(secondPage) }
            first.await { it.selectedPage?.id == secondPage }
            complete { done -> first.addText(secondPage, "Page two", onComplete = done) }
            LibraryMutationGate.withLock { repository.updatePageText(pageId, "External change") }
            onMain { first.selectPage(pageId) }
            first.await { it.selectedPage?.id == pageId }
            onMain(first::undo)
            first.await { it.recognitionMessage == refreshedMessage() && !it.canUndo }
            onMain { first.selectPage(secondPage) }
            first.await { it.selectedPage?.id == secondPage && it.canUndo }
            onMain(first::undo)
            first.await { it.elements.none { element -> element.pageId == secondPage } && it.canRedo }
            assertEquals("External change", repository.getBlocks(pageId).single().text)
            assertEquals("Page one", repository.getElements(pageId).single().text)
        }
    }

    private suspend fun assertExternalEditPreserved(edit: suspend (SeliaDocsRepository, EditorViewModel, String) -> Unit) {
        withEditors { repository, first, second, pageId ->
            complete { done -> first.addText(pageId, "Local text", onComplete = done) }
            first.await { it.canUndo }
            edit(repository, second, pageId)
            val expected = LibraryMutationGate.withLock {
                Triple(repository.getStrokes(pageId), repository.getElements(pageId), repository.getBlocks(pageId))
            }
            onMain(first::undo)
            val refreshed = first.await { it.recognitionMessage == refreshedMessage() && !it.canUndo && !it.canRedo }
            assertFalse(refreshed.failed)
            assertEquals(expected.second, repository.getElements(pageId))
            assertEquals(expected.third, repository.getBlocks(pageId))
            val strokes = repository.getStrokes(pageId)
            assertEquals(expected.first.size, strokes.size)
            expected.first.zip(strokes).forEach { (before, after) ->
                assertEquals(before.copy(inputs = after.inputs), after)
                assertArrayEquals(before.inputs, after.inputs)
            }
        }
    }

    private suspend fun withEditors(block: suspend (SeliaDocsRepository, EditorViewModel, EditorViewModel, String) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(app))
        val book = repository.createNotebook(CreateNotebookRequest("Workspace history ${System.nanoTime()}", CoverColor.SAGE,
            CoverPattern.SOLID, PaperTemplate.BLANK, PageOrientation.PORTRAIT, false))
        val firstOwner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val secondOwner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        lateinit var first: EditorViewModel
        lateinit var second: EditorViewModel
        try {
            onMain {
                first = ViewModelProvider(firstOwner, viewModelFactory {
                    initializer {
                        EditorViewModel(app, book, imageOcrRecognizer = {
                            ImageOcrResult("Recognized text", listOf(ImageOcrRegion("Recognized text", 0.1f, 0.1f, 0.8f, 0.4f)))
                        })
                    }
                })[EditorViewModel::class.java]
                second = ViewModelProvider(secondOwner, viewModelFactory {
                    initializer { EditorViewModel(app, book) }
                })[EditorViewModel::class.java]
            }
            val pageId = first.await { it.selectedPage != null }.selectedPage!!.id
            second.await { it.selectedPage?.id == pageId }
            block(repository, first, second, pageId)
        } finally {
            onMain { firstOwner.viewModelStore.clear(); secondOwner.viewModelStore.clear() }
            LibraryMutationGate.withLock { repository.deleteNotebook(book) }
        }
    }

    private suspend fun complete(action: ((Boolean) -> Unit) -> Unit) {
        val result = CompletableDeferred<Boolean>()
        onMain { action { result.complete(it) } }
        assertTrue(withTimeout(10_000) { result.await() })
    }

    private suspend fun EditorViewModel.await(predicate: (EditorUiState) -> Boolean): EditorUiState =
        withTimeout(10_000) { state.first(predicate) }

    private fun refreshedMessage(): String = ApplicationProvider.getApplicationContext<Application>().getString(R.string.editor_history_refreshed)

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
