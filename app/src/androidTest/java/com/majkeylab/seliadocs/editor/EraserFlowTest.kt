package com.majkeylab.seliadocs.editor

import android.app.Application
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EraserFlowTest {
    @Test
    fun segmentAndWholeStrokeModesAreUndoable() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId =
            repository.createNotebook(
                CreateNotebookRequest(
                    "Eraser ${System.nanoTime()}",
                    CoverColor.PERIWINKLE,
                    CoverPattern.SOLID,
                    PaperTemplate.BLANK,
                    PageOrientation.PORTRAIT,
                    false,
                ),
            )
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val pageId = await(viewModel, "page") { it.selectedPage != null }.selectedPage!!.id
            onMain { viewModel.addStroke(pageId, testStroke()) }
            await(viewModel, "stroke") { it.strokes.size == 1 }

            onMain {
                viewModel.setEraserMode(EraserMode.SEGMENT)
                viewModel.eraseStrokes(pageId, listOf(CanvasPoint(50f, -20f), CanvasPoint(50f, 20f)))
            }
            await(viewModel, "segment erase") { it.strokes.size == 2 && it.canUndo }
            onMain(viewModel::undo)
            await(viewModel, "segment undo") { it.strokes.size == 1 }

            onMain {
                viewModel.setEraserMode(EraserMode.STROKE)
                viewModel.eraseStrokes(pageId, listOf(CanvasPoint(50f, -20f), CanvasPoint(50f, 20f)))
            }
            val erased = await(viewModel, "whole stroke erase") { it.strokes.isEmpty() }
            assertEquals(EraserMode.STROKE, erased.eraserMode)
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    private fun testStroke(): Stroke {
        val inputs = MutableStrokeInputBatch()
        repeat(5) { index ->
            inputs.add(InputToolType.STYLUS, index * 25f, 0f, index * 10L, 0.01f, 0.6f, 0.2f, 0.3f)
        }
        return Stroke(InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 4f), inputs)
    }

    private suspend fun await(
        viewModel: EditorViewModel,
        label: String,
        predicate: (EditorUiState) -> Boolean,
    ): EditorUiState =
        withTimeoutOrNull(30_000) { viewModel.state.first(predicate) }
            ?: throw AssertionError("Timed out waiting for $label")

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
