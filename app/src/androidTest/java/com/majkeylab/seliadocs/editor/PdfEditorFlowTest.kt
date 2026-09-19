package com.majkeylab.seliadocs.editor

import android.app.Application
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.PageMode
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfEditorFlowTest {
    @Test
    fun notePagesAndAdditionalSlidesFollowTheSelectedPage() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val book = repository.createNotebook(request())
        val first = repository.getPages(book).single().id
        val last = repository.addPage(book)
        val source = File(application.cacheDir, "insert-slides-${System.nanoTime()}.pdf")
        createPdf(source)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        try {
            lateinit var editor: EditorViewModel
            onMain {
                editor = ViewModelProvider(owner, viewModelFactory {
                    initializer { EditorViewModel(application, book) }
                })[EditorViewModel::class.java]
            }
            editor.await("initial page") { it.selectedPage?.id == first }
            onMain { editor.importPdf(Uri.fromFile(source)) }
            val imported = editor.await("first slide") { it.pages.size == 3 && it.selectedPage?.pageMode == PageMode.PDF.name }
            val slide = requireNotNull(imported.selectedPage)
            assertEquals(listOf(first, slide.id, last), imported.pages.map { it.id })

            val existingPageIds = imported.pages.mapTo(mutableSetOf()) { it.id }
            onMain { editor.addPage() }
            val withNote = editor.await("selected new note after slide") {
                it.pages.size == 4 && it.selectedPage?.let { page ->
                    page.pageMode == PageMode.PAPER.name && page.id !in existingPageIds
                } == true
            }
            val note = requireNotNull(withNote.selectedPage)
            assertEquals(listOf(first, slide.id, note.id, last), withNote.pages.map { it.id })
            assertEquals(slide.widthPoints, note.widthPoints)
            assertEquals(slide.heightPoints, note.heightPoints)

            onMain { editor.selectPage(slide.id) }
            editor.await("selected slide") { it.selectedPage?.id == slide.id }
            onMain { editor.importPdf(Uri.fromFile(source)) }
            val withMoreSlides = editor.await("additional slides") {
                it.pages.size == 5 && it.selectedPage?.let { page -> page.pageMode == PageMode.PDF.name && page.id != slide.id } == true
            }
            assertEquals(listOf(first, slide.id, withMoreSlides.selectedPage!!.id, note.id, last), withMoreSlides.pages.map { it.id })
        } finally {
            onMain { owner.viewModelStore.clear() }
            val assets = repository.getPdfSources(book).map { it.assetId }
            repository.deleteNotebook(book)
            val store = AssetStore(File(application.filesDir, "assets"))
            assets.forEach { store.file(it).delete() }
            source.delete()
        }
    }

    @Test
    fun importedPdfPageAcceptsInkAndSurvivesEditorReopen() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val repository = SeliaDocsRepository(SeliaDocsDatabase.get(application))
        val notebookId = repository.createNotebook(request())
        val source = File(application.cacheDir, "editor-${System.nanoTime()}.pdf")
        createPdf(source)
        val assetStore = AssetStore(File(application.filesDir, "assets"))
        var importedAsset: String? = null
        try {
            lateinit var viewModel: EditorViewModel
            onMain { viewModel = EditorViewModel(application, notebookId) }
            viewModel.await("initial page") { it.selectedPage != null }
            onMain { viewModel.importPdf(Uri.fromFile(source)) }
            val imported =
                viewModel.await("PDF import") {
                    it.pages.size == 2 && it.selectedPage?.pageMode == PageMode.PDF.name && it.pdfSources.size == 1
                }
            val pdfPageId = imported.selectedPage!!.id
            importedAsset = imported.pdfSources.single().assetId
            val bitmap = viewModel.renderPdfPage(pdfPageId, 200, 280)
            assertEquals(200, bitmap?.width)
            assertEquals(280, bitmap?.height)

            onMain { viewModel.addStroke(pdfPageId, testStroke()) }
            viewModel.await("PDF annotation") { it.selectedPage?.id == pdfPageId && it.strokes.size == 1 }

            lateinit var reopened: EditorViewModel
            onMain { reopened = EditorViewModel(application, notebookId) }
            reopened.await("reopened pages") { it.pages.size == 2 }
            onMain { reopened.selectPage(pdfPageId) }
            reopened.await("reopened annotation") {
                it.selectedPage?.id == pdfPageId && it.strokes.size == 1
            }
            Unit
        } finally {
            repository.deleteNotebook(notebookId)
            importedAsset?.let { assetStore.file(it).delete() }
            source.delete()
        }
    }

    private fun testStroke(): Stroke =
        Stroke(
            InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 4f),
            MutableStrokeInputBatch()
                .add(InputToolType.STYLUS, 30f, 40f, 0L, 0.01f, 0.7f, 0.2f, 0.3f)
                .add(InputToolType.STYLUS, 160f, 180f, 16L, 0.01f, 0.7f, 0.2f, 0.3f),
        )

    private fun createPdf(file: File) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            page.canvas.drawRect(40f, 50f, 300f, 180f, Paint().apply { color = Color.BLACK })
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private suspend fun EditorViewModel.await(
        label: String,
        predicate: (EditorUiState) -> Boolean,
    ): EditorUiState =
        withTimeoutOrNull(30_000) { state.first(predicate) }
            ?: throw AssertionError("Timed out waiting for $label")

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun request() =
        CreateNotebookRequest(
            "PDF editor ${System.nanoTime()}",
            CoverColor.PERIWINKLE,
            CoverPattern.SOLID,
            PaperTemplate.RULED,
            PageOrientation.PORTRAIT,
            false,
        )
}
