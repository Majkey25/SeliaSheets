package com.majkeylab.seliadocs.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.data.AnnotationRect
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
import com.majkeylab.seliadocs.data.encodeAnnotationRects
import com.majkeylab.seliadocs.data.encodeSourceRect
import com.majkeylab.seliadocs.pdf.PdfSandboxClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudyAnnotationBackupTest {
    private lateinit var database: SeliaDocsDatabase
    private lateinit var repository: SeliaDocsRepository
    private lateinit var root: File
    private lateinit var assets: AssetStore
    private lateinit var validator: BackupValidator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SeliaDocsDatabase::class.java).build()
        repository = SeliaDocsRepository(database)
        root = File(context.cacheDir, "study-backup-${System.nanoTime()}").apply { mkdirs() }
        assets = AssetStore(File(root, "assets"))
        validator = BackupValidator(File(root, "staging"), PdfSandboxClient(context)::inspect)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun mergeAndDuplicatePreserveShapeColorOpacityAndPageSpaceWidth() = runTest {
        val book = repository.createNotebook(request())
        val page = repository.getPages(book).single().id
        val original = repository.addElementEntity(page, ElementDraft(ElementKind.SHAPE, 20f, 30f, 200f, 40f,
            shapeKind = "LINE", colorArgb = 0x803156D9.toInt(), strokeWidth = 9.5f))
        val duplicate = requireNotNull(repository.getElement(repository.duplicateElement(original.id)))
        assertEquals(original.colorArgb, duplicate.colorArgb)
        assertEquals(original.strokeWidth, duplicate.strokeWidth)
        repository.deleteElement(duplicate.id)
        val output = ByteArrayOutputStream()
        BackupExporter(repository, assets, "test").export(BackupScope.Notebook(book), output)
        validator.validate(ByteArrayInputStream(output.toByteArray())).use { backup ->
            assertEquals(7, backup.manifest.formatVersion)
            assertTrue("shape-style" in backup.manifest.featureFlags)
            assertTrue("pdf-markup" !in backup.manifest.featureFlags)
        }
        BackupImporter(database, repository, assets, validator, File(root, "restore"), "test")
            .restore(ByteArrayInputStream(output.toByteArray()), RestoreMode.MERGE)
        val imported = repository.getAllNotebooks().single { it.id != book }
        val restored = repository.loadNotebook(imported.id).elements.single()
        assertEquals(original.colorArgb, restored.colorArgb)
        assertEquals(original.strokeWidth, restored.strokeWidth)
        assertEquals(original.shapeKind, restored.shapeKind)
    }

    @Test
    fun malformedOrDowngradedShapeStylesAreRejectedAndLegacyShapesStillRead() = runTest {
        val record = backupElement().copy(kind = "SHAPE", text = null, shapeKind = "LINE",
            colorArgb = 0x803156D9.toInt(), strokeWidth = 9.5f, annotationRects = null, sourcePageId = null, sourceRect = null)
        val encoded = StringWriter().also { BackupJson.writeRecord(it, record) }.toString()
        assertEquals(record, BackupJson.records(StringReader(encoded)).single())
        listOf(0f, -1f, 129f, Float.MAX_VALUE, Float.NaN, Float.POSITIVE_INFINITY).forEach { width ->
            assertTrue(runCatching { BackupJson.writeRecord(StringWriter(), record.copy(strokeWidth = width)) }
                .exceptionOrNull() is BackupFailure)
        }
        val malformed = encoded.replace("\"strokeWidth\":9.5", "\"strokeWidth\":1e999")
        assertTrue(runCatching { BackupJson.records(StringReader(malformed)).toList() }.exceptionOrNull() is BackupFailure)
        val notebook = BackupNotebook("book", "Study", "SAGE", "SOLID", "BLANK", "PORTRAIT", false, false, 1, 2, null)
        val page = BackupPage("page", "book", 0, "BLANK", 595, 842)
        val records = StringWriter().also { output -> listOf(notebook, page, record).forEach { BackupJson.writeRecord(output, it) } }.toString()
        listOf(6 to setOf("shape-style"), 7 to emptySet()).forEach { (version, flags) ->
            val manifest = BackupManifest(version, "test", 1, 1, 1, 0, flags)
            val failure = runCatching { validator.validate(ByteArrayInputStream(archive(manifest, records))).close() }.exceptionOrNull()
            assertTrue("$version $flags: $failure", failure is BackupFailure.InvalidRelationship)
        }
        val legacy = record.copy(colorArgb = null, strokeWidth = null)
        val legacyRecords = StringWriter().also { output -> listOf(notebook, page, legacy).forEach { BackupJson.writeRecord(output, it) } }.toString()
        validator.validate(ByteArrayInputStream(archive(BackupManifest(6, "legacy", 1, 1, 1, 0), legacyRecords))).use {
            assertEquals(6, it.manifest.formatVersion)
        }
    }

    @Test
    fun mergeAndPageCopyPreserveMarkupAndRemapInternalSourceLinks() = runTest {
        val book = repository.createNotebook(request())
        val source = repository.getPages(book).single().id
        val target = repository.addPage(book)
        val original = repository.addElementEntity(target, draft(source))
        repository.addElementEntity(source, draft(source).copy(kind = ElementKind.UNDERLINE))
        val duplicate = repository.duplicatePage(source)
        assertEquals(duplicate, repository.getElements(duplicate).single().sourcePageId)
        val copied = repository.getElement(repository.duplicateElement(original.id))!!
        assertEquals(original.annotationRects, copied.annotationRects)
        assertEquals(source, copied.sourcePageId)
        repository.deleteElement(copied.id)
        repository.replaceElements(target, listOf(original))
        val output = ByteArrayOutputStream()
        BackupExporter(repository, assets, "test").export(BackupScope.Notebook(book), output)
        validator.validate(ByteArrayInputStream(output.toByteArray())).use { backup ->
            assertEquals(7, backup.manifest.formatVersion)
            assertTrue(backup.manifest.featureFlags.containsAll(setOf("pdf-markup", "source-links")))
        }
        BackupImporter(database, repository, assets, validator, File(root, "restore"), "test")
            .restore(ByteArrayInputStream(output.toByteArray()), RestoreMode.MERGE)
        val imported = repository.getAllNotebooks().single { it.id != book }
        val pages = repository.getPages(imported.id)
        val restored = repository.getElements(pages.single { it.pageIndex == 2 }.id).single()
        assertEquals(pages.single { it.pageIndex == 0 }.id, restored.sourcePageId)
        assertNotEquals(source, restored.sourcePageId)
        assertEquals(original.annotationRects, restored.annotationRects)
        assertEquals(original.sourceRect, restored.sourceRect)
        assertEquals(original.colorArgb, restored.colorArgb)
        assertEquals(original.text, restored.text)
    }

    @Test
    fun deletedSourceDoesNotDestroyExcerptOrPreventRestore() = runTest {
        val book = repository.createNotebook(request())
        val source = repository.getPages(book).single().id
        val target = repository.addPage(book)
        val original = repository.addElementEntity(target, draft(source).copy(
            kind = ElementKind.TEXT, colorArgb = null, annotationRects = null,
        ))
        repository.deletePage(source)
        val output = ByteArrayOutputStream()
        BackupExporter(repository, assets, "test").export(BackupScope.Notebook(book), output)
        BackupImporter(database, repository, assets, validator, File(root, "restore"), "test")
            .restore(ByteArrayInputStream(output.toByteArray()), RestoreMode.MERGE)
        val imported = repository.getAllNotebooks().single { it.id != book }
        val restored = repository.loadNotebook(imported.id).elements.single()
        assertEquals(original.text, restored.text)
        assertEquals(source, restored.sourcePageId)
    }

    @Test
    fun duplicatingSmallMarkupPreservesSizeAndOriginalSourceLocation() = runTest {
        val book = repository.createNotebook(request())
        val page = repository.getPages(book).single().id
        val original = repository.addElementEntity(page, draft(page).copy(width = 10f, height = 0.5f))
        val duplicate = requireNotNull(repository.getElement(repository.duplicateElement(original.id)))
        assertEquals(original.width, duplicate.width, 0f)
        assertEquals(original.height, duplicate.height, 0f)
        assertEquals(original.x + 12f, duplicate.x, 0.001f)
        assertEquals(original.y + 12f, duplicate.y, 0.001f)
        assertEquals(original.sourceRect, duplicate.sourceRect)
        assertEquals(original.annotationRects, duplicate.annotationRects)
    }

    @Test
    fun libraryMergeRemapsTextExcerptLinksAcrossNotebooks() = runTest {
        val sourceBook = repository.createNotebook(request().copy(title = "Source"))
        val targetBook = repository.createNotebook(request().copy(title = "Study"))
        val source = repository.getPages(sourceBook).single().id
        val target = repository.getPages(targetBook).single().id
        val original = repository.addLinkedExcerpt(target, draft(source).copy(
            kind = ElementKind.TEXT, colorArgb = null, annotationRects = null,
        ))
        assets.prepare()
        assets.file("excerpt.png").writeBytes(testPng(0xFF336699.toInt()))
        repository.addLinkedExcerpt(target, draft(source).copy(
            kind = ElementKind.IMAGE, colorArgb = null, annotationRects = null, assetId = "excerpt.png",
        ))
        val output = ByteArrayOutputStream()
        BackupExporter(repository, assets, "test").export(BackupScope.Library, output)
        BackupImporter(database, repository, assets, validator, File(root, "restore"), "test")
            .restore(ByteArrayInputStream(output.toByteArray()), RestoreMode.MERGE)
        val books = repository.getAllNotebooks()
        val importedSource = books.single { it.title == "Source" && it.id != sourceBook }
        val importedTarget = books.single { it.title == "Study" && it.id != targetBook }
        val restoredElements = repository.loadNotebook(importedTarget.id).elements
        val restored = restoredElements.single { it.kind == "TEXT" }
        assertEquals(repository.getPages(importedSource.id).single().id, restored.sourcePageId)
        assertEquals(original.text, restored.text)
        assertEquals(original.sourceRect, restored.sourceRect)
        val restoredImage = restoredElements.single { it.kind == "IMAGE" }
        assertEquals(restored.sourcePageId, restoredImage.sourcePageId)
        assertTrue(assets.requireFile(requireNotNull(restoredImage.assetId)).readBytes().contentEquals(testPng(0xFF336699.toInt())))
    }

    @Test
    fun generatedPageIdCannotTurnABrokenSourceLinkIntoAnUnrelatedPage() = runTest {
        val book = repository.createNotebook(request())
        val page = repository.getPages(book).single().id
        repository.addElementEntity(page, draft("missing-page"))
        val output = ByteArrayOutputStream()
        BackupExporter(repository, assets, "test").export(BackupScope.Notebook(book), output)
        repository.addElementEntity(page, draft("existing-missing-page"))
        val candidates = listOf("replacement-book", "existing-missing-page", "missing-page", "replacement-page", "replacement-mark").iterator()
        BackupImporter(database, repository, assets, validator, File(root, "restore"), "test", idFactory = { candidates.next() })
            .restore(ByteArrayInputStream(output.toByteArray()), RestoreMode.MERGE)
        val imported = repository.getAllNotebooks().single { it.id != book }
        val content = repository.loadNotebook(imported.id)
        assertEquals("replacement-page", content.pages.single().id)
        assertEquals("missing-page", content.elements.single().sourcePageId)
    }

    @Test
    fun allMarkupKindsRoundTripAndInvalidMetadataIsRejected() {
        val base = backupElement()
        listOf("HIGHLIGHT", "UNDERLINE", "STRIKEOUT").forEach { kind ->
            val record = base.copy(kind = kind)
            val encoded = StringWriter().also { BackupJson.writeRecord(it, record) }.toString()
            assertEquals(record, BackupJson.records(StringReader(encoded)).single())
        }
        listOf(
            base.copy(annotationRects = "0,0,2,1"), base.copy(annotationRects = null),
            base.copy(colorArgb = 0), base.copy(sourceRect = null),
            base.copy(sourcePageId = "  "), base.copy(kind = "TEXT"),
            base.copy(assetId = "hidden.png"),
        ).forEach { invalid ->
            assertTrue(runCatching { BackupJson.writeRecord(StringWriter(), invalid) }.exceptionOrNull() is BackupFailure)
        }
    }

    @Test
    fun versionsOneThroughFiveAndLegacyElementsRemainReadable() {
        (1..5).forEach { version ->
            val text = """{"formatVersion":$version,"appVersion":"legacy","exportedAt":1}"""
            assertEquals(version, BackupJson.readManifest(StringReader(text)).formatVersion)
        }
        val text = """{"kind":"element","id":"old","pageId":"page","zIndex":0,"elementKind":"TEXT",
            "x":10,"y":20,"width":100,"height":50,"rotation":0,"text":"Existing text"}""".replace("\n", "")
        val record = BackupJson.records(StringReader(text)).single() as BackupElement
        assertEquals("Existing text", record.text)
        assertEquals(null, record.colorArgb)
        assertEquals(null, record.annotationRects)
        assertEquals(null, record.sourcePageId)
        assertEquals(null, record.sourceRect)
        assertEquals(null, record.strokeWidth)
    }

    @Test
    fun legacyPunctuatedUnicodePageIdSupportsLinkedExcerptAndBackupMerge() = runTest {
        val book = repository.createNotebook(request())
        val target = repository.getPages(book).single()
        val legacy = target.copy(id = "page.legacy · Řeč: 1", pageIndex = 1)
        database.notebookDao().insertPage(legacy)
        val original = repository.addLinkedExcerpt(target.id, draft(legacy.id).copy(
            kind = ElementKind.TEXT, colorArgb = null, annotationRects = null,
        ))
        val output = ByteArrayOutputStream()
        BackupExporter(repository, assets, "test").export(BackupScope.Notebook(book), output)
        BackupImporter(database, repository, assets, validator, File(root, "restore"), "test")
            .restore(ByteArrayInputStream(output.toByteArray()), RestoreMode.MERGE)
        val imported = repository.getAllNotebooks().single { it.id != book }
        val content = repository.loadNotebook(imported.id)
        assertEquals(content.pages.single { it.pageIndex == 1 }.id, content.elements.single().sourcePageId)
        assertEquals(original.text, content.elements.single().text)
        assertEquals(original.sourceRect, content.elements.single().sourceRect)
    }

    @Test
    fun downgradedOrUnflaggedAnnotationsAreRejectedBeforeRestore() = runTest {
        val notebook = BackupNotebook("book", "Study", "SAGE", "SOLID", "BLANK", "PORTRAIT", false, false, 1, 2, null)
        val page = BackupPage("page", "book", 0, "BLANK", 595, 842)
        val record = backupElement()
        val records = StringWriter().also { output -> listOf(notebook, page, record).forEach { BackupJson.writeRecord(output, it) } }.toString()
        listOf(5 to setOf("pdf-markup", "source-links"), 6 to emptySet(), 6 to setOf("pdf-markup")).forEach { (version, flags) ->
            val manifest = BackupManifest(version, "test", 1, 1, 1, 0, flags)
            val failure = runCatching { validator.validate(ByteArrayInputStream(archive(manifest, records))).close() }.exceptionOrNull()
            assertTrue("$version $flags: $failure", failure is BackupFailure.InvalidRelationship)
        }
    }

    private fun request() = CreateNotebookRequest("Study", CoverColor.SAGE, CoverPattern.SOLID, PaperTemplate.BLANK, PageOrientation.PORTRAIT, false)

    private fun draft(source: String) = ElementDraft(
        ElementKind.HIGHLIGHT, 20f, 30f, 200f, 40f, text = "Selected text", colorArgb = 0x66FFD54F,
        annotationRects = encodeAnnotationRects(listOf(AnnotationRect(0f, 0f, 1f, 0.4f), AnnotationRect(0f, 0.6f, 0.6f, 1f))),
        sourcePageId = source, sourceRect = encodeSourceRect(AnnotationRect(0.1f, 0.2f, 0.8f, 0.3f)),
    )

    private fun backupElement(): BackupElement {
        val draft = draft("missing-page")
        return BackupElement("mark", "page", 0, draft.kind.name, draft.x, draft.y, draft.width, draft.height,
            0f, draft.text, null, null, null, null, colorArgb = draft.colorArgb, annotationRects = draft.annotationRects,
            sourcePageId = draft.sourcePageId, sourceRect = draft.sourceRect)
    }

    private fun archive(manifest: BackupManifest, records: String): ByteArray {
        val manifestText = StringWriter().also { BackupJson.writeManifest(it, manifest) }.toString()
        val entries = mapOf("manifest.json" to manifestText.toByteArray(), "records.jsonl" to records.toByteArray())
        val checksums = entries.entries.joinToString(",") { (name, bytes) ->
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            "\"$name\":\"$hash\""
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            (entries + ("checksums.json" to "{\"algorithm\":\"SHA-256\",\"entries\":{$checksums}}".toByteArray())).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
