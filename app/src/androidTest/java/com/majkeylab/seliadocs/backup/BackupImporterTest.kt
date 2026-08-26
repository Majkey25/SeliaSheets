package com.majkeylab.seliadocs.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.CreateNotebookRequest
import com.majkeylab.seliadocs.data.ElementDraft
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.LibraryMutationGate
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.PdfPageSpec
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.library.LibraryViewModel
import com.majkeylab.seliadocs.pdf.PdfSandboxClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupImporterTest {
    private lateinit var context: Context
    private lateinit var database: SeliaDocsDatabase
    private lateinit var repository: SeliaDocsRepository
    private lateinit var assetRoot: File
    private lateinit var assets: AssetStore
    private lateinit var stagingRoot: File
    private lateinit var importer: BackupImporter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = inMemoryDatabase()
        repository = repository(database)
        assetRoot = File(context.cacheDir, "backup-import-assets-${System.nanoTime()}")
        assets = AssetStore(assetRoot)
        stagingRoot = File(context.cacheDir, "backup-import-stage-${System.nanoTime()}")
        var nextRestoreId = 0
        importer =
            BackupImporter(
                database = database,
                repository = repository,
                assets = assets,
                validator = BackupValidator(stagingRoot, PdfSandboxClient(context)::inspect),
                stagingRoot = stagingRoot,
                appVersion = "test",
                idFactory = { "restored-${nextRestoreId++}" },
            )
    }

    @After
    fun tearDown() {
        database.close()
        assetRoot.deleteRecursively()
        stagingRoot.deleteRecursively()
    }

    @Test
    fun mergeRestoresEditableNotebookIntoEmptyLibrary() = runTest {
        val archive = sourceArchive("Imported")

        val summary = importer.restore(ByteArrayInputStream(archive), RestoreMode.MERGE)

        val notebook = repository.getAllNotebooks().single()
        val content = repository.loadNotebook(notebook.id)
        assertEquals("Imported", notebook.title)
        assertArrayEquals(validTestStrokePayload().inputs, content.strokes.single().inputs)
        assertEquals("Imported page text", content.blocks.single().text)
        assertEquals("Main chapter", content.chapters.single().title)
        assertEquals(content.chapters.single().id, content.pages.single().chapterId)
        val assetId = content.elements.single().assetId
        assertArrayEquals(testPng(0xFFFF0000.toInt()), assets.requireFile(requireNotNull(assetId)).readBytes())
        assertEquals(RestoreSummary(1, 1, 1, 0), summary)
    }

    @Test
    fun mergeRemapsCollidingIdsAndPreservesExistingData() = runTest {
        populate(repository, assets, "Existing", testPng(0xFF0000FF.toInt()))
        val archive = sourceArchive("Imported")

        val summary = importer.restore(ByteArrayInputStream(archive), RestoreMode.MERGE)

        val notebooks = repository.getAllNotebooks()
        assertEquals(setOf("Existing", "Imported"), notebooks.mapTo(mutableSetOf()) { it.title })
        assertEquals(2, notebooks.map { it.id }.toSet().size)
        assertEquals(2, assets.files().size)
        val imported = notebooks.single { it.title == "Imported" }
        val importedAsset = repository.loadNotebook(imported.id).elements.single().assetId
        assertTrue(importedAsset != "asset.png")
        assertArrayEquals(testPng(0xFFFF0000.toInt()), assets.requireFile(requireNotNull(importedAsset)).readBytes())
        assertArrayEquals(testPng(0xFF0000FF.toInt()), assets.requireFile("asset.png").readBytes())
        assertTrue(summary.remappedIds >= 3)
    }

    @Test
    fun replaceSwapsLibraryAndRemovesOldAssets() = runTest {
        populate(repository, assets, "Old", testPng(0xFF0000FF.toInt()))
        val archive = sourceArchive("Replacement")

        val summary = importer.restore(ByteArrayInputStream(archive), RestoreMode.REPLACE)

        val notebook = repository.getAllNotebooks().single()
        val assetId = repository.loadNotebook(notebook.id).elements.single().assetId
        assertEquals("Replacement", notebook.title)
        assertEquals(1, assets.files().size)
        assertArrayEquals(testPng(0xFFFF0000.toInt()), assets.requireFile(requireNotNull(assetId)).readBytes())
        assertEquals(1, summary.notebooks)
        assertTrue(stagingRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun replaceWaitsForEditorMutationBeforeReplacingLibrary() = runBlocking {
        val archive = sourceArchive("Replacement")
        val mutationStarted = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val editorMutation =
            launch(Dispatchers.Default) {
                LibraryMutationGate.withLock {
                    mutationStarted.complete(Unit)
                    releaseMutation.await()
                }
            }
        withTimeout(10_000) { mutationStarted.await() }
        val restore =
            async(Dispatchers.IO) {
                importer.restore(ByteArrayInputStream(archive), RestoreMode.REPLACE)
            }

        val completedWhileLocked =
            try {
                withTimeoutOrNull(5_000) {
                    restore.await()
                    true
                } == true
            } finally {
                releaseMutation.complete(Unit)
                withTimeout(10_000) { editorMutation.join() }
            }

        assertFalse("Replacement completed while editor mutation owned library gate", completedWhileLocked)
        withTimeout(10_000) { restore.await() }
        assertEquals("Replacement", repository.getAllNotebooks().single().title)
    }

    @Test
    fun cancellationBeforeAtomicMutationLeavesLibraryUnchanged() = runBlocking {
        repository.createNotebook(request("Old"))
        assets.prepare()
        assets.file("old.bin").writeBytes(byteArrayOf(1))
        val archive = sourceArchive("Replacement")
        val gateLocked = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val gateOwner =
            launch(Dispatchers.Default) {
                LibraryMutationGate.withLock {
                    gateLocked.complete(Unit)
                    releaseGate.await()
                }
            }
        withTimeout(10_000) { gateLocked.await() }
        val failure = AtomicReference<Throwable?>()
        try {
            val restore =
                launch(Dispatchers.IO) {
                    try {
                        importer.restore(ByteArrayInputStream(archive), RestoreMode.REPLACE)
                    } catch (caught: Throwable) {
                        failure.set(caught)
                    }
                }
            withTimeout(10_000) {
                while (stagingRoot.listFiles().orEmpty().isEmpty()) delay(10)
            }
            restore.cancel(CancellationException("Cancelled before atomic mutation"))
            withTimeout(10_000) { restore.join() }
        } finally {
            releaseGate.complete(Unit)
            withTimeout(10_000) { gateOwner.join() }
        }

        assertTrue(failure.get() is CancellationException)
        assertEquals(listOf("Old"), repository.getAllNotebooks().map { it.title })
        assertArrayEquals(byteArrayOf(1), assets.requireFile("old.bin").readBytes())
        assertTrue(stagingRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationAfterAtomicMutationStartsCompletesReplacement() = runBlocking {
        repository.createNotebook(request("Old"))
        assets.prepare()
        assets.file("old.bin").writeBytes(byteArrayOf(1))
        val archive = sourceArchive("Replacement")
        val atomicMutationStarted = CompletableDeferred<Unit>()
        val releaseAtomicMutation = CompletableDeferred<Unit>()
        val blockingImporter = blockingImporter(atomicMutationStarted, releaseAtomicMutation)
        val summary = AtomicReference<RestoreSummary?>()
        val failure = AtomicReference<Throwable?>()
        val restore =
            launch(Dispatchers.IO) {
                try {
                    summary.set(blockingImporter.restore(ByteArrayInputStream(archive), RestoreMode.REPLACE))
                } catch (caught: Throwable) {
                    failure.set(caught)
                }
            }

        withTimeout(10_000) { atomicMutationStarted.await() }
        restore.cancel(CancellationException("Cancelled after atomic mutation started"))
        releaseAtomicMutation.complete(Unit)
        withTimeout(10_000) { restore.join() }

        assertNull(failure.get())
        assertEquals(RestoreSummary(1, 1, 1, 0), summary.get())
        val notebook = repository.getAllNotebooks().single()
        val assetId = requireNotNull(repository.loadNotebook(notebook.id).elements.single().assetId)
        assertEquals("Replacement", notebook.title)
        assertArrayEquals(testPng(0xFFFF0000.toInt()), assets.requireFile(assetId).readBytes())
        assertFalse(assets.file("old.bin").exists())
        assertTrue(stagingRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun viewModelPublishesReplacementAfterAtomicCancellation() = runBlocking {
        repository.createNotebook(request("Old"))
        val archive = sourceArchive("Replacement")
        val atomicMutationStarted = CompletableDeferred<Unit>()
        val releaseAtomicMutation = CompletableDeferred<Unit>()
        val replacementStoreName = "backup-replacement-${System.nanoTime()}"
        val replacementStore = context.getSharedPreferences(replacementStoreName, Context.MODE_PRIVATE)
        val viewModel =
            BackupViewModel(
                context.applicationContext as Application,
                blockingImporter(atomicMutationStarted, releaseAtomicMutation),
                replacementStore,
            )
        val archiveFile = File(context.cacheDir, "backup-view-model-${System.nanoTime()}.seliasheets")
        archiveFile.writeBytes(archive)

        try {
            viewModel.restore(Uri.fromFile(archiveFile), RestoreMode.REPLACE)
            withTimeout(10_000) { atomicMutationStarted.await() }
            viewModel.viewModelScope.cancel(CancellationException("Cancelled after atomic mutation started"))
            releaseAtomicMutation.complete(Unit)
            withTimeout(10_000) {
                while (viewModel.state.value.running) delay(10)
            }

            assertEquals(1L, viewModel.state.value.replacementGeneration)
            assertTrue(viewModel.hasPendingReplacement())
            assertFalse(viewModel.state.value.failed)
            assertEquals("Replacement", repository.getAllNotebooks().single().title)

            val recreated =
                BackupViewModel(
                    context.applicationContext as Application,
                    replacementPreferences = replacementStore,
                )
            try {
                assertEquals(1L, recreated.state.value.replacementGeneration)
                assertTrue(recreated.hasPendingReplacement())
                recreated.acknowledgeReplacement(requireNotNull(recreated.claimPendingReplacement()))
            } finally {
                recreated.viewModelScope.cancel()
            }
            val afterAcknowledgement =
                BackupViewModel(
                    context.applicationContext as Application,
                    replacementPreferences = replacementStore,
                )
            try {
                assertEquals(1L, afterAcknowledgement.state.value.replacementGeneration)
                assertFalse(afterAcknowledgement.hasPendingReplacement())
            } finally {
                afterAcknowledgement.viewModelScope.cancel()
            }
        } finally {
            releaseAtomicMutation.complete(Unit)
            archiveFile.delete()
            context.deleteSharedPreferences(replacementStoreName)
        }
    }

    @Test
    fun libraryMutationsAndStartupAssetCleanupWaitForBackupGate() = runBlocking {
        assets.prepare()
        val orphan = assets.file("orphan.bin").apply { writeBytes(byteArrayOf(1)) }
        val gateLocked = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val gateOwner =
            launch(Dispatchers.Default) {
                LibraryMutationGate.withLock {
                    gateLocked.complete(Unit)
                    releaseGate.await()
                }
            }
        withTimeout(10_000) { gateLocked.await() }
        val viewModel =
            LibraryViewModel(
                context.applicationContext as Application,
                repository,
                assets,
            )

        try {
            viewModel.createNotebook(request("Queued mutation"))
            val changedWhileLocked =
                withTimeoutOrNull(5_000) {
                    while (orphan.exists() && repository.getAllNotebooks().isEmpty()) delay(10)
                    true
                } == true
            assertFalse("Library mutation or startup cleanup completed while backup owned the gate", changedWhileLocked)
            assertTrue(orphan.exists())
            assertTrue(repository.getAllNotebooks().isEmpty())
            releaseGate.complete(Unit)
            withTimeout(10_000) { gateOwner.join() }
            withTimeout(10_000) {
                while (orphan.exists() || repository.getAllNotebooks().isEmpty()) delay(10)
            }
        } finally {
            releaseGate.complete(Unit)
            withTimeout(10_000) { gateOwner.join() }
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun invalidArchiveLeavesDatabaseAndAssetsUnchanged() = runTest {
        populate(repository, assets, "Existing", testPng(0xFF0000FF.toInt()))

        val failure =
            runCatching {
                    importer.restore(ByteArrayInputStream(byteArrayOf(1, 2, 3)), RestoreMode.REPLACE)
                }
                .exceptionOrNull()

        assertTrue(failure is BackupFailure)
        assertEquals(listOf("Existing"), repository.getAllNotebooks().map { it.title })
        assertArrayEquals(testPng(0xFF0000FF.toInt()), assets.requireFile("asset.png").readBytes())
    }

    @Test
    fun pdfSourceAndPageRoundTripWithOriginalAsset() = runTest {
        val sourceDatabase = inMemoryDatabase()
        val sourceRepository = repository(sourceDatabase)
        val sourceRoot = File(context.cacheDir, "backup-pdf-source-${System.nanoTime()}")
        val sourceAssets = AssetStore(sourceRoot)
        val pdfBytes = testPdf(1)
        val archive =
            try {
                val notebookId = sourceRepository.createNotebook(request("PDF source"))
                sourceAssets.prepare()
                sourceAssets.file("slides.pdf").writeBytes(pdfBytes)
                sourceRepository.importPdf(
                    notebookId,
                    "slides.pdf",
                    "Slides.pdf",
                    pdfBytes.size.toLong(),
                    sha256(pdfBytes),
                    listOf(PdfPageSpec(595, 842)),
                )
                ByteArrayOutputStream().also { output ->
                    BackupExporter(sourceRepository, sourceAssets, "test").export(BackupScope.Library, output)
                }.toByteArray()
            } finally {
                sourceDatabase.close()
                sourceRoot.deleteRecursively()
            }

        importer.restore(ByteArrayInputStream(archive), RestoreMode.MERGE)

        val notebook = repository.getAllNotebooks().single()
        val content = repository.loadNotebook(notebook.id)
        val pdfSource = content.pdfSources.single()
        assertEquals(pdfSource.id, content.pages.single { it.pdfSourceId != null }.pdfSourceId)
        assertArrayEquals(pdfBytes, assets.requireFile(pdfSource.assetId).readBytes())
    }

    @Test
    fun deletedAndDuplicatedPdfPagesRoundTrip() = runTest {
        val sourceDatabase = inMemoryDatabase()
        val sourceRepository = repository(sourceDatabase)
        val sourceRoot = File(context.cacheDir, "backup-pdf-edits-${System.nanoTime()}")
        val sourceAssets = AssetStore(sourceRoot)
        val pdfBytes = testPdf(3)
        val archive =
            try {
                val notebookId = sourceRepository.createNotebook(request("Edited PDF"))
                sourceAssets.prepare()
                sourceAssets.file("edited.pdf").writeBytes(pdfBytes)
                val imported =
                    sourceRepository.importPdf(
                        notebookId,
                        "edited.pdf",
                        "Edited.pdf",
                        pdfBytes.size.toLong(),
                        sha256(pdfBytes),
                        List(3) { PdfPageSpec(595, 842) },
                    )
                sourceRepository.deletePage(imported.pageIds[1])
                sourceRepository.duplicatePage(imported.pageIds[0])
                ByteArrayOutputStream().also { output ->
                    BackupExporter(sourceRepository, sourceAssets, "test").export(BackupScope.Library, output)
                }.toByteArray()
            } finally {
                sourceDatabase.close()
                sourceRoot.deleteRecursively()
            }

        importer.restore(ByteArrayInputStream(archive), RestoreMode.MERGE)

        val restored = repository.loadNotebook(repository.getAllNotebooks().single().id)
        assertEquals(listOf(0, 0, 2), restored.pages.mapNotNull { it.pdfPageIndex }.sorted())
        assertArrayEquals(pdfBytes, assets.requireFile(restored.pdfSources.single().assetId).readBytes())
    }

    @Test
    fun deletedMiddleChapterRoundTripsWithContiguousOrder() = runTest {
        val sourceDatabase = inMemoryDatabase()
        val sourceRepository = repository(sourceDatabase)
        val sourceRoot = File(context.cacheDir, "backup-chapters-${System.nanoTime()}")
        val archive =
            try {
                val notebookId = sourceRepository.createNotebook(request("Chapters"))
                val chapterIds =
                    List(3) { index ->
                        sourceRepository.createChapter(notebookId, "Chapter $index", 0xFF3156D9.toInt())
                    }
                sourceRepository.deleteChapter(chapterIds[1])
                ByteArrayOutputStream().also { output ->
                    BackupExporter(sourceRepository, AssetStore(sourceRoot), "test")
                        .export(BackupScope.Library, output)
                }.toByteArray()
            } finally {
                sourceDatabase.close()
                sourceRoot.deleteRecursively()
            }

        importer.restore(ByteArrayInputStream(archive), RestoreMode.MERGE)

        val restored = repository.loadNotebook(repository.getAllNotebooks().single().id)
        assertEquals(listOf(0, 1), restored.chapters.map { it.orderIndex })
    }

    @Test
    fun duplicatedRotatedEdgeElementExportsAsValidBackup() = runTest {
        val sourceDatabase = inMemoryDatabase()
        val sourceRepository = repository(sourceDatabase)
        val sourceRoot = File(context.cacheDir, "backup-element-${System.nanoTime()}")
        val archive =
            try {
                val notebookId = sourceRepository.createNotebook(request("Rotated element"))
                val page = sourceRepository.getPages(notebookId).single()
                val elementId =
                    sourceRepository.addElement(
                        page.id,
                        ElementDraft(
                            ElementKind.TEXT,
                            x = 488f,
                            y = 755f,
                            width = 100f,
                            height = 60f,
                            rotation = 45f,
                            text = "Edge",
                        ),
                    )
                sourceRepository.duplicateElement(elementId)
                ByteArrayOutputStream().also { output ->
                    BackupExporter(sourceRepository, AssetStore(sourceRoot), "test")
                        .export(BackupScope.Library, output)
                }.toByteArray()
            } finally {
                sourceDatabase.close()
                sourceRoot.deleteRecursively()
            }

        importer.restore(ByteArrayInputStream(archive), RestoreMode.MERGE)

        val restored = repository.loadNotebook(repository.getAllNotebooks().single().id)
        assertEquals(2, restored.elements.size)
        assertTrue(restored.elements.all { it.rotation == 45f })
    }

    private suspend fun sourceArchive(title: String): ByteArray {
        val sourceDatabase = inMemoryDatabase()
        val sourceRepository = repository(sourceDatabase)
        val sourceRoot = File(context.cacheDir, "backup-source-${System.nanoTime()}")
        val sourceAssets = AssetStore(sourceRoot)
        return try {
            populate(sourceRepository, sourceAssets, title, testPng(0xFFFF0000.toInt()))
            ByteArrayOutputStream().also { output ->
                BackupExporter(sourceRepository, sourceAssets, "test", clock = { 42L })
                    .export(BackupScope.Library, output)
            }.toByteArray()
        } finally {
            sourceDatabase.close()
            sourceRoot.deleteRecursively()
        }
    }

    private suspend fun populate(
        targetRepository: SeliaDocsRepository,
        targetAssets: AssetStore,
        title: String,
        assetBytes: ByteArray,
    ) {
        val notebookId = targetRepository.createNotebook(request(title))
        val page = targetRepository.getPages(notebookId).single()
        val chapterId = targetRepository.createChapter(notebookId, "Main chapter", 0xFF3156D9.toInt())
        targetRepository.assignPageToChapter(page.id, chapterId)
        targetRepository.addStroke(
            page.id,
            validTestStrokePayload(),
        )
        targetRepository.updatePageText(page.id, "Imported page text")
        targetAssets.prepare()
        targetAssets.file("asset.png").writeBytes(assetBytes)
        targetRepository.addElement(
            page.id,
            ElementDraft(ElementKind.IMAGE, 0f, 0f, 20f, 20f, assetId = "asset.png"),
        )
    }

    private fun inMemoryDatabase(): SeliaDocsDatabase =
        Room.inMemoryDatabaseBuilder(context, SeliaDocsDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun repository(targetDatabase: SeliaDocsDatabase): SeliaDocsRepository {
        var nextId = 0
        return SeliaDocsRepository(
            database = targetDatabase,
            clock = { 1_000L },
            idFactory = { "id-${nextId++}" },
        )
    }

    private fun blockingImporter(
        atomicMutationStarted: CompletableDeferred<Unit>,
        releaseAtomicMutation: CompletableDeferred<Unit>,
    ): BackupImporter {
        var nextRestoreId = 0
        return BackupImporter(
            database = database,
            repository = repository,
            assets = assets,
            validator = BackupValidator(stagingRoot, PdfSandboxClient(context)::inspect),
            stagingRoot = stagingRoot,
            appVersion = "test",
            idFactory = {
                when (nextRestoreId++) {
                    0 -> "rollback"
                    else -> {
                        runBlocking {
                            atomicMutationStarted.complete(Unit)
                            releaseAtomicMutation.await()
                        }
                        "temporary"
                    }
                }
            },
        )
    }

    private fun request(title: String) =
        CreateNotebookRequest(
            title = title,
            coverColor = CoverColor.PERIWINKLE,
            coverPattern = CoverPattern.SOLID,
            paper = PaperTemplate.GRID,
            orientation = PageOrientation.PORTRAIT,
            fingerDrawing = false,
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
