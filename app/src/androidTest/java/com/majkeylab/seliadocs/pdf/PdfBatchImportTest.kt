package com.majkeylab.seliadocs.pdf

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.backup.testPdf
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.MAX_PDF_IMPORT_BYTES
import com.majkeylab.seliadocs.data.PageMode
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.PdfImportSpec
import com.majkeylab.seliadocs.data.PdfPageSpec
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfBatchImportTest {
    private lateinit var application: Application
    private lateinit var database: SeliaDocsDatabase
    private lateinit var repository: SeliaDocsRepository
    private lateinit var directory: File
    private lateinit var assets: AssetStore
    private lateinit var sandbox: PdfSandboxClient
    private val bindings = AtomicInteger()
    private val unbindings = AtomicInteger()
    private var nextId = 0
    private var failAtId: Int? = null

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        directory = File(application.cacheDir, "pdf-batch-${System.nanoTime()}").apply { mkdirs() }
        assets = AssetStore(File(directory, "assets"))
        database = Room.inMemoryDatabaseBuilder(application, SeliaDocsDatabase::class.java).build()
        repository = SeliaDocsRepository(database, clock = { 1_000L }, idFactory = {
            if (nextId == failAtId) throw IOException("Injected batch insertion failure")
            "batch-${nextId++}"
        })
        sandbox = PdfSandboxClient(object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
                bindings.incrementAndGet()
                return super.bindService(service, connection, flags)
            }
            override fun unbindService(connection: ServiceConnection) {
                unbindings.incrementAndGet()
                super.unbindService(connection)
            }
        })
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun multipleSourcesKeepPageOrderChapterAndBytesWithOneSandboxBinding() = runBlocking {
        val notebook = repository.createNotebook(request())
        val anchor = repository.getPages(notebook).single()
        val chapter = repository.createChapter(notebook, "Lecture", 0xFF123456.toInt())
        repository.assignPageToChapter(anchor.id, chapter)
        val following = repository.addPage(notebook, anchor.id)
        repository.updatePageText(following, "Following notes")
        val originals = listOf(source("first.pdf", 1), source("second.pdf", 2))

        val results = importer().importMany(notebook, originals.map(Uri::fromFile), anchor.id, "Lecture.pdf")

        assertEquals(1, bindings.get())
        assertEquals(1, unbindings.get())
        assertEquals(listOf(1, 2), results.map { it.pageCount })
        val pages = repository.getPages(notebook)
        assertEquals(listOf(anchor.id) + results.flatMap { it.pageIds } + following, pages.map { it.id })
        assertEquals(listOf(0, 1, 2, 3, 4), pages.map { it.pageIndex })
        assertEquals("Following notes", repository.getBlocks(following).single().text)
        results.forEachIndexed { index, imported ->
            val pdf = repository.getPdfSource(imported.sourceId)
            val importedPages = pages.filter { it.pdfSourceId == pdf.id }
            assertEquals((0 until imported.pageCount).toList(), importedPages.map { it.pdfPageIndex })
            assertTrue(importedPages.all {
                it.chapterId == chapter && it.pageMode == PageMode.PDF.name && it.title == "Lecture" &&
                    it.widthPoints == 595 && it.heightPoints == 842
            })
            assertEquals("Lecture.pdf", pdf.displayName)
            assertArrayEquals(originals[index].readBytes(), assets.requireFile(pdf.assetId).readBytes())
        }
        assertEquals(2, repository.getPdfSources(notebook).size)
        assertEquals(repository.getReferencedAssetIds(), assets.files().map { it.name }.toSet())
    }

    @Test
    fun malformedSecondPdfRemovesAllStagingWithoutChangingNotebook() = runBlocking {
        val notebook = repository.createNotebook(request())
        val anchor = repository.getPages(notebook).single()
        repository.addPage(notebook, anchor.id)
        val before = repository.loadNotebook(notebook)
        val first = source("first.pdf", 1)
        val second = File(directory, "bad.pdf").apply { writeText("%PDF-not a valid PDF") }

        val failure = runCatching {
            importer().importMany(notebook, listOf(Uri.fromFile(first), Uri.fromFile(second)), anchor.id)
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(1, bindings.get())
        assertEquals(1, unbindings.get())
        assertEquals(before, repository.loadNotebook(notebook))
        assertTrue(assets.files().isEmpty())
    }

    @Test
    fun secondSourceDatabaseFailureRollsBackFirstSourceAndShiftedPages() = runBlocking {
        val notebook = repository.createNotebook(request())
        val anchor = repository.getPages(notebook).single()
        repository.addPage(notebook, anchor.id)
        val before = repository.loadNotebook(notebook)
        val sources = listOf(source("first.pdf", 1), source("second.pdf", 1))
        // First source + its page consume two IDs, then fail after their inserts.
        failAtId = nextId + 2

        val failure = runCatching {
            importer().importMany(notebook, sources.map(Uri::fromFile), anchor.id)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("Injected batch insertion failure", failure?.message)
        assertEquals(before, repository.loadNotebook(notebook))
        assertTrue(assets.files().isEmpty())
    }

    @Test
    fun cancellationAfterOuterCommitKeepsEveryReferencedAsset() = runBlocking {
        database.close()
        val importJob = Job()
        val cancelledAfterCommit = AtomicBoolean()
        database = Room.inMemoryDatabaseBuilder(application, SeliaDocsDatabase::class.java)
            .openHelperFactory(object : SupportSQLiteOpenHelper.Factory {
                override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                    val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
                    fun monitor(delegate: SupportSQLiteDatabase): SupportSQLiteDatabase =
                        object : SupportSQLiteDatabase by delegate {
                            override fun endTransaction() {
                                delegate.endTransaction()
                                if (!delegate.inTransaction() && delegate.query("SELECT COUNT(*) FROM pdf_sources").use {
                                        it.moveToFirst() && it.getInt(0) == 2
                                    } && cancelledAfterCommit.compareAndSet(false, true)) {
                                    importJob.cancel()
                                }
                            }
                        }
                    return object : SupportSQLiteOpenHelper by helper {
                        override val writableDatabase: SupportSQLiteDatabase get() = monitor(helper.writableDatabase)
                        override val readableDatabase: SupportSQLiteDatabase get() = monitor(helper.readableDatabase)
                    }
                }
            }).build()
        repository = SeliaDocsRepository(database)
        val notebook = repository.createNotebook(request())
        val anchor = repository.getPages(notebook).single()
        val sources = listOf(source("first.pdf", 1), source("second.pdf", 2))
        try {
            val importing = CoroutineScope(Dispatchers.IO + importJob).async {
                importer().importMany(notebook, sources.map(Uri::fromFile), anchor.id)
            }
            val failure = runCatching { importing.await() }.exceptionOrNull()
            importing.join()

            assertTrue("Cancellation must follow the outer SQLite commit", cancelledAfterCommit.get())
            assertTrue(failure is CancellationException)
            val pdfs = repository.getPdfSources(notebook)
            assertEquals(2, pdfs.size)
            assertEquals(listOf(0, 1, 2, 3), repository.getPages(notebook).map { it.pageIndex })
            assertEquals(pdfs.map { it.assetId }.toSet(), assets.files().map { it.name }.toSet())
            val inspected = sandbox.inspectMany(pdfs.map { assets.requireFile(it.assetId) })
            assertEquals(listOf(1, 2), inspected.map { it.pages.size }.sorted())
        } finally {
            importJob.cancel()
        }
    }

    @Test
    fun batchFilePageAndByteLimitsRejectBeforeDatabaseMutation() = runBlocking {
        val notebook = repository.createNotebook(request())
        val before = repository.loadNotebook(notebook)
        val source = Uri.fromFile(source("source.pdf", 1))
        for (uris in listOf(emptyList(), List(101) { source })) {
            assertTrue(runCatching { importer().importMany(notebook, uris) }.isFailure)
            assertEquals(before, repository.loadNotebook(notebook))
            assertTrue(assets.files().isEmpty())
        }
        assertEquals(0, bindings.get())
        val first = PdfImportSpec("first.pdf", "First.pdf", 1L, "0".repeat(64), listOf(PdfPageSpec(595, 842)))
        val second = first.copy(assetId = "second.pdf")
        val batches = listOf(
            listOf(first.copy(pages = List(2_000) { PdfPageSpec(595, 842) }), second),
            listOf(first.copy(byteSize = MAX_PDF_IMPORT_BYTES), second),
        )
        for (batch in batches) {
            assertTrue(runCatching { repository.importPdfs(notebook, batch) }.isFailure)
            assertEquals(before, repository.loadNotebook(notebook))
        }
    }

    private fun importer() = PdfImporter(application.contentResolver, assets, repository, sandbox)

    private fun source(name: String, pages: Int): File = File(directory, name).apply { writeBytes(testPdf(pages)) }

    private fun request() = CreateNotebookRequest(
        "PDF batch", CoverColor.SAGE, CoverPattern.SOLID, PaperTemplate.RULED, PageOrientation.PORTRAIT, false,
    )
}
