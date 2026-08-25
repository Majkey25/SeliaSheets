package com.majkeylab.seliadocs.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.majkeylab.seliadocs.data.StrokePayload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
                validator = BackupValidator(stagingRoot),
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
        assertArrayEquals(byteArrayOf(1, 2, 3), content.strokes.single().inputs)
        val assetId = content.elements.single().assetId
        assertArrayEquals(byteArrayOf(4, 5, 6), assets.requireFile(requireNotNull(assetId)).readBytes())
        assertEquals(RestoreSummary(1, 1, 1, 0), summary)
    }

    @Test
    fun mergeRemapsCollidingIdsAndPreservesExistingData() = runTest {
        populate(repository, assets, "Existing", byteArrayOf(9, 9, 9))
        val archive = sourceArchive("Imported")

        val summary = importer.restore(ByteArrayInputStream(archive), RestoreMode.MERGE)

        val notebooks = repository.getAllNotebooks()
        assertEquals(setOf("Existing", "Imported"), notebooks.mapTo(mutableSetOf()) { it.title })
        assertEquals(2, notebooks.map { it.id }.toSet().size)
        assertEquals(2, assets.files().size)
        val imported = notebooks.single { it.title == "Imported" }
        val importedAsset = repository.loadNotebook(imported.id).elements.single().assetId
        assertTrue(importedAsset != "asset.png")
        assertArrayEquals(byteArrayOf(4, 5, 6), assets.requireFile(requireNotNull(importedAsset)).readBytes())
        assertArrayEquals(byteArrayOf(9, 9, 9), assets.requireFile("asset.png").readBytes())
        assertTrue(summary.remappedIds >= 3)
    }

    @Test
    fun replaceSwapsLibraryAndRemovesOldAssets() = runTest {
        populate(repository, assets, "Old", byteArrayOf(9, 9, 9))
        val archive = sourceArchive("Replacement")

        val summary = importer.restore(ByteArrayInputStream(archive), RestoreMode.REPLACE)

        val notebook = repository.getAllNotebooks().single()
        val assetId = repository.loadNotebook(notebook.id).elements.single().assetId
        assertEquals("Replacement", notebook.title)
        assertEquals(1, assets.files().size)
        assertArrayEquals(byteArrayOf(4, 5, 6), assets.requireFile(requireNotNull(assetId)).readBytes())
        assertEquals(1, summary.notebooks)
        assertTrue(stagingRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun invalidArchiveLeavesDatabaseAndAssetsUnchanged() = runTest {
        populate(repository, assets, "Existing", byteArrayOf(9, 9, 9))

        val failure =
            runCatching {
                    importer.restore(ByteArrayInputStream(byteArrayOf(1, 2, 3)), RestoreMode.REPLACE)
                }
                .exceptionOrNull()

        assertTrue(failure is BackupFailure)
        assertEquals(listOf("Existing"), repository.getAllNotebooks().map { it.title })
        assertArrayEquals(byteArrayOf(9, 9, 9), assets.requireFile("asset.png").readBytes())
    }

    private suspend fun sourceArchive(title: String): ByteArray {
        val sourceDatabase = inMemoryDatabase()
        val sourceRepository = repository(sourceDatabase)
        val sourceRoot = File(context.cacheDir, "backup-source-${System.nanoTime()}")
        val sourceAssets = AssetStore(sourceRoot)
        return try {
            populate(sourceRepository, sourceAssets, title, byteArrayOf(4, 5, 6))
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
        targetRepository.addStroke(
            page.id,
            StrokePayload("PEN", 0xff000000.toInt(), 3f, 0.1f, byteArrayOf(1, 2, 3)),
        )
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

    private fun request(title: String) =
        CreateNotebookRequest(
            title = title,
            coverColor = CoverColor.PERIWINKLE,
            coverPattern = CoverPattern.SOLID,
            paper = PaperTemplate.GRID,
            orientation = PageOrientation.PORTRAIT,
            fingerDrawing = false,
        )
}
