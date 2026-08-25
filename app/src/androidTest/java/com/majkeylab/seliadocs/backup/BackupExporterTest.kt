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
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.data.StrokePayload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        repository.addPage(notebookId)
        repository.addStroke(
            firstPage.id,
            StrokePayload("PEN", 0xff000000.toInt(), 3f, 0.1f, byteArrayOf(1, 2, 3)),
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
        val assetBytes = byteArrayOf(4, 5, 6, 7)
        assets.prepare()
        assets.file("asset.png").writeBytes(assetBytes)
        repository.addElement(
            firstPage.id,
            ElementDraft(ElementKind.IMAGE, 20f, 140f, 80f, 80f, assetId = "asset.png"),
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
        assertEquals(1, records.filterIsInstance<BackupStroke>().size)
        assertEquals(3, records.filterIsInstance<BackupElement>().size)
        assertArrayEquals(assetBytes, entries.getValue("assets/asset.png"))
        assertEquals(sha256(assetBytes), readChecksums(entries.getValue("checksums.json")).getValue("assets/asset.png"))
        assertEquals(1, manifest.notebookCount)
        assertEquals(2, manifest.pageCount)
        assertEquals(1, manifest.assetCount)
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
