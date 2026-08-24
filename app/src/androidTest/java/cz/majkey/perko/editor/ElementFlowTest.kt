package cz.majkey.perko.editor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.perko.data.CoverColor
import cz.majkey.perko.data.CoverPattern
import cz.majkey.perko.data.CreateNotebookRequest
import cz.majkey.perko.data.PageOrientation
import cz.majkey.perko.data.PaperTemplate
import cz.majkey.perko.data.PerkoDatabase
import cz.majkey.perko.data.PerkoRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElementFlowTest {
    @Test
    fun textElementSupportsUndoAndRedo() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = PerkoRepository(PerkoDatabase.get(application))
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
            val page = withTimeout(5_000) { viewModel.state.first { it.selectedPage != null }.selectedPage!! }

            viewModel.addText(page.id, "Force = mass × acceleration")
            val added = withTimeout(5_000) { viewModel.state.first { it.elements.size == 1 } }
            assertEquals("Force = mass × acceleration", added.elements.single().text)

            viewModel.undo()
            withTimeout(5_000) { viewModel.state.first { it.elements.isEmpty() && it.canRedo } }
            viewModel.redo()
            val restored = withTimeout(5_000) { viewModel.state.first { it.elements.size == 1 } }
            assertEquals("Force = mass × acceleration", restored.elements.single().text)

            viewModel.addMath(page.id, "(2+3)*4=")
            val math = withTimeout(5_000) { viewModel.state.first { it.elements.size == 2 } }
            assertEquals("(2+3)*4 = 20", math.elements.single { it.resultText != null }.resultText)
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }
}
