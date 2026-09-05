package com.majkeylab.seliadocs.backup

import android.content.Context
import android.util.JsonReader
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupExporterTest {
    private lateinit var database: SeliaDocsDatabase
    private lateinit var repository: SeliaDocsRepository
    private lateinit var assetRoot: File
    private lateinit var assets: AssetStore
    private lateinit var exporter: BackupExporter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var nextId = 0
        database =
            Room.inMemoryDatabaseBuilder(context, SeliaDocsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            SeliaDocsRepository(
                database = database,
                clock = { 1_000L },
                idFactory = { "id-${nextId++}" },
            )
        assetRoot = File(context.cacheDir, "backup-export-${System.nanoTime()}")
        assets = AssetStore(assetRoot)
        exporter = BackupExporter(repository, assets, appVersion = "test", clock = { 42L })
    }

    @After
    fun tearDown() {
        database.close()
        assetRoot.deleteRecursively()
    }

    @Test
    fun completeNotebookExportsEditableRecordsAndAssetChecksum() = runTest {
        val notebookId = repository.createNotebook(request("Physics"))
        val firstPage = repository.getPages(notebookId).single()
        val chapterId = repository.createChapter(notebookId, "Mechanics", 0xFF3156D9.toInt())
        repository.assignPageToChapter(firstPage.id, chapterId)
        repository.addPage(notebookId)
        repository.updatePageText(firstPage.id, "Typed lecture notes")
        repository.addStroke(
            firstPage.id,
            validTestStrokePayload(),
        )
        repository.addElement(
            firstPage.id,
            ElementDraft(ElementKind.TEXT, 10f, 20f, 120f, 48f, text = "Velocity"),
        )
        repository.addElement(
            firstPage.id,
            ElementDraft(
                ElementKind.MATH,
                10f,
                80f,
                120f,
                48f,
                expression = "2+2",
                resultText = "4",
            ),
        )
        val assetBytes = testPng(0xFFFF0000.toInt())
        assets.prepare()
        assets.file("asset.png").writeBytes(assetBytes)
        repository.addElement(
            firstPage.id,
            ElementDraft(
                ElementKind.IMAGE,
                20f,
                140f,
                80f,
                80f,
                assetId = "asset.png",
                ocrRegions = "VmVsb2NpdHk,0.1,0.2,0.8,0.4",
            ),
        )
        val output = ByteArrayOutputStream()

        val summary = exporter.export(BackupScope.Notebook(notebookId), output)
        val entries = readZip(output.toByteArray())
        val records = mutableListOf<BackupRecord>()
        BackupJson.readRecords(entries.getValue("records.jsonl").inputStream().reader(), records::add)
        val manifest =
            BackupJson.readManifest(entries.getValue("manifest.json").inputStream().reader())

        assertEquals(setOf("manifest.json", "records.jsonl", "assets/asset.png", "checksums.json"), entries.keys)
        assertEquals(1, records.filterIsInstance<BackupNotebook>().size)
        assertEquals(2, records.filterIsInstance<BackupPage>().size)
        assertEquals("Mechanics", records.filterIsInstance<BackupChapter>().single().title)
        assertEquals(chapterId, records.filterIsInstance<BackupPage>().first().chapterId)
        assertEquals(1, records.filterIsInstance<BackupStroke>().size)
        assertEquals(3, records.filterIsInstance<BackupElement>().size)
        assertEquals(
            "VmVsb2NpdHk,0.1,0.2,0.8,0.4",
            records.filterIsInstance<BackupElement>().single { it.kind == ElementKind.IMAGE.name }.ocrRegions,
        )
        assertEquals("Typed lecture notes", records.filterIsInstance<BackupBlock>().single().text)
        assertArrayEquals(assetBytes, entries.getValue("assets/asset.png"))
        assertEquals(sha256(assetBytes), readChecksums(entries.getValue("checksums.json")).getValue("assets/asset.png"))
        assertEquals(1, manifest.notebookCount)
        assertEquals(BACKUP_FORMAT_VERSION, manifest.formatVersion)
        assertEquals(2, manifest.pageCount)
        assertEquals(1, manifest.assetCount)
        assertTrue("page-text" in manifest.featureFlags)
        assertTrue("chapters" in manifest.featureFlags)
        assertEquals(BackupSummary(1, 2, 1, output.size().toLong()), summary)
    }

    @Test
    fun emptyLibraryExportsValidEmptyArchive() = runTest {
        val output = ByteArrayOutputStream()

        val summary = exporter.export(BackupScope.Library, output)
        val entries = readZip(output.toByteArray())
        val records = mutableListOf<BackupRecord>()
        BackupJson.readRecords(entries.getValue("records.jsonl").inputStream().reader(), records::add)

        assertEquals(BackupSummary(0, 0, 0, output.size().toLong()), summary)
        assertTrue(records.isEmpty())
    }

    @Test
    fun pdfSourceAndBackedPageAreIncludedInEditableBackup() = runTest {
        val notebookId = repository.createNotebook(request("PDF backup"))
        val pdfBytes = testPdf(1)
        assets.prepare()
        assets.file("slides.pdf").writeBytes(pdfBytes)
        repository.importPdf(
            notebookId,
            "slides.pdf",
            "Slides.pdf",
            pdfBytes.size.toLong(),
            sha256(pdfBytes),
            listOf(PdfPageSpec(595, 842)),
        )
        val output = ByteArrayOutputStream()

        exporter.export(BackupScope.Notebook(notebookId), output)

        val entries = readZip(output.toByteArray())
        val records = mutableListOf<BackupRecord>()
        BackupJson.readRecords(entries.getValue("records.jsonl").inputStream().reader(), records::add)
        val source = records.filterIsInstance<BackupPdfSource>().single()
        val page = records.filterIsInstance<BackupPage>().single { it.pdfSourceId != null }
        val manifest = BackupJson.readManifest(entries.getValue("manifest.json").inputStream().reader())
        assertEquals(source.id, page.pdfSourceId)
        assertArrayEquals(pdfBytes, entries.getValue("assets/slides.pdf"))
        assertTrue("pdf-sources" in manifest.featureFlags)
    }

    @Test
    fun selectedScopeExportsOnlyRequestedNotebook() = runTest {
        val selected = repository.createNotebook(request("Selected"))
        repository.createNotebook(request("Excluded"))
        val output = ByteArrayOutputStream()

        exporter.export(BackupScope.Selected(setOf(selected)), output)
        val records = mutableListOf<BackupRecord>()
        BackupJson.readRecords(
            readZip(output.toByteArray()).getValue("records.jsonl").inputStream().reader(),
            records::add,
        )

        assertEquals(listOf("Selected"), records.filterIsInstance<BackupNotebook>().map(BackupNotebook::title))
    }

    @Test
    fun missingAssetFailsBeforeWritingArchive() = runTest {
        val notebookId = repository.createNotebook(request("Missing asset"))
        val page = repository.getPages(notebookId).single()
        repository.addElement(
            page.id,
            ElementDraft(ElementKind.IMAGE, 0f, 0f, 20f, 20f, assetId = "missing.png"),
        )
        val output = ByteArrayOutputStream()

        val failure =
            runCatching { exporter.export(BackupScope.Notebook(notebookId), output) }
                .exceptionOrNull()

        assertTrue(failure is BackupExportFailure.MissingAsset)
        assertEquals("missing.png", (failure as BackupExportFailure.MissingAsset).assetId)
        assertEquals(0, output.size())
    }

    @Test
    fun libraryExportWaitsForLibraryMutationGate() = runBlocking {
        repository.createNotebook(request("Locked export"))
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
        val output = ByteArrayOutputStream()
        val factoryOpened = AtomicBoolean(false)
        val export =
            async(Dispatchers.IO) {
                exportUserLibrary(exporter) {
                    factoryOpened.set(true)
                    output
                }
            }

        var factoryOpenedWhileLocked = false
        val completedWhileLocked =
            try {
                (withTimeoutOrNull(5_000) { export.await() } != null).also {
                    factoryOpenedWhileLocked = factoryOpened.get()
                }
            } finally {
                releaseGate.complete(Unit)
                withTimeout(10_000) { gateOwner.join() }
            }

        assertFalse("Library export completed while a mutation owned the gate", completedWhileLocked)
        assertFalse(factoryOpenedWhileLocked)
        withTimeout(10_000) { export.await() }
        assertTrue(output.size() > 0)
    }

    @Test
    fun recordLimitFailsBeforeOpeningDestination() = runTest {
        createEightRecordNotebook()
        val limitedExporter =
            BackupExporter(
                repository = repository,
                assets = assets,
                appVersion = "test",
                clock = { 42L },
                maxRecords = 7,
            )
        val output = ByteArrayOutputStream()
        val factoryOpened = AtomicBoolean(false)

        val failure =
            runCatching {
                    exportUserLibrary(limitedExporter) {
                        factoryOpened.set(true)
                        output
                    }
                }
                .exceptionOrNull()

        assertTrue(failure is BackupFailure.LimitExceeded)
        assertEquals("recordCount", (failure as BackupFailure.LimitExceeded).field)
        assertFalse(factoryOpened.get())
        assertEquals(0, output.size())
    }

    @Test
    fun exactRecordLimitOpensDestinationAndExportsAllRecords() = runTest {
        createEightRecordNotebook()
        val limitedExporter =
            BackupExporter(
                repository = repository,
                assets = assets,
                appVersion = "test",
                clock = { 42L },
                maxRecords = 8,
            )
        val output = ByteArrayOutputStream()
        val factoryOpened = AtomicBoolean(false)

        limitedExporter.export(BackupScope.Library) {
            factoryOpened.set(true)
            output
        }

        val records = mutableListOf<BackupRecord>()
        BackupJson.readRecords(
            readZip(output.toByteArray()).getValue("records.jsonl").inputStream().reader(),
            records::add,
        )
        assertTrue(factoryOpened.get())
        assertEquals(8, records.size)
    }

    @Test
    fun checksumMetadataOverBudgetFailsBeforeOpeningDestination() = runTest {
        val notebookId = createImageNotebook()
        val limitedExporter =
            BackupExporter(
                repository = repository,
                assets = assets,
                appVersion = "test",
                clock = { 42L },
                maxChecksumBytes = 1,
            )
        val output = ByteArrayOutputStream()
        val factoryOpened = AtomicBoolean(false)

        val failure =
            runCatching {
                    limitedExporter.export(BackupScope.Notebook(notebookId)) {
                        factoryOpened.set(true)
                        output
                    }
                }
                .exceptionOrNull()

        assertTrue(failure is BackupFailure.LimitExceeded)
        assertEquals("checksums", (failure as BackupFailure.LimitExceeded).field)
        assertFalse(factoryOpened.get())
        assertEquals(0, output.size())
    }

    @Test
    fun checksumMetadataAtExactBudgetExports() = runTest {
        val notebookId = createImageNotebook()
        val baseline = ByteArrayOutputStream()
        exporter.export(BackupScope.Notebook(notebookId), baseline)
        val budget = readZip(baseline.toByteArray()).getValue("checksums.json").size.toLong()
        val exactExporter =
            BackupExporter(
                repository = repository,
                assets = assets,
                appVersion = "test",
                clock = { 42L },
                maxChecksumBytes = budget,
            )
        val output = ByteArrayOutputStream()

        exactExporter.export(BackupScope.Notebook(notebookId), output)

        assertEquals(budget, readZip(output.toByteArray()).getValue("checksums.json").size.toLong())
    }

    private suspend fun createEightRecordNotebook() {
        val notebookId = repository.createNotebook(request("Eight records"))
        val page = repository.getPages(notebookId).single()
        repository.createChapter(notebookId, "Chapter", 0xFF3156D9.toInt())
        repository.addStroke(page.id, validTestStrokePayload())
        repository.addElement(
            page.id,
            ElementDraft(ElementKind.TEXT, 10f, 20f, 120f, 48f, text = "Element"),
        )
        repository.updatePageText(page.id, "Block")
        val pdfBytes = testPdf(1)
        assets.prepare()
        assets.file("limit.pdf").writeBytes(pdfBytes)
        repository.importPdf(
            notebookId,
            "limit.pdf",
            "Limit.pdf",
            pdfBytes.size.toLong(),
            sha256(pdfBytes),
            listOf(PdfPageSpec(595, 842)),
        )
    }

    private suspend fun createImageNotebook(): String {
        val notebookId = repository.createNotebook(request("Checksum budget"))
        val page = repository.getPages(notebookId).single()
        assets.prepare()
        assets.file("asset.png").writeBytes(testPng(0xFF0000FF.toInt()))
        repository.addElement(
            page.id,
            ElementDraft(ElementKind.IMAGE, 0f, 0f, 20f, 20f, assetId = "asset.png"),
        )
        return notebookId
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

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> =
        buildMap {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes())
                    zip.closeEntry()
                }
            }
        }

    private fun readChecksums(bytes: ByteArray): Map<String, String> =
        buildMap {
            JsonReader(StringReader(bytes.toString(Charsets.UTF_8))).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "entries") {
                        reader.beginObject()
                        while (reader.hasNext()) put(reader.nextName(), reader.nextString())
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
