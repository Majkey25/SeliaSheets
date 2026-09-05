package com.majkeylab.seliadocs.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.AssetStore
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
import com.majkeylab.seliadocs.library.LibraryViewModel
import com.majkeylab.seliadocs.recognition.ImageOcrResult
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElementFlowTest {
    @Test
    fun importedImageTextIsSearchable(): Unit = runBlocking {
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

            val completed = CompletableDeferred<Boolean>()
            onMain { viewModel.importImage(page.id, Uri.fromFile(source), onComplete = completed::complete) }
            val imported = viewModel.awaitState("OCR image import") {
                it.elements.singleOrNull()?.ocrRegions.isNullOrBlank().not()
            }
            importedAssetId = imported.elements.single().assetId
            assertEquals(true, withTimeoutOrNull(TIMEOUT_MS) { completed.await() })

            val match = repository.searchPageText(notebookId, "organic chemistry").single()
            assertEquals(page.id, match.pageId)
            assertEquals(imported.elements.single().id, match.elementId)
            assertFalse(imported.elements.single().ocrRegions.isNullOrBlank())
            assertTrue(
                repository.searchPageText(
                    notebookId,
                    "organic chemistry",
                    includeImageOcr = false,
                ).isEmpty(),
            )
            repository.updateElement(imported.elements.single().copy(ocrRegions = "broken"))
            viewModel.awaitState("legacy OCR metadata") { it.elements.single().ocrRegions == "broken" }
            onMain { viewModel.searchPageText("organic chemistry") }
            val searchState = viewModel.awaitState("OCR search result") { it.searchResults.isNotEmpty() }
            onMain { viewModel.openSearchResult(searchState.searchResults.single()) }
            val highlighted = viewModel.awaitState("OCR search highlight") { it.ocrSearchHighlight != null }
            assertEquals(imported.elements.single().id, highlighted.ocrSearchHighlight?.elementId)
            viewModel.awaitState("OCR region regeneration") { !it.elements.single().ocrRegions.isNullOrBlank() }
        } finally {
            repository.deleteNotebook(notebookId)
            importedAssetId?.let { AssetStore(File(application.filesDir, "assets")).file(it).delete() }
            source.delete()
        }
    }

    @Test
    fun gatedImportFailureKeepsDraftInputBlockedUntilQueuedBackCanRun() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("Import completion gate"))
        val holder = EditorSessionHolder()
        holder.prepare("0:$notebookId")
        val factory = viewModelFactory {
            initializer { EditorViewModel(application, notebookId) }
        }
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = ViewModelProvider(holder, factory)[EditorViewModel::class.java] }
            val pageId = viewModel.awaitState("import page load") { it.selectedPage != null }.selectedPage!!.id
            val missing = Uri.fromFile(File(application.cacheDir, "missing-import-${System.nanoTime()}"))
            listOf(EditorAction.ImportPdf(missing), EditorAction.ImportImage(pageId, missing, ocr = false))
                .forEach { action ->
                    holder.requestAction(action)
                    val epoch = requireNotNull(holder.beginActionSave())
                    holder.completeActionSave(epoch, true)
                    assertEquals(action, holder.takeReadyAction())
                    val acquired = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val completed = CompletableDeferred<Boolean>()
                    val gate = launch(Dispatchers.IO) {
                        LibraryMutationGate.withLock {
                            acquired.complete(Unit)
                            release.await()
                        }
                    }
                    try {
                        assertEquals(Unit, withTimeoutOrNull(TIMEOUT_MS) { acquired.await() })
                        val onComplete: (Boolean) -> Unit = { success ->
                            holder.completeExecutingAction(epoch, action)
                            completed.complete(success)
                        }
                        onMain {
                            when (action) {
                                is EditorAction.ImportPdf -> viewModel.importPdf(action.uri, onComplete)
                                is EditorAction.ImportImage ->
                                    viewModel.importImage(action.pageId, action.uri, action.ocr, onComplete)
                                else -> error("Expected an import action")
                            }
                        }
                        holder.prepare("0:$notebookId")
                        holder.requestAction(EditorAction.Close(EditorCloseIntent.BACK))
                        assertFalse(completed.isCompleted)
                        assertTrue(holder.actionState.value.busy)
                        assertFalse(holder.acceptDraft(pageId, androidx.compose.ui.text.input.TextFieldValue("Rejected")))
                        assertFalse(holder.beginInlineText(InlineTextDraft(pageId, null, CanvasPoint(20f, 30f), "Rejected")))
                        assertNull(holder.beginActionSave())
                        release.complete(Unit)
                        assertEquals(false, withTimeoutOrNull(TIMEOUT_MS) { completed.await() })
                        assertNull(holder.actionState.value.executing)
                        assertEquals(epoch, holder.beginActionSave())
                        holder.completeActionSave(epoch, true)
                        assertEquals(EditorAction.Close(EditorCloseIntent.BACK), holder.takeReadyAction())
                        assertTrue(repository.getElements(pageId).isEmpty())
                    } finally {
                        release.complete(Unit)
                        gate.join()
                    }
                }
        } finally {
            onMain { holder.viewModelStore.clear() }
            repository.deleteNotebook(notebookId)
        }
    }

    @Test
    fun heldImageOcrDoesNotBlockTextSaveOrClose() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("Held image OCR"))
        val source = File(application.cacheDir, "held-ocr-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        val ocrStarted = CompletableDeferred<Unit>()
        val releaseOcr = CompletableDeferred<Unit>()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val factory = viewModelFactory {
            initializer {
                EditorViewModel(
                    application,
                    notebookId,
                    imageOcrRecognizer = {
                        ocrStarted.complete(Unit)
                        releaseOcr.await()
                        ImageOcrResult("HELD OCR", emptyList())
                    },
                )
            }
        }
        var assetId: String? = null
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = ViewModelProvider(owner, factory)[EditorViewModel::class.java] }
            val pageId = viewModel.awaitState("held OCR page") { it.selectedPage != null }.selectedPage!!.id
            onMain { viewModel.importImage(pageId, Uri.fromFile(source)) }
            assertEquals(Unit, withTimeoutOrNull(TIMEOUT_MS) { ocrStarted.await() })
            assetId = repository.getElements(pageId).single().assetId

            onMain { viewModel.updatePageText(pageId, "Saved during OCR") }
            viewModel.awaitState("text save during OCR") { it.blocks.singleOrNull()?.text == "Saved during OCR" }
            val closed = CompletableDeferred<Boolean>()
            onMain { viewModel.flushPageTextBeforeClose(pageId, "Final draft", closed::complete) }
            assertEquals(true, withTimeoutOrNull(TIMEOUT_MS) { closed.await() })
            assertEquals("Final draft", repository.getBlocks(pageId).single().text)
            assertFalse(releaseOcr.isCompleted)

            releaseOcr.complete(Unit)
            viewModel.awaitState("late OCR indexing") { it.elements.singleOrNull()?.text == "HELD OCR" }
            assertEquals("Final draft", repository.getBlocks(pageId).single().text)
            assertEquals(1, repository.searchPageText(notebookId, "HELD OCR").size)

            onMain(viewModel::undo)
            viewModel.awaitState("undo draft after OCR") { it.blocks.singleOrNull()?.text == "Saved during OCR" }
            onMain(viewModel::undo)
            viewModel.awaitState("undo text after OCR") { it.blocks.isEmpty() }
            onMain(viewModel::undo)
            viewModel.awaitState("undo indexed image") { it.elements.isEmpty() }
            assertTrue(repository.searchPageText(notebookId, "HELD OCR").isEmpty())
            onMain(viewModel::redo)
            viewModel.awaitState("redo indexed image") { it.elements.singleOrNull()?.text == "HELD OCR" }
            assertEquals(1, repository.searchPageText(notebookId, "HELD OCR").size)
        } finally {
            releaseOcr.complete(Unit)
            onMain(owner.viewModelStore::clear)
            repository.deleteNotebook(notebookId)
            assetId?.let { AssetStore(File(application.filesDir, "assets")).file(it).delete() }
            source.delete()
        }
    }

    @Test
    fun failedImageOcrCanBeRetriedWithoutDuplicateRecognition() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("Image OCR retry"))
        val assets = AssetStore(File(application.filesDir, "assets"))
        val assetId = "retry-${System.nanoTime()}.png"
        val assetFile = assets.prepare().let { assets.file(assetId) }
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        try {
            assetFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        val retryStarted = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        var attempts = 0
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val factory = viewModelFactory {
            initializer {
                EditorViewModel(application, notebookId, imageOcrRecognizer = {
                    attempts++
                    if (attempts == 1) error("Recognition unavailable")
                    retryStarted.complete(Unit)
                    releaseRetry.await()
                    ImageOcrResult("RECOVERED IMAGE TEXT", emptyList())
                })
            }
        }
        try {
            val pageId = repository.getPages(notebookId).single().id
            val elementId = repository.addElement(
                pageId,
                ElementDraft(ElementKind.IMAGE, 20f, 20f, 200f, 100f, assetId = assetId),
            )
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = ViewModelProvider(owner, factory)[EditorViewModel::class.java] }
            viewModel.awaitState("retry image loaded") { it.elements.singleOrNull()?.id == elementId }
            onMain { viewModel.selectElement(elementId) }
            viewModel.awaitState("retry image selected") { it.selectedElementId == elementId }
            onMain(viewModel::recognizeSelectedImage)
            viewModel.awaitState("OCR failure feedback") {
                it.recognitionMessage == application.getString(R.string.image_ocr_failed)
            }
            assertTrue(repository.searchPageText(notebookId, "RECOVERED").isEmpty())

            onMain {
                viewModel.recognizeSelectedImage()
                viewModel.recognizeSelectedImage()
            }
            assertEquals(Unit, withTimeoutOrNull(TIMEOUT_MS) { retryStarted.await() })
            assertEquals(2, attempts)
            releaseRetry.complete(Unit)
            viewModel.awaitState("OCR retry indexed") {
                it.elements.singleOrNull()?.text == "RECOVERED IMAGE TEXT" &&
                    it.recognitionMessage == application.getString(R.string.image_ocr_ready)
            }
            assertEquals(elementId, repository.searchPageText(notebookId, "RECOVERED").single().elementId)
        } finally {
            releaseRetry.complete(Unit)
            onMain(owner.viewModelStore::clear)
            repository.deleteNotebook(notebookId)
            assetFile.delete()
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
    fun orphanedImageAssetIsRemovedAtNextLibraryStartupAfterEditorHistoryClears() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("Image cleanup"))
        val assetStore = AssetStore(File(application.filesDir, "assets"))
        val assetId = "cleanup-${System.nanoTime()}.png"
        val assetFile = assetStore.prepare().let { assetStore.file(assetId) }
        val owner =
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        val factory = viewModelFactory { initializer { EditorViewModel(application, notebookId) } }
        var library: LibraryViewModel? = null
        try {
            val pageId = repository.getPages(notebookId).single().id
            val elementId =
                LibraryMutationGate.withLock {
                    assetFile.writeBytes(byteArrayOf(1, 2, 3))
                    repository.addElement(
                        pageId,
                        ElementDraft(ElementKind.IMAGE, 20f, 20f, 200f, 100f, assetId = assetId),
                    )
                }
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = ViewModelProvider(owner, factory)[EditorViewModel::class.java] }
            viewModel.awaitState("image load") { it.elements.singleOrNull()?.id == elementId }

            onMain {
                viewModel.selectElement(elementId)
                viewModel.deleteSelectedElement()
            }
            viewModel.awaitState("image delete") { it.elements.isEmpty() && it.canUndo }
            assertTrue(assetFile.isFile)

            onMain(owner.viewModelStore::clear)
            val deletedWhenEditorCleared =
                withTimeoutOrNull(1_000) {
                    while (assetFile.exists()) delay(10)
                    true
                }
            assertNull(deletedWhenEditorCleared)
            library = LibraryViewModel(application, repository, assetStore)

            val removed =
                withTimeoutOrNull(TIMEOUT_MS) {
                    while (assetFile.exists()) delay(10)
                    true
                }
            assertEquals(true, removed)
        } finally {
            onMain(owner.viewModelStore::clear)
            library?.viewModelScope?.cancel()
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

    @Test
    fun nonFittingTextAddReportsFailureWithoutCreatingAnElement() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(testNotebook("Non-fitting text"))
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            val page = viewModel.awaitState("page load") { it.selectedPage != null }.selectedPage!!
            val completed = CompletableDeferred<Boolean>()

            onMain {
                viewModel.addText(
                    page.id,
                    "A".repeat(200),
                    CanvasPoint(120f, 790f),
                    completed::complete,
                )
            }

            assertEquals(false, withTimeoutOrNull(TIMEOUT_MS) { completed.await() })
            assertTrue(repository.getElements(page.id).isEmpty())
            assertTrue(viewModel.state.value.failed)
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
