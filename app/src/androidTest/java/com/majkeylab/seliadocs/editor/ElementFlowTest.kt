package com.majkeylab.seliadocs.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.ElementDraft
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElementFlowTest {
    @Test
    fun importedImageTextIsSearchable() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("Image OCR"))
        val source = File(application.cacheDir, "ocr-${System.nanoTime()}.png")
        var importedAssetId: String? = null
        val bitmap = Bitmap.createBitmap(1_400, 360, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText(
                    "ORGANIC CHEMISTRY 2026",
                    40f,
                    220f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 112f
                        typeface = Typeface.DEFAULT_BOLD
                    },
                )
            }
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val page = viewModel.awaitState("OCR page") { it.selectedPage != null }.selectedPage!!

            onMain { viewModel.importImage(page.id, Uri.fromFile(source)) }
            val imported = viewModel.awaitState("OCR image import") { it.elements.size == 1 }
            importedAssetId = imported.elements.single().assetId

            val match = repository.searchPageText(notebookId, "organic chemistry").single()
            assertEquals(page.id, match.pageId)
            assertTrue(
                repository.searchPageText(
                    notebookId,
                    "organic chemistry",
                    includeImageOcr = false,
                ).isEmpty(),
            )
        } finally {
            repository.deleteNotebook(notebookId)
            importedAssetId?.let { AssetStore(File(application.filesDir, "assets")).file(it).delete() }
            source.delete()
        }
    }

    @Test
    fun manualMathUsesAssignmentsFromPageText() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("Math variables"))
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val pageId = viewModel.awaitState("math page") { it.selectedPage != null }.selectedPage!!.id
            onMain { viewModel.updatePageText(pageId, "width=12\nheight=4") }
            viewModel.awaitState("math assignments") {
                it.selectedBlocks.singleOrNull()?.text == "width=12\nheight=4"
            }

            onMain { viewModel.addMath(pageId, "width*height=") }
            val math = viewModel.awaitState("manual variable math") { it.elements.size == 1 }.elements.single()
            assertEquals("width*height = 48", math.resultText)
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    @Test
    fun importedImageIsSelectedAndTransformable() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("Image import"))
        val source = File(application.cacheDir, "image-${System.nanoTime()}.png")
        var importedAssetId: String? = null
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val page = viewModel.awaitState("image page") { it.selectedPage != null }.selectedPage!!

            onMain { viewModel.importImage(page.id, Uri.fromFile(source)) }
            val imported =
                viewModel.awaitState("image import") {
                    it.elements.singleOrNull()?.let { image ->
                        image.kind == ElementKind.IMAGE.name &&
                            it.tool == EditorTool.LASSO &&
                            it.selectedElementId == image.id
                    } == true
                }
            val image = imported.elements.single()
            importedAssetId = image.assetId
            assertEquals(EditorTool.LASSO, imported.tool)
            assertEquals(image.id, imported.selectedElementId)

            val transformed = image.transform().copy(x = 40f, y = 60f, width = 240f, height = 120f, rotation = 15f)
            onMain { viewModel.updateSelectedElement(transformed) }
            viewModel.awaitState("image transform") { it.selectedElement?.rotation == 15f }
            onMain(viewModel::undo)
            viewModel.awaitState("image transform undo") { it.selectedElement?.rotation == image.rotation }
            onMain(viewModel::redo)
            viewModel.awaitState("image transform redo") { it.selectedElement?.rotation == 15f }
            Unit
        } finally {
            repository.deleteNotebook(notebookId)
            importedAssetId?.let { AssetStore(File(application.filesDir, "assets")).file(it).delete() }
            source.delete()
        }
    }

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
                    it.elements.single { element -> element.id == textElement.id }.x == textElement.x &&
                        it.selectedElementId == textElement.id
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

    @Test
    fun firstEditAfterPageSwitchKeepsExistingContentOnUndo() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("History switch"))
        try {
            val firstPage = repository.getPages(notebookId).single()
            val secondPageId = repository.addPage(notebookId)
            val originalId =
                repository.addElement(
                    secondPageId,
                    ElementDraft(ElementKind.TEXT, 20f, 20f, 200f, 80f, text = "Existing"),
                )
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            viewModel.awaitState("first page load") { it.selectedPage?.id == firstPage.id }

            onMain {
                viewModel.selectPage(secondPageId)
                viewModel.addText(secondPageId, "New")
            }
            viewModel.awaitState("second page edit") { it.elements.size == 2 && it.canUndo }
            onMain(viewModel::undo)

            val restored = viewModel.awaitState("second page undo") { it.elements.size == 1 }
            assertEquals(originalId, restored.elements.single().id)
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    @Test
    fun imageAssetSurvivesDeleteUndo() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("Image undo"))
        val assetStore = AssetStore(File(application.filesDir, "assets"))
        val assetId = "undo-${System.nanoTime()}.png"
        val assetFile = assetStore.prepare().let { assetStore.file(assetId) }
        assetFile.writeBytes(byteArrayOf(1, 2, 3))
        try {
            val pageId = repository.getPages(notebookId).single().id
            val elementId =
                repository.addElement(
                    pageId,
                    ElementDraft(ElementKind.IMAGE, 20f, 20f, 200f, 100f, assetId = assetId),
                )
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            viewModel.awaitState("image load") { it.elements.singleOrNull()?.id == elementId }

            onMain {
                viewModel.selectElement(elementId)
                viewModel.deleteSelectedElement()
            }
            viewModel.awaitState("image delete") { it.elements.isEmpty() && it.canUndo }
            onMain(viewModel::undo)
            viewModel.awaitState("image undo") { it.elements.singleOrNull()?.id == elementId }

            assertTrue(assetFile.isFile)
        } finally {
            repository.deleteNotebook(notebookId)
            assetFile.delete()
        }
    }

    @Test
    fun failedMutationDoesNotBlockNextEdit() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("Mutation recovery"))
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val pageId = viewModel.awaitState("page load") { it.selectedPage != null }.selectedPage!!.id

            onMain { viewModel.addMath(pageId, "1/0=") }
            viewModel.awaitState("failed math") { it.failed }
            onMain { viewModel.addText(pageId, "Recovered") }

            val recovered = viewModel.awaitState("edit after failure") { it.elements.size == 1 && !it.failed }
            assertEquals("Recovered", recovered.elements.single().text)
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    @Test
    fun queuedDeleteUsesSelectionAtTapTime() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("Selection race"))
        try {
            val pageId = repository.getPages(notebookId).single().id
            val firstId =
                repository.addElement(
                    pageId,
                    ElementDraft(ElementKind.TEXT, 20f, 20f, 200f, 80f, text = "First"),
                )
            val secondId =
                repository.addElement(
                    pageId,
                    ElementDraft(ElementKind.TEXT, 20f, 120f, 200f, 80f, text = "Second"),
                )
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            viewModel.awaitState("elements load") { it.elements.size == 2 }

            onMain {
                viewModel.addText(pageId, "Queued first")
                viewModel.selectElement(firstId)
                viewModel.deleteSelectedElement()
                viewModel.selectElement(secondId)
            }

            val remaining =
                viewModel.awaitState("selected element delete") {
                    it.elements.size == 2 && it.elements.any { element -> element.text == "Queued first" }
                }
            assertTrue(remaining.elements.any { it.id == secondId })
            assertTrue(remaining.elements.none { it.id == firstId })
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    @Test
    fun pageTextSupportsUndoAndRedo() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("Page text"))
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val pageId = viewModel.awaitState("page load") { it.selectedPage != null }.selectedPage!!.id

            onMain { viewModel.updatePageText(pageId, "First paragraph\n\nSecond paragraph") }
            viewModel.awaitState("page text save") { it.blocks.singleOrNull()?.text?.startsWith("First") == true && it.canUndo }
            onMain(viewModel::undo)
            viewModel.awaitState("page text undo") { it.blocks.isEmpty() && it.canRedo }
            onMain(viewModel::redo)

            val restored = viewModel.awaitState("page text redo") { it.blocks.singleOrNull() != null }
            assertEquals("First paragraph\n\nSecond paragraph", restored.blocks.single().text)
        } finally {
            repository.deleteNotebook(notebookId)
        }
    }

    @Test
    fun closeFlushTreatsDeletedDraftPageAsSuccessfulNoOp() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("Deleted draft page"))
        try {
            val deletedPage = repository.getPages(notebookId).single()
            repository.addPage(notebookId)
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            viewModel.awaitState("two pages load") { it.pages.size == 2 }
            repository.deletePage(deletedPage.id)
            val completed = CompletableDeferred<Boolean>()

            onMain {
                viewModel.flushPageTextBeforeClose(deletedPage.id, "Stale draft", completed::complete)
            }

            assertEquals(true, withTimeoutOrNull(TIMEOUT_MS) { completed.await() })
            assertTrue(repository.getBlocks(deletedPage.id).isEmpty())
            assertFalse(viewModel.state.value.failed)
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
        const val TIMEOUT_MS = 30_000L
    }
}
