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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class SmartShapeFlowTest {
    @Test
    fun heldLineConvertsToUndoableShapeAndToggleCanDisableIt() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(request())
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val pageId = await(viewModel, "page") { it.selectedPage != null }.selectedPage!!.id

            onMain { viewModel.addStroke(pageId, heldLine(), shapeAssist = true) }
            val shape = await(viewModel, "smart line") {
                it.elements.size == 1 && it.strokes.isEmpty() && it.canUndo
            }
            assertEquals(ShapeKind.LINE.name, shape.elements.single().shapeKind)
            assertEquals(shape.elements.single().id, shape.smartShapePreviewId)
            assertEquals(0, shape.strokes.size)

            onMain(viewModel::undo)
            val original = await(viewModel, "smart line undo") { it.elements.isEmpty() && it.strokes.size == 1 }
            assertEquals(BrushKind.PRESSURE_PEN.name, original.strokes.single().brushKind)
            onMain(viewModel::redo)
            await(viewModel, "smart line redo") { it.elements.size == 1 && it.strokes.isEmpty() }
            onMain(viewModel::undo)
            await(viewModel, "smart line undo to original") { it.elements.isEmpty() && it.strokes.size == 1 }
            onMain(viewModel::undo)
            await(viewModel, "smart line undo to empty") { it.elements.isEmpty() && it.strokes.isEmpty() }
            onMain { viewModel.addStroke(pageId, heldLine(), shapeAssist = false) }
            val raw = await(viewModel, "disabled assist") { it.strokes.size == 1 }
            assertEquals(0, raw.elements.size)
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    @Test
    fun heldArrowAndEllipseConvertAndUndoRestoresRawInk() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(request())
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val pageId = await(viewModel, "page") { it.selectedPage != null }.selectedPage!!.id

            listOf(ShapeKind.ARROW to heldArrow(), ShapeKind.ELLIPSE to heldEllipse()).forEach { (kind, stroke) ->
                val expected = InkCodec.encode(stroke)
                onMain { viewModel.addStroke(pageId, stroke, shapeAssist = true) }
                val converted = await(viewModel, "held $kind") {
                    it.elements.singleOrNull()?.shapeKind == kind.name && it.strokes.isEmpty()
                }
                assertEquals(kind.name, converted.elements.single().shapeKind)

                onMain(viewModel::undo)
                val raw = await(viewModel, "$kind undo") { it.elements.isEmpty() && it.strokes.size == 1 }
                val restored = raw.strokes.single()
                assertEquals(expected.brushKind.name, restored.brushKind)
                assertEquals(expected.colorArgb, restored.colorArgb)
                assertEquals(expected.size, restored.size)
                assertEquals(expected.epsilon, restored.epsilon)
                assertArrayEquals(expected.inputs, restored.inputs)
                onMain(viewModel::undo)
                await(viewModel, "$kind clear") { it.elements.isEmpty() && it.strokes.isEmpty() }
            }
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    private fun heldLine(): Stroke =
        Stroke(
            InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 4f),
            MutableStrokeInputBatch()
                .add(InputToolType.STYLUS, 40f, 80f, 0L, 0.01f, 0.7f, 0.2f, 0.3f)
                .add(InputToolType.STYLUS, 120f, 81f, 160L, 0.01f, 0.7f, 0.2f, 0.3f)
                .add(InputToolType.STYLUS, 220f, 80f, 260L, 0.01f, 0.7f, 0.2f, 0.3f)
                .add(InputToolType.STYLUS, 220f, 80f, 600L, 0.01f, 0.7f, 0.2f, 0.3f),
        )

    private fun heldArrow(): Stroke =
        heldStroke(
            listOf(
                TimedCanvasPoint(CanvasPoint(40f, 80f), 0L),
                TimedCanvasPoint(CanvasPoint(90f, 80f), 80L),
                TimedCanvasPoint(CanvasPoint(140f, 80f), 160L),
                TimedCanvasPoint(CanvasPoint(122f, 64f), 220L),
                TimedCanvasPoint(CanvasPoint(140f, 80f), 260L),
                TimedCanvasPoint(CanvasPoint(122f, 96f), 320L),
                TimedCanvasPoint(CanvasPoint(140f, 80f), 360L),
                TimedCanvasPoint(CanvasPoint(140f, 80f), 700L),
            ),
        )

    private fun heldEllipse(): Stroke {
        val points =
            (0..16).map { index ->
                val angle = 2.0 * PI * index / 16.0
                TimedCanvasPoint(
                    CanvasPoint(
                        150f + 60f * cos(angle).toFloat(),
                        180f + 40f * sin(angle).toFloat(),
                    ),
                    index * 20L,
                )
            }
        return heldStroke(
            points +
                TimedCanvasPoint(CanvasPoint(210f, 180f), 650L) +
                TimedCanvasPoint(CanvasPoint(210f, 180f), 720L),
        )
    }

    private fun heldStroke(points: List<TimedCanvasPoint>): Stroke {
        val inputs = MutableStrokeInputBatch()
        points.forEach { point ->
            inputs.add(
                InputToolType.STYLUS,
                point.point.x,
                point.point.y,
                point.elapsedTimeMillis,
                0.01f,
                0.7f,
                0.2f,
                0.3f,
            )
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

    private fun request() =
        CreateNotebookRequest(
            "Smart shape ${System.nanoTime()}",
            CoverColor.PERIWINKLE,
            CoverPattern.SOLID,
            PaperTemplate.BLANK,
            PageOrientation.PORTRAIT,
            false,
        )
}
