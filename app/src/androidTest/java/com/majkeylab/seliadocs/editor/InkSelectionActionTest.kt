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
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.data.StrokePayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class InkSelectionActionTest {
    @Test
    fun duplicateSelectedStrokesCopiesOnlySelectionAndSelectsCopy() = runBlocking {
        withEditor("Duplicate ink") { repository, viewModel, pageId ->
            val selectedId = repository.addStroke(pageId, strokePayload(50f, 50f, 70f, 70f))
            repository.addStroke(pageId, strokePayload(200f, 200f, 220f, 220f))
            viewModel.awaitState("two strokes") { it.selectedStrokes.size == 2 }
            onMain { viewModel.selectStrokes(pageId, selectionAroundFirstStroke()) }
            assertEquals(setOf(selectedId), viewModel.state.value.selectedStrokeIds)

            onMain(viewModel::duplicateSelectedStrokes)

            val duplicated = viewModel.awaitState("duplicated ink selected and undoable") {
                it.selectedStrokes.size == 3 && it.canUndo &&
                    it.selectedStrokeIds.size == 1 && selectedId !in it.selectedStrokeIds
            }
            val copyId = duplicated.selectedStrokeIds.single()
            val source = duplicated.selectedStrokes.single { it.id == selectedId }
            val copy = duplicated.selectedStrokes.single { it.id == copyId }
            assertNotEquals(source.id, copy.id)
            assertEquals(source.brushKind, copy.brushKind)
            assertEquals(source.colorArgb, copy.colorArgb)
            assertEquals(source.size, copy.size)
            assertEquals(source.zIndex + 2, copy.zIndex)
            assertEquals(CanvasPoint(62f, 62f), copy.toStrokePath().points.first())
            assertTrue(duplicated.canUndo)

            onMain(viewModel::undo)

            val restored = viewModel.awaitState("ink duplicate undo") { it.selectedStrokes.size == 2 }
            assertTrue(restored.selectedStrokes.any { it.id == selectedId })
            assertFalse(restored.selectedStrokes.any { it.id == copyId })
        }
    }

    @Test
    fun deleteSelectedStrokesIsUndoable() = runBlocking {
        withEditor("Delete ink") { repository, viewModel, pageId ->
            val selectedId = repository.addStroke(pageId, strokePayload(50f, 50f, 70f, 70f))
            val keptId = repository.addStroke(pageId, strokePayload(200f, 200f, 220f, 220f))
            viewModel.awaitState("two strokes") { it.selectedStrokes.size == 2 }
            onMain { viewModel.selectStrokes(pageId, selectionAroundFirstStroke()) }

            onMain(viewModel::deleteSelectedStrokes)

            val deleted = viewModel.awaitState("selected ink deleted") { it.selectedStrokes.size == 1 }
            assertEquals(keptId, deleted.selectedStrokes.single().id)
            assertTrue(deleted.selectedStrokeIds.isEmpty())

            onMain(viewModel::undo)

            val restored = viewModel.awaitState("ink delete undo") { it.selectedStrokes.size == 2 }
            assertEquals(setOf(selectedId, keptId), restored.selectedStrokes.mapTo(mutableSetOf()) { it.id })
        }
    }

    @Test
    fun recolorSelectedStrokesPreservesInputAndIsUndoable() = runBlocking {
        withEditor("Recolor ink") { repository, viewModel, pageId ->
            val selectedId = repository.addStroke(pageId, strokePayload(50f, 50f, 70f, 70f))
            val keptId = repository.addStroke(pageId, strokePayload(200f, 200f, 220f, 220f))
            val original = viewModel.awaitState("two strokes") { it.selectedStrokes.size == 2 }
            val source = original.selectedStrokes.single { it.id == selectedId }
            onMain { viewModel.selectStrokes(pageId, selectionAroundFirstStroke()) }

            onMain { viewModel.recolorSelectedStrokes(BLUE) }

            val recolored = viewModel.awaitState("selected ink recolored") {
                it.selectedStrokes.single { stroke -> stroke.id == selectedId }.colorArgb == BLUE
            }
            val changed = recolored.selectedStrokes.single { it.id == selectedId }
            val kept = recolored.selectedStrokes.single { it.id == keptId }
            assertEquals(source.brushKind, changed.brushKind)
            assertEquals(source.size, changed.size)
            assertArrayEquals(source.inputs, changed.inputs)
            assertEquals(0xFF202124.toInt(), kept.colorArgb)

            onMain(viewModel::undo)

            val restored = viewModel.awaitState("ink recolor undo") {
                it.selectedStrokes.single { stroke -> stroke.id == selectedId }.colorArgb == source.colorArgb
            }
            assertArrayEquals(source.inputs, restored.selectedStrokes.single { it.id == selectedId }.inputs)
        }
    }

    @Test
    fun recolorSelectedHighlighterPreservesOpacity() = runBlocking {
        withEditor("Recolor highlighter") { repository, viewModel, pageId ->
            val selectedId =
                repository.addStroke(
                    pageId,
                    strokePayload(
                        50f,
                        50f,
                        70f,
                        70f,
                        BrushKind.HIGHLIGHTER,
                        0x66FFD54F,
                    ),
                )
            viewModel.awaitState("highlighter") { it.selectedStrokes.size == 1 }
            onMain { viewModel.selectStrokes(pageId, selectionAroundFirstStroke()) }

            onMain { viewModel.recolorSelectedStrokes(BLUE) }

            val recolored = viewModel.awaitState("highlighter recolored") {
                it.selectedStrokes.single { stroke -> stroke.id == selectedId }.colorArgb == 0x663156D9
            }
            assertEquals(0x663156D9, recolored.selectedStrokes.single().colorArgb)
        }
    }

    @Test
    fun transformSelectedStrokesScalesRotatesSensorsAndUndo() = runBlocking {
        withEditor("Transform ink") { repository, viewModel, pageId ->
            val selectedId = repository.addStroke(pageId, strokePayload(50f, 50f, 70f, 70f))
            val keptId = repository.addStroke(pageId, strokePayload(200f, 200f, 220f, 220f))
            val original = viewModel.awaitState("two strokes") { it.selectedStrokes.size == 2 }
            val source = original.selectedStrokes.single { it.id == selectedId }
            val keptInputs = original.selectedStrokes.single { it.id == keptId }.inputs.copyOf()
            onMain { viewModel.selectStrokes(pageId, selectionAroundFirstStroke()) }

            onMain { viewModel.transformSelectedStrokes(scale = 2f, rotationDegrees = 90f) }

            val transformed = viewModel.awaitState("selected ink transformed") {
                it.selectedStrokes.single { stroke -> stroke.id == selectedId }.size == 8f
            }
            val changed = transformed.selectedStrokes.single { it.id == selectedId }
            val inputs = changed.toInkStroke().inputs
            assertEquals(80f, inputs[0].x, 0.01f)
            assertEquals(40f, inputs[0].y, 0.01f)
            assertEquals(40f, inputs[1].x, 0.01f)
            assertEquals(80f, inputs[1].y, 0.01f)
            assertEquals(0.5f, inputs[0].pressure, 0.001f)
            assertEquals(0f, inputs[0].tiltRadians, 0.001f)
            assertEquals(Math.PI.toFloat() / 2f, inputs[0].orientationRadians, 0.001f)
            assertArrayEquals(keptInputs, transformed.selectedStrokes.single { it.id == keptId }.inputs)

            onMain(viewModel::undo)

            val restored = viewModel.awaitState("ink transform undo") {
                it.selectedStrokes.single { stroke -> stroke.id == selectedId }.size == source.size
            }
            assertArrayEquals(source.inputs, restored.selectedStrokes.single { it.id == selectedId }.inputs)
        }
    }

    @Test
    fun invalidSelectedStrokeTransformDoesNothing() = runBlocking {
        withEditor("Invalid transform") { repository, viewModel, pageId ->
            val selectedId = repository.addStroke(pageId, strokePayload(50f, 50f, 70f, 70f))
            val original = viewModel.awaitState("stroke") { it.selectedStrokes.size == 1 }.selectedStrokes.single()
            onMain { viewModel.selectStrokes(pageId, selectionAroundFirstStroke()) }

            onMain { viewModel.transformSelectedStrokes(scale = 0f, rotationDegrees = Float.NaN) }

            val unchanged = viewModel.state.value
            assertArrayEquals(original.inputs, unchanged.selectedStrokes.single { it.id == selectedId }.inputs)
            assertEquals(original.size, unchanged.selectedStrokes.single().size)
            assertFalse(unchanged.canUndo)
        }
    }

    @Test
    fun queuedMoveUsesSelectionAtGestureEnd() = runBlocking {
        withEditor("Move selection race") { repository, viewModel, pageId ->
            val firstId = repository.addStroke(pageId, strokePayload(50f, 50f, 70f, 70f))
            val secondId = repository.addStroke(pageId, strokePayload(200f, 200f, 220f, 220f))
            viewModel.awaitState("two strokes") { it.selectedStrokes.size == 2 }
            onMain { viewModel.selectStrokes(pageId, selectionAroundFirstStroke()) }
            val gateAcquired = CountDownLatch(1)
            val releaseGate = CompletableDeferred<Unit>()
            val gateOwner =
                CoroutineScope(Dispatchers.IO).launch {
                    LibraryMutationGate.withLock {
                        gateAcquired.countDown()
                        releaseGate.await()
                    }
                }
            try {
                assertTrue(gateAcquired.await(5, TimeUnit.SECONDS))
                onMain {
                    viewModel.moveSelectedStrokes(pageId, CanvasPoint(20f, 0f))
                    viewModel.selectStrokes(pageId, selectionAroundSecondStroke())
                }
            } finally {
                releaseGate.complete(Unit)
                gateOwner.join()
            }

            val moved = viewModel.awaitState("first stroke moved") {
                it.selectedStrokes.single { stroke -> stroke.id == firstId }.toStrokePath().points.first().x == 70f
            }
            assertEquals(200f, moved.selectedStrokes.single { it.id == secondId }.toStrokePath().points.first().x)
        }
    }

    private suspend fun withEditor(
        title: String,
        block: suspend (SeliaDocsRepository, EditorViewModel, String) -> Unit,
    ) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook(title))
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val pageId = viewModel.awaitState("page") { it.selectedPage != null }.selectedPage!!.id
            block(repository, viewModel, pageId)
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    private fun strokePayload(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        brushKind: BrushKind = BrushKind.PRESSURE_PEN,
        colorArgb: Int = 0xFF202124.toInt(),
    ): StrokePayload {
        val inputs =
            MutableStrokeInputBatch().apply {
                add(InputToolType.STYLUS, startX, startY, 0L, 0.01f, 0.5f, 0f, 0f)
                add(InputToolType.STYLUS, endX, endY, 16L, 0.01f, 0.5f, 0f, 0f)
            }
        val encoded =
            InkCodec.encode(
                Stroke(
                    InkCodec.createBrush(brushKind, colorArgb, 4f),
                    inputs,
                ),
            )
        return StrokePayload(
            encoded.brushKind.name,
            encoded.colorArgb,
            encoded.size,
            encoded.epsilon,
            encoded.inputs,
        )
    }

    private fun selectionAroundFirstStroke() =
        listOf(
            CanvasPoint(40f, 40f),
            CanvasPoint(90f, 40f),
            CanvasPoint(90f, 90f),
            CanvasPoint(40f, 90f),
            CanvasPoint(40f, 40f),
        )

    private fun selectionAroundSecondStroke() =
        listOf(
            CanvasPoint(190f, 190f),
            CanvasPoint(230f, 190f),
            CanvasPoint(230f, 230f),
            CanvasPoint(190f, 230f),
            CanvasPoint(190f, 190f),
        )

    private suspend fun EditorViewModel.awaitState(
        label: String,
        predicate: (EditorUiState) -> Boolean,
    ): EditorUiState =
        withTimeoutOrNull(TIMEOUT_MS) { state.first(predicate) }
            ?: throw AssertionError("Timed out waiting for $label")

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun testNotebook(title: String) =
        CreateNotebookRequest(
            title = "$title ${System.nanoTime()}",
            coverColor = CoverColor.PERIWINKLE,
            coverPattern = CoverPattern.SOLID,
            paper = PaperTemplate.RULED,
            orientation = PageOrientation.PORTRAIT,
            fingerDrawing = false,
        )

    private companion object {
        const val BLUE = 0xFF3156D9.toInt()
        const val TIMEOUT_MS = 30_000L
    }
}
