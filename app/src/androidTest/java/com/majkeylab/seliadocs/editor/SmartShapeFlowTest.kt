package com.majkeylab.seliadocs.editor

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class SmartShapeFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun heldLineConvertsToUndoableShapeAndToggleCanDisableIt() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(request())
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val pageId = await(viewModel, "page") { it.selectedPage != null }.selectedPage!!.id

            val committed = CompletableDeferred<Long?>()
            val completed = CompletableDeferred<Boolean>()
            onMain {
                viewModel.addStroke(
                    pageId, heldLine(colorArgb = 0x803156D9.toInt(), width = 12f), shapeAssist = true,
                    onCommitted = { committed.complete(it) },
                    onComplete = {
                        assertTrue("Canonical acknowledgement precedes completion", committed.isCompleted)
                        completed.complete(it)
                    },
                )
            }
            assertEquals(true, withTimeoutOrNull(5_000) { completed.await() })
            val committedRevision = requireNotNull(committed.await())
            assertEquals(repository.getPage(pageId)!!.updatedAt, committedRevision)
            val shape = await(viewModel, "smart line") {
                it.elements.size == 1 && it.strokes.isEmpty() && it.canUndo && it.selectedPageContentRevision == committedRevision
            }
            assertEquals(ShapeKind.LINE.name, shape.elements.single().shapeKind)
            assertEquals(0x803156D9.toInt(), shape.elements.single().colorArgb)
            assertEquals(12f, shape.elements.single().strokeWidth)
            assertEquals(0x803156D9.toInt(), repository.getElements(pageId).single().colorArgb)
            assertEquals(shape.elements.single().id, shape.smartShapePreviewId)
            assertEquals(0, shape.strokes.size)

            onMain(viewModel::undo)
            val original = await(viewModel, "smart line undo") { it.elements.isEmpty() && it.strokes.size == 1 }
            assertEquals(BrushKind.PRESSURE_PEN.name, original.strokes.single().brushKind)
            assertEquals(0x803156D9.toInt(), original.strokes.single().colorArgb)
            assertEquals(12f, original.strokes.single().size)
            onMain(viewModel::redo)
            val redone = await(viewModel, "smart line redo") { it.elements.size == 1 && it.strokes.isEmpty() }
            assertEquals(0x803156D9.toInt(), redone.elements.single().colorArgb)
            assertEquals(12f, redone.elements.single().strokeWidth)
            onMain(viewModel::undo)
            await(viewModel, "smart line undo to original") { it.elements.isEmpty() && it.strokes.size == 1 }
            onMain(viewModel::undo)
            await(viewModel, "smart line undo to empty") { it.elements.isEmpty() && it.strokes.isEmpty() }
            onMain { viewModel.addStroke(pageId, heldLine(), shapeAssist = false) }
            val raw = await(viewModel, "disabled assist") { it.strokes.size == 1 }
            assertEquals(0, raw.elements.size)
            val rejected = CompletableDeferred<Long?>()
            val failed = CompletableDeferred<Boolean>()
            onMain {
                viewModel.addStroke(
                    "$pageId-missing", heldLine(), shapeAssist = false,
                    onCommitted = { rejected.complete(it) }, onComplete = { failed.complete(it) },
                )
            }
            assertEquals(false, withTimeoutOrNull(5_000) { failed.await() })
            assertEquals(null, rejected.await())
            assertEquals(1, repository.getStrokes(pageId).size)
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    @Test
    fun manualShapeUsesTheEarliestSelectedStrokeStyleAndUndoRestoresBoth() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(request())
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val pageId = await(viewModel, "page") { it.selectedPage != null }.selectedPage!!.id
            onMain { viewModel.addStroke(pageId, heldLine(0x80E53935.toInt(), 9f), shapeAssist = false) }
            await(viewModel, "first ink") { it.strokes.size == 1 }
            onMain { viewModel.addStroke(pageId, heldLine(0xFF3156D9.toInt(), 3f), shapeAssist = false) }
            await(viewModel, "second ink") { it.strokes.size == 2 }
            onMain {
                viewModel.selectStrokes(pageId, listOf(CanvasPoint(20f, 60f), CanvasPoint(240f, 60f),
                    CanvasPoint(240f, 100f), CanvasPoint(20f, 100f), CanvasPoint(20f, 60f)))
            }
            await(viewModel, "both selected") { it.selectedStrokeIds.size == 2 }
            onMain { viewModel.cleanSelectedShape(ShapeKind.LINE) }
            val clean = await(viewModel, "manual shape") { it.strokes.isEmpty() && it.elements.size == 1 }
            assertEquals(0x80E53935.toInt(), clean.elements.single().colorArgb)
            assertEquals(9f, clean.elements.single().strokeWidth)
            onMain(viewModel::undo)
            val restored = await(viewModel, "manual undo") { it.elements.isEmpty() && it.strokes.size == 2 }
            assertEquals(listOf(0x80E53935.toInt(), 0xFF3156D9.toInt()), restored.strokes.map { it.colorArgb })
            assertEquals(listOf(9f, 3f), restored.strokes.map { it.size })
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

    @Test
    fun delayedPenCompletionUsesStrokeBrushInsteadOfCurrentToolbarTool() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(request())
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val pageId = await(viewModel, "page") { it.selectedPage != null }.selectedPage!!.id

            onMain {
                viewModel.selectTool(EditorTool.HIGHLIGHTER)
                viewModel.addStroke(pageId, heldLine(), shapeAssist = true)
            }

            val converted = await(viewModel, "delayed pen shape") { it.elements.size == 1 && it.strokes.isEmpty() }
            assertEquals(ShapeKind.LINE.name, converted.elements.single().shapeKind)
            assertEquals(0, converted.strokes.size)
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    @Test
    fun heldShapesEraseInBothModesAndUndoRestoresStyles() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(request())
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val pageId = await(viewModel, "page") { it.selectedPage != null }.selectedPage!!.id
            EraserMode.entries.forEach { mode ->
                listOf(
                    ShapeKind.LINE to heldLine(0x803156D9.toInt(), 12f),
                    ShapeKind.ARROW to heldArrow(),
                    ShapeKind.ELLIPSE to heldEllipse(),
                ).forEach { (kind, stroke) ->
                    onMain { viewModel.addStroke(pageId, stroke, shapeAssist = true) }
                    var shape = await(viewModel, "held $kind") {
                        it.elements.singleOrNull()?.shapeKind == kind.name && it.strokes.isEmpty()
                    }.elements.single()
                    if (kind == ShapeKind.ARROW) {
                        onMain { viewModel.selectElement(shape.id) }
                        await(viewModel, "selected arrow") { it.selectedElement?.id == shape.id }
                        onMain { viewModel.updateSelectedElement(shape.transform().copy(rotation = 90f)) }
                        shape = await(viewModel, "rotated arrow") {
                            it.elements.singleOrNull()?.rotation == 90f
                        }.elements.single()
                    }
                    onMain {
                        viewModel.selectTool(EditorTool.ERASER)
                        viewModel.setEraserMode(mode)
                    }
                    val tapPointCount = if (mode == EraserMode.SEGMENT) 3 else 2
                    if (kind == ShapeKind.ELLIPSE) {
                        onMain { viewModel.eraseStrokes(pageId, List(tapPointCount) { CanvasPoint(150f, 180f) }) }
                        LibraryMutationGate.withLock {
                            assertEquals(listOf(shape), repository.getElements(pageId))
                        }
                    }
                    val hit = when (kind) {
                        ShapeKind.LINE -> CanvasPoint(130f, 101f)
                        ShapeKind.ARROW -> CanvasPoint(114f, 112f)
                        else -> CanvasPoint(210f, 180f)
                    }
                    onMain { viewModel.eraseStrokes(pageId, List(tapPointCount) { hit }) }
                    await(viewModel, "$mode $kind erase") { it.elements.isEmpty() && it.strokes.isEmpty() }
                    onMain(viewModel::undo)
                    val restored = await(viewModel, "$mode $kind undo") { it.elements.size == 1 }
                    assertEquals(shape, restored.elements.single())
                    assertEquals(listOf(shape), repository.getElements(pageId))
                    onMain(viewModel::redo)
                    await(viewModel, "$mode $kind redo") { it.elements.isEmpty() && it.strokes.isEmpty() }
                }
            }
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    @Test
    fun erasingMixedInkAndShapeIsOneUndoStep() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(request())
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val pageId = await(viewModel, "page") { it.selectedPage != null }.selectedPage!!.id
            onMain { viewModel.addStroke(pageId, heldLine(0x80E53935.toInt(), 6f), shapeAssist = false) }
            val raw = await(viewModel, "raw ink") { it.strokes.size == 1 }.strokes.single()
            onMain { viewModel.addStroke(pageId, heldLine(0x803156D9.toInt(), 12f), shapeAssist = true) }
            val shape = await(viewModel, "mixed shape") { it.elements.size == 1 && it.strokes.size == 1 }.elements.single()
            onMain {
                viewModel.selectTool(EditorTool.ERASER)
                viewModel.setEraserMode(EraserMode.SEGMENT)
                viewModel.eraseStrokes(pageId, listOf(CanvasPoint(120f, 60f), CanvasPoint(120f, 100f)))
            }
            val erased = await(viewModel, "mixed erase") { it.elements.isEmpty() && it.strokes.size == 2 }
            onMain(viewModel::undo)
            val restored = await(viewModel, "mixed undo") { it.elements.size == 1 && it.strokes.size == 1 }
            assertEquals(shape, restored.elements.single())
            assertEquals(raw.id, restored.strokes.single().id)
            assertEquals(raw.colorArgb, restored.strokes.single().colorArgb)
            assertEquals(raw.size, restored.strokes.single().size)
            assertArrayEquals(raw.inputs, restored.strokes.single().inputs)
            onMain(viewModel::redo)
            val redone = await(viewModel, "mixed redo") { it.elements.isEmpty() && it.strokes.size == 2 }
            assertEquals(erased.strokes.map { it.id }, redone.strokes.map { it.id })
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    @Test
    fun legacyShapePreviewMatchesCanonicalStyleAtReducedScale() {
        val kinds = listOf(ShapeKind.ARROW, ShapeKind.RECTANGLE, ShapeKind.TRIANGLE)
        val strokeWidth = mutableStateOf<Float?>(null)
        rule.setContent {
            SeliaDocsTheme {
                Surface(color = Color.White) {
                    Column {
                        kinds.forEach { kind ->
                            val legacy = ElementEntity("legacy", "page", 0, "SHAPE", 0f, 0f, 600f, 400f,
                                0f, null, null, kind.name, null, null)
                            CleanShape(legacy.copy(strokeWidth = strokeWidth.value),
                                Modifier.size(60.dp, 40.dp).testTag("shape-$kind"))
                        }
                    }
                }
            }
        }
        val legacyImages = kinds.associateWith { kind ->
            rule.onNodeWithTag("shape-$kind").captureToImage().toPixelMap()
        }
        // Keep the screen position unchanged so GPU edge rasterization is comparable.
        rule.runOnIdle { strokeWidth.value = 3f }
        kinds.forEach { kind ->
            val legacy = legacyImages.getValue(kind)
            val canonical = rule.onNodeWithTag("shape-$kind").captureToImage().toPixelMap()
            assertEquals(canonical.width, legacy.width)
            assertEquals(canonical.height, legacy.height)
            val legacyPixels = IntArray(legacy.width * legacy.height) { index ->
                legacy[index % legacy.width, index / legacy.width].toArgb()
            }
            val canonicalPixels = IntArray(canonical.width * canonical.height) { index ->
                canonical[index % canonical.width, index / canonical.width].toArgb()
            }
            assertTrue("$kind must render visible ink", legacyPixels.any { it != Color.White.toArgb() })
            assertArrayEquals("$kind legacy preview must match canonical style", canonicalPixels, legacyPixels)
        }
    }

    @Test
    fun paintedTriangleMiterTipErasesInBothModesAndInteriorDoesNot() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(request())
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val page = await(viewModel, "page") { it.selectedPage != null }.selectedPage!!
            val inputs = heldStroke(listOf(
                TimedCanvasPoint(CanvasPoint(150f, 80f), 0L),
                TimedCanvasPoint(CanvasPoint(180f, 130f), 80L),
                TimedCanvasPoint(CanvasPoint(210f, 180f), 160L),
                TimedCanvasPoint(CanvasPoint(150f, 180f), 240L),
                TimedCanvasPoint(CanvasPoint(90f, 180f), 320L),
                TimedCanvasPoint(CanvasPoint(120f, 130f), 400L),
                TimedCanvasPoint(CanvasPoint(150f, 80f), 480L),
                TimedCanvasPoint(CanvasPoint(150f, 80f), 820L),
            )).inputs
            val stroke = Stroke(InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 32f), inputs)
            val bitmap = Bitmap.createBitmap(page.widthPoints, page.heightPoints, Bitmap.Config.ARGB_8888)
            try {
                EraserMode.entries.forEach { mode ->
                    onMain { viewModel.addStroke(page.id, stroke, shapeAssist = true) }
                    val shape = await(viewModel, "held triangle") {
                        it.elements.singleOrNull()?.shapeKind == ShapeKind.TRIANGLE.name && it.strokes.isEmpty()
                    }.elements.single()
                    PdfExporter(AssetStore(application.cacheDir)).renderPage(android.graphics.Canvas(bitmap),
                        page, emptyList(), listOf(shape), emptyList(), null)
                    assertTrue("Miter tip must have painted ink outside the nominal box",
                        android.graphics.Color.red(bitmap.getPixel(150, 54)) < 128)
                    onMain {
                        viewModel.selectTool(EditorTool.ERASER)
                        viewModel.setEraserMode(mode)
                        viewModel.eraseStrokes(page.id, listOf(CanvasPoint(150f, 146f)))
                    }
                    LibraryMutationGate.withLock {
                        assertEquals(listOf(shape), repository.getElements(page.id))
                    }
                    onMain { viewModel.eraseStrokes(page.id, listOf(CanvasPoint(150f, 38f))) }
                    await(viewModel, "$mode miter erase") { it.elements.isEmpty() && it.strokes.isEmpty() }
                    onMain(viewModel::undo)
                    val restored = await(viewModel, "$mode miter undo") { it.elements.size == 1 }
                    assertEquals(shape, restored.elements.single())
                    onMain(viewModel::redo)
                    await(viewModel, "$mode miter redo") { it.elements.isEmpty() && it.strokes.isEmpty() }
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    private fun heldLine(colorArgb: Int = 0xFF202124.toInt(), width: Float = 4f): Stroke =
        Stroke(
            InkCodec.createBrush(BrushKind.PRESSURE_PEN, colorArgb, width),
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
