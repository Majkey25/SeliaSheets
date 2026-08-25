package com.majkeylab.seliadocs.editor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElementFlowTest {
    @Test
    fun textElementSupportsUndoAndRedo() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId =
            repository.createNotebook(
                CreateNotebookRequest(
                    title = "Element test ${System.nanoTime()}",
                    coverColor = CoverColor.PERIWINKLE,
                    coverPattern = CoverPattern.SOLID,
                    paper = PaperTemplate.RULED,
                    orientation = PageOrientation.PORTRAIT,
                    fingerDrawing = false,
                ),
            )
        try {
            lateinit var viewModel: EditorViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel = EditorViewModel(application, notebookId)
            }
            val page = viewModel.awaitState("page load") { it.selectedPage != null }.selectedPage!!

            onMain { viewModel.addText(page.id, "Force = mass × acceleration") }
            val added = viewModel.awaitState("text add") { it.elements.size == 1 && it.canUndo }
            assertEquals("Force = mass × acceleration", added.elements.single().text)

            onMain(viewModel::undo)
            viewModel.awaitState("text undo") { it.elements.isEmpty() && it.canRedo }
            onMain(viewModel::redo)
            val restored = viewModel.awaitState("text redo") { it.elements.size == 1 }
            assertEquals("Force = mass × acceleration", restored.elements.single().text)

            onMain { viewModel.addMath(page.id, "(2+3)*4=") }
            val math = viewModel.awaitState("math add") { it.elements.size == 2 }
            assertEquals("(2+3)*4 = 20", math.elements.single { it.resultText != null }.resultText)

            val textElement = math.elements.single { it.text != null }
            onMain { viewModel.selectElement(textElement.id) }
            onMain {
                viewModel.updateSelectedElement(
                    ElementTransform(40f, 50f, 180f, 90f, 25f),
                )
            }
            val moved =
                viewModel.awaitState("element transform") {
                    it.selectedElement?.x == 40f && it.canUndo
                }
            assertEquals(25f, moved.selectedElement?.rotation)

            onMain(viewModel::undo)
            val undone =
                viewModel.awaitState("element transform undo") {
                    it.elements.single { element -> element.id == textElement.id }.x == textElement.x
                }
            assertEquals(textElement.width, undone.elements.single { it.id == textElement.id }.width)

            onMain(viewModel::duplicateSelectedElement)
            val duplicated =
                viewModel.awaitState("element duplicate") {
                    it.elements.size == 3 && it.selectedElementId != textElement.id
                }
            assertTrue(duplicated.selectedElement != null)

            onMain { viewModel.selectElement(textElement.id) }
            val selected =
                viewModel.awaitState("select original element") {
                    it.selectedElementId == textElement.id
                }
            val originalZ = selected.selectedElement?.zIndex ?: error("Selection missing")
            onMain(viewModel::bringSelectedElementForward)
            viewModel.awaitState("element layer order") {
                (it.selectedElement?.zIndex ?: originalZ) > originalZ
            }

            onMain(viewModel::deleteSelectedElement)
            viewModel.awaitState("element delete") {
                it.elements.size == 2 && it.selectedElement == null
            }
            onMain(viewModel::undo)
            viewModel.awaitState("element delete undo") {
                it.elements.any { element -> element.id == textElement.id }
            }
            Unit
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    private suspend fun EditorViewModel.awaitState(
        label: String,
        predicate: (EditorUiState) -> Boolean,
    ): EditorUiState =
        withTimeoutOrNull(TIMEOUT_MS) { state.first(predicate) }
            ?: throw AssertionError("Timed out waiting for $label")

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
