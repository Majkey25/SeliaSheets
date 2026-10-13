package com.majkeylab.seliadocs.documents

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.majkeylab.seliadocs.MainActivity
import com.majkeylab.seliadocs.backup.BackupElement
import com.majkeylab.seliadocs.backup.BackupExporter
import com.majkeylab.seliadocs.backup.BackupJson
import com.majkeylab.seliadocs.backup.BackupPage
import com.majkeylab.seliadocs.backup.BackupPdfSource
import com.majkeylab.seliadocs.backup.BackupRecord
import com.majkeylab.seliadocs.backup.BackupScope
import com.majkeylab.seliadocs.backup.BackupStroke
import com.majkeylab.seliadocs.backup.BackupValidator
import com.majkeylab.seliadocs.backup.validTestStrokePayload
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.ElementDraft
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.PageMode
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.pdf.PdfImporter
import com.majkeylab.seliadocs.pdf.PdfSandboxClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PowerPointImportFlowTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private lateinit var application: Application
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var database: SeliaDocsDatabase
    private lateinit var repository: SeliaDocsRepository
    private lateinit var assets: AssetStore
    private lateinit var sandbox: PdfSandboxClient
    private lateinit var renderHost: ViewGroup
    private lateinit var initialHostChildren: List<View>

    @Before
    fun setUp() {
        activityRule.scenario.onActivity { activity ->
            renderHost = activity.findViewById<ViewGroup>(android.R.id.content)
            assertTrue(renderHost.isAttachedToWindow)
            initialHostChildren = (0 until renderHost.childCount).map(renderHost::getChildAt)
        }
        application = ApplicationProvider.getApplicationContext()
        directory = File(application.cacheDir, "presentation-flow-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(application) {
            override fun getCacheDir(): File = directory
        }
        database = Room.inMemoryDatabaseBuilder(application, SeliaDocsDatabase::class.java).build()
        repository = SeliaDocsRepository(database)
        assets = AssetStore(File(directory, "assets"))
        sandbox = PdfSandboxClient(application)
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun slidesInsertAfterChapterAnchorAndKeepFollowingPageAndOriginal() = runBlocking {
        val notebook = createNotebook()
        val anchor = repository.getPages(notebook).single()
        val chapter = repository.createChapter(notebook, "Lecture", Color.BLUE)
        repository.assignPageToChapter(anchor.id, chapter)
        val followingId = repository.addPage(notebook, anchor.id)
        repository.updatePageText(followingId, "Following notes stay here")
        val following = requireNotNull(repository.getPage(followingId))
        val original = presentation()
        val originalBytes = original.readBytes()

        val imported = LibraryMutationGate.withLock {
            importer().import(notebook, Uri.fromFile(original), anchor.id, renderHost).single()
        }

        val pages = repository.getPages(notebook)
        assertEquals(listOf(anchor.id) + imported.pageIds + followingId, pages.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), pages.map { it.pageIndex })
        assertEquals(following.copy(pageIndex = 3), pages.last())
        assertEquals("Following notes stay here", repository.getBlocks(followingId).single().text)
        val slides = pages.filter { it.id in imported.pageIds }
        assertEquals(2, imported.pageCount)
        assertEquals(listOf(0, 1), slides.map { it.pdfPageIndex })
        assertTrue(slides.all {
            it.pdfSourceId == imported.sourceId && it.pageMode == PageMode.PDF.name &&
                it.chapterId == chapter && it.widthPoints == 720 && it.heightPoints == 405
        })
        val pdf = repository.getPdfSources(notebook).single()
        val installed = assets.requireFile(pdf.assetId)
        assertEquals(2, sandbox.inspect(installed).pages.size)
        for (index in 0..1) {
            val bitmap = sandbox.renderPage(installed, index, 960, 540)
            try {
                val pixel = bitmap.getPixel(150, 170)
                if (index == 0) assertTrue("First slide lost its red shape", Color.red(pixel) > 200 && Color.blue(pixel) < 50)
                else assertTrue("Second slide lost its blue shape", Color.blue(pixel) > 200 && Color.red(pixel) < 50)
            } finally {
                bitmap.recycle()
            }
        }
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals(setOf(pdf.assetId), repository.getReferencedAssetIds())
        assertImportCleanedUp()
    }

    @Test
    fun textAndInkRemainEditableAndBackupIncludesConvertedSource() = runBlocking {
        val notebook = createNotebook()
        val anchor = repository.getPages(notebook).single()
        val imported = LibraryMutationGate.withLock {
            importer().import(notebook, Uri.fromFile(presentation()), anchor.id, renderHost).single()
        }
        val pageId = imported.pageIds.first()
        val text = "Poznámka ke snímku"
        val elementId = repository.addElement(pageId, ElementDraft(ElementKind.TEXT, 40f, 250f, 250f, 60f, text = text))
        val strokeId = repository.addStroke(pageId, validTestStrokePayload())
        val reloaded = SeliaDocsRepository(database).loadNotebook(notebook)
        assertEquals(text, reloaded.elements.single { it.id == elementId }.text)
        assertEquals(pageId, reloaded.strokes.single { it.id == strokeId }.pageId)
        assertEquals(imported.sourceId, reloaded.pages.single { it.id == pageId }.pdfSourceId)
        val source = reloaded.pdfSources.single()
        val sourceBytes = assets.requireFile(source.assetId).readBytes()
        val output = ByteArrayOutputStream()

        LibraryMutationGate.withLock {
            BackupExporter(repository, assets, "test").export(BackupScope.Notebook(notebook), output)
        }
        val validator = BackupValidator(File(directory, "backup-validation"), sandbox::inspect)
        validator.validate(output.toByteArray().inputStream()).use { backup ->
            assertTrue("pdf-sources" in backup.manifest.featureFlags)
            assertEquals(setOf(source.assetId), backup.index.assetIds)
            assertArrayEquals(sourceBytes, backup.assetFiles.getValue(source.assetId).readBytes())
            val records = mutableListOf<BackupRecord>()
            backup.recordsFile.reader().use { BackupJson.readRecords(it, records::add) }
            assertEquals(imported.sourceId, records.filterIsInstance<BackupPdfSource>().single().id)
            assertEquals(listOf(0, 1), records.filterIsInstance<BackupPage>().filter { it.pdfSourceId != null }.map { it.pdfPageIndex })
            assertEquals(text, records.filterIsInstance<BackupElement>().single().text)
            assertEquals(strokeId, records.filterIsInstance<BackupStroke>().single().id)
        }
        assertImportCleanedUp()
    }

    @Test
    fun corruptPresentationLeavesNotebookAssetsAndCacheUnchanged() = runBlocking {
        val notebook = createNotebook()
        val before = repository.loadNotebook(notebook)
        val source = File(directory, "corrupt.pptx").apply { writeText("not a presentation") }

        val failure = runCatching {
            LibraryMutationGate.withLock { importer().import(notebook, Uri.fromFile(source), before.pages.single().id, renderHost) }
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(before, repository.loadNotebook(notebook))
        assertTrue(assets.files().isEmpty())
        assertEquals("not a presentation", source.readText())
        assertImportCleanedUp()
    }

    @Test
    fun cancellationDuringStagingRemovesCopiesWithoutSavingPages() = runBlocking {
        val notebook = createNotebook()
        val before = repository.loadNotebook(notebook)
        val original = presentation()
        val originalBytes = original.readBytes()
        val importJob = Job()
        val cancelled = AtomicBoolean()
        val cancellingContext = object : ContextWrapper(application) {
            override fun getCacheDir(): File {
                cancelled.set(true)
                importJob.cancel()
                return directory
            }
        }
        try {
            val importing = CoroutineScope(Dispatchers.IO + importJob).async {
                LibraryMutationGate.withLock {
                    importer(cancellingContext).import(notebook, Uri.fromFile(original), before.pages.single().id, renderHost)
                }
            }
            val failure = runCatching { importing.await() }.exceptionOrNull()
            importing.join()

            assertTrue("Import must reach private staging before cancellation", cancelled.get())
            assertTrue(failure is CancellationException)
            assertEquals(before, repository.loadNotebook(notebook))
            assertTrue(assets.files().isEmpty())
            assertArrayEquals(originalBytes, original.readBytes())
            assertImportCleanedUp()
        } finally {
            importJob.cancel()
        }
    }

    private fun importer(importContext: Context = context) = PowerPointImporter(
        importContext,
        PdfImporter(application.contentResolver, assets, repository, sandbox),
    )

    private fun presentation() = File(directory, "lecture.pptx").also(::createPresentationFixture)

    private suspend fun createNotebook() = repository.createNotebook(
        CreateNotebookRequest("Lecture", CoverColor.SAGE, CoverPattern.SOLID, PaperTemplate.RULED, PageOrientation.PORTRAIT, false),
    )

    private fun assertImportCleanedUp() {
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("presentation-import-") })
        assertTrue(assets.files().none { it.name.startsWith(".pdf-import-") })
        activityRule.scenario.onActivity {
            assertEquals(initialHostChildren, (0 until renderHost.childCount).map(renderHost::getChildAt))
        }
    }
}
