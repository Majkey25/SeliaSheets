package com.majkeylab.seliadocs.pdf

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfImportTest {
    private lateinit var application: Application
    private lateinit var database: SeliaDocsDatabase
    private lateinit var repository: SeliaDocsRepository
    private lateinit var root: File
    private lateinit var assets: AssetStore
    private lateinit var sandbox: PdfSandboxClient

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(application, SeliaDocsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        var nextId = 0
        repository = SeliaDocsRepository(database, clock = { 1_000L }, idFactory = { "id-${nextId++}" })
        root = File(application.cacheDir, "pdf-import-${System.nanoTime()}")
        assets = AssetStore(root)
        sandbox = PdfSandboxClient(application)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun validPdfCreatesBackedPagesAndRendersInSandbox() = runBlocking {
        val notebookId = repository.createNotebook(request())
        val source = File(application.cacheDir, "source-${System.nanoTime()}.pdf")
        createPdf(source)
        try {
            val importer = PdfImporter(application.contentResolver, assets, repository, sandbox, idFactory = { "asset" })

            val imported = importer.import(notebookId, Uri.fromFile(source))

            assertEquals(2, imported.pageCount)
            val pdfSource = repository.getPdfSources(notebookId).single()
            val pages = repository.getPages(notebookId).filter { it.pdfSourceId == pdfSource.id }
            assertEquals(listOf(0, 1), pages.map { it.pdfPageIndex })
            assertTrue(pages.all { it.pageMode == PageMode.PDF.name })
            val installed = assets.requireFile(pdfSource.assetId)
            val inspected = sandbox.inspect(installed)
            assertTrue(inspected.sandboxUid != application.applicationInfo.uid)
            val bitmap = sandbox.renderPage(installed, 0, 240, 320)
            try {
                assertEquals(240, bitmap.width)
                assertEquals(320, bitmap.height)
                assertTrue((0 until bitmap.width step 8).any { x ->
                    (0 until bitmap.height step 8).any { y -> bitmap.getPixel(x, y) != Color.WHITE }
                })
            } finally {
                bitmap.recycle()
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun corruptPdfLeavesNotebookAndAssetsUnchanged() = runBlocking {
        val notebookId = repository.createNotebook(request())
        val source = File(application.cacheDir, "corrupt-${System.nanoTime()}.pdf")
        source.writeText("%PDF-not really a PDF")
        try {
            val importer = PdfImporter(application.contentResolver, assets, repository, sandbox)

            val failure = runCatching { importer.import(notebookId, Uri.fromFile(source)) }.exceptionOrNull()

            assertTrue(failure != null)
            assertTrue(repository.getPdfSources(notebookId).isEmpty())
            assertEquals(1, repository.getPages(notebookId).size)
            assertTrue(assets.files().isEmpty())
        } finally {
            source.delete()
        }
    }

    @Test
    fun bindingDeathFailsPendingClientAndUnbinds() = runBlocking {
        val unbound = AtomicBoolean()
        val context =
            object : ContextWrapper(application) {
                override fun getApplicationContext(): Context = this

                override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
                    connection.onBindingDied(ComponentName(packageName, PdfRenderService::class.java.name))
                    return true
                }

                override fun unbindService(connection: ServiceConnection) {
                    unbound.set(true)
                }
            }

        val failure =
            runCatching {
                PdfSandboxClient(context).inspect(File(application.cacheDir, "unused.pdf"))
            }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("PDF sandbox binding died while connecting", failure?.message)
        assertTrue(unbound.get())
    }

    private fun createPdf(file: File) {
        val document = PdfDocument()
        try {
            listOf(595 to 842, 842 to 595).forEachIndexed { index, (width, height) ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                page.canvas.drawRect(40f, 50f, 300f, 180f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun request() =
        CreateNotebookRequest(
            "PDF notebook",
            CoverColor.PERIWINKLE,
            CoverPattern.SOLID,
            PaperTemplate.RULED,
            PageOrientation.PORTRAIT,
            false,
        )
}
