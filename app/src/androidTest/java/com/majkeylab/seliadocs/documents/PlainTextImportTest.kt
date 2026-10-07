package com.majkeylab.seliadocs.documents

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlainTextImportTest {
    private lateinit var application: Application
    private lateinit var database: SeliaDocsDatabase
    private lateinit var repository: SeliaDocsRepository
    private lateinit var directory: File
    private lateinit var importer: PlainTextImporter

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(application, SeliaDocsDatabase::class.java).build()
        repository = SeliaDocsRepository(database)
        directory = File(application.cacheDir, "plain-text-test-${System.nanoTime()}").apply { mkdirs() }
        importer = PlainTextImporter(application.contentResolver, repository)
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun markdownStaysLiteralAndUnicodeSurvivesPagination() = runBlocking {
        val notebook = createNotebook()
        val original = "# Nadpis **tučně**\r\n[odkaz](https://example.com)\r" +
            "  Příliš žluťoučký 🌍 e\u0301\t終\r\n\r\n".repeat(300) + "\u000CKonec\n"
        val expected = original.replace("\r\n", "\n").replace('\r', '\n')
        val source = File(directory, "notes.md").apply { writeText(original) }
        val bytes = source.readBytes()

        val pages = importer.import(notebook, Uri.fromFile(source))

        assertTrue(pages.size > 1)
        assertEquals(expected, importedText(pages))
        assertTrue(pages.all { repository.getPage(it)?.pageMode == PageMode.PAPER.name })
        assertTrue(repository.getPdfSources(notebook).isEmpty())
        assertTrue(repository.getReferencedAssetIds().isEmpty())
        assertArrayEquals(bytes, source.readBytes())
        assertEquals(listOf(source.name), directory.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun strictUtf8AndBomMarkedUtf16PreserveTextAndEmptyFiles() = runBlocking {
        val notebook = createNotebook()
        val text = "Příliš 🌍\r\n\t終\rKonec"
        val expected = "Příliš 🌍\n\t終\nKonec"
        val inputs = listOf(
            text.toByteArray(Charsets.UTF_8) to expected,
            (byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)) to expected,
            (byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)) to expected,
            (byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)) to expected,
            byteArrayOf() to "",
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) to "",
        )
        val source = File(directory, "opaque")
        for ((bytes, decoded) in inputs) {
            source.writeBytes(bytes)
            val pages = importer.import(notebook, Uri.fromFile(source))
            assertEquals(decoded, importedText(pages))
            assertArrayEquals(bytes, source.readBytes())
        }
    }

    @Test
    fun malformedEncodingAndBinaryControlsDoNotSavePages() = runBlocking {
        val notebook = createNotebook()
        val before = repository.loadNotebook(notebook)
        val inputs = listOf(
            byteArrayOf(0xC3.toByte(), 0x28),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x61),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0xD8.toByte()),
            "unmarked".toByteArray(Charsets.UTF_16LE),
            "text\u0000end".toByteArray(),
            "text\u0001end".toByteArray(),
            "text\u007Fend".toByteArray(),
            "text\u0085end".toByteArray(),
        )
        val source = File(directory, "invalid.txt")
        for (bytes in inputs) {
            source.writeBytes(bytes)
            val failure = runCatching { importer.import(notebook, Uri.fromFile(source)) }.exceptionOrNull()
            assertTrue("Invalid encoding or binary content was accepted", failure is IOException)
            assertEquals(before, repository.loadNotebook(notebook))
            assertArrayEquals(bytes, source.readBytes())
        }
        assertEquals(listOf(source.name), directory.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun encodedAndDecodedLimitsRejectWithoutDatabaseChanges() = runBlocking {
        val notebook = createNotebook()
        val before = repository.loadNotebook(notebook)
        val source = File(directory, "large.txt")
        source.writeBytes(ByteArray(4 * 1024 * 1024 + 1) { 'x'.code.toByte() })
        val encodedFailure = runCatching { importer.import(notebook, Uri.fromFile(source)) }.exceptionOrNull()
        assertTrue(encodedFailure is IOException)
        assertEquals("Text file exceeds 4 MiB", encodedFailure?.message)
        assertEquals(before, repository.loadNotebook(notebook))

        source.writeText("x".repeat(1_000_001))
        val decodedFailure = runCatching { importer.import(notebook, Uri.fromFile(source)) }.exceptionOrNull()
        assertTrue(decodedFailure is IOException)
        assertEquals("Text exceeds 1,000,000 characters", decodedFailure?.message)
        assertEquals(before, repository.loadNotebook(notebook))
        assertEquals(listOf(source.name), directory.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun insertionPreservesAnchorChapterDimensionsAndFollowingPage() = runBlocking {
        val notebook = createNotebook(PageOrientation.LANDSCAPE)
        val anchor = repository.getPages(notebook).single()
        val chapter = repository.createChapter(notebook, "Lecture", 0xFF123456.toInt())
        repository.assignPageToChapter(anchor.id, chapter)
        val followingId = repository.addPage(notebook, anchor.id)
        repository.updatePageText(followingId, "Existing notes")
        val following = requireNotNull(repository.getPage(followingId))
        val source = File(directory, "notes.txt").apply { writeText("First\u000CSecond") }

        val inserted = importer.import(notebook, Uri.fromFile(source), anchor.id)

        val pages = repository.getPages(notebook)
        assertEquals(2, inserted.size)
        assertEquals(listOf(anchor.id) + inserted + followingId, pages.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), pages.map { it.pageIndex })
        assertTrue(pages.filter { it.id in inserted }.all {
            it.chapterId == chapter && it.widthPoints == 842 && it.heightPoints == 595 && it.pageMode == PageMode.PAPER.name
        })
        assertEquals(following.copy(pageIndex = 3), pages.last())
        assertEquals("Existing notes", repository.getBlocks(followingId).single().text)
        assertEquals("First\u000CSecond", importedText(inserted))
    }

    @Test
    fun missingAndForeignAnchorsLeaveBothNotebooksUnchanged() = runBlocking {
        val notebook = createNotebook()
        val otherNotebook = createNotebook()
        val foreignPage = repository.getPages(otherNotebook).single()
        val before = repository.loadNotebook(notebook)
        val otherBefore = repository.loadNotebook(otherNotebook)
        val source = File(directory, "valid.txt").apply { writeText("Do not insert") }
        for (anchor in listOf("missing", foreignPage.id)) {
            assertTrue(runCatching { importer.import(notebook, Uri.fromFile(source), anchor) }.isFailure)
            assertEquals(before, repository.loadNotebook(notebook))
            assertEquals(otherBefore, repository.loadNotebook(otherNotebook))
        }
    }

    private suspend fun importedText(pages: List<String>): String =
        pages.map { repository.getBlocks(it).singleOrNull()?.text.orEmpty() }.joinToString("")

    private suspend fun createNotebook(orientation: PageOrientation = PageOrientation.PORTRAIT): String =
        repository.createNotebook(CreateNotebookRequest("Text", CoverColor.SAGE, CoverPattern.SOLID, PaperTemplate.RULED, orientation, false))
}
