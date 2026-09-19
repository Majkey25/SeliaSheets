package com.majkeylab.seliadocs.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfTextSearchBackendTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.cacheDir, "pdf-search-${System.nanoTime()}.pdf")

    @After
    fun cleanUp() { file.delete() }

    @Test
    fun nativeSearchReturnsRepeatedMatchesAndTextPresenceForMisses() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        createPdf(imageOnly = false)
        val result = PdfSandboxClient(context).searchText(file, 0, "Alpha")
        assertTrue(result.hasText)
        assertEquals(2, result.matches.size)
        assertTrue(result.matches.all { !it.isOcr && it.bounds.isNotEmpty() })
        assertTrue(result.matches.flatMap { it.bounds }.all { it.left >= 0f && it.right <= 1f && it.top >= 0f && it.bottom <= 1f })
        val missing = PdfSandboxClient(context).searchText(file, 0, "missing-keyword")
        assertTrue("A missed keyword must not trigger page OCR when native text exists", missing.hasText)
        assertTrue(missing.matches.isEmpty())
        assertTrue(PdfTextSearcher(context).search(file, 0, "missing-keyword", allowOcr = true).isEmpty())
    }

    @Test
    fun scannedPdfSearchUsesWordOcrAndLiteralPhraseMatching() = runBlocking {
        createPdf(imageOnly = true)
        val searcher = PdfTextSearcher(context)
        val result = searcher.search(file, 0, "alpha beta")
        assertEquals(2, result.size)
        assertTrue(result.all { it.isOcr && it.bounds.size == 2 })
        assertTrue(searcher.search(file, 0, "*?").isEmpty())
        assertTrue(searcher.search(file, 0, "missing-keyword").isEmpty())
        if (Build.VERSION.SDK_INT >= 35) {
            assertTrue(searcher.search(file, 0, "Alpha", allowOcr = false).isEmpty())
        }
    }

    @Test
    fun oldSdkWithoutOcrReportsUnsupportedInsteadOfNoMatches() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT < 35)
        createPdf(imageOnly = false)
        val failure = runCatching { PdfTextSearcher(context).search(file, 0, "Alpha", allowOcr = false) }.exceptionOrNull()
        assertTrue(failure is UnsupportedOperationException)
        assertEquals(PdfProtocol.ERROR_SEARCH_UNSUPPORTED, failure?.message)
    }

    @Test
    fun emptyInvalidAndCancelledQueriesDoNotOpenPdf() = runBlocking {
        val searcher = PdfTextSearcher(context)
        assertTrue(searcher.search(file, 0, "  ").isEmpty())
        assertTrue(runCatching { searcher.search(file, 0, "x".repeat(257)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { searcher.search(file, -1, "Alpha") }.exceptionOrNull() is IllegalArgumentException)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            searcher.search(file, 0, "Alpha")
            fail("Cancelled search must not return")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(file.exists())
    }

    @Test
    fun invalidBinderQueryClosesReceivedDescriptor() {
        createPdf(imageOnly = false)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val fd = descriptor.fileDescriptor
        val service = IPdfRenderService.Stub.asInterface(PdfRenderService().onBind(null))
        val result = service.searchText(descriptor, 0, "x".repeat(257))
        assertFalse(result.getBoolean(PdfProtocol.SUCCESS))
        assertEquals(PdfProtocol.ERROR_LIMIT, result.getString(PdfProtocol.ERROR))
        assertFalse(fd.valid())
    }

    @Test
    fun corruptPdfIsAnExplicitFailureNotAnEmptySearch() = runBlocking {
        file.writeText("Not a PDF")
        val failure = runCatching { PdfTextSearcher(context).search(file, 0, "Alpha") }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun malformedOrOversizedBinderSearchPayloadIsRejected() {
        fun reply(counts: IntArray, bounds: FloatArray, hasText: Boolean = true) = Bundle().apply {
            putBoolean(PdfProtocol.SEARCH_HAS_TEXT, hasText)
            putIntArray(PdfProtocol.SEARCH_MATCH_COUNTS, counts)
            putFloatArray(PdfProtocol.SEARCH_BOUNDS, bounds)
        }
        assertThrows(IOException::class.java) { decodePdfSearchResult(Bundle()) }
        assertThrows(IOException::class.java) { decodePdfSearchResult(reply(IntArray(101) { 1 }, FloatArray(404))) }
        assertThrows(IOException::class.java) { decodePdfSearchResult(reply(intArrayOf(2_001), FloatArray(8_004))) }
        assertThrows(IOException::class.java) { decodePdfSearchResult(reply(intArrayOf(-1), floatArrayOf())) }
        assertThrows(IOException::class.java) { decodePdfSearchResult(reply(intArrayOf(1), floatArrayOf(0f, 0f, 1f))) }
        assertThrows(IOException::class.java) { decodePdfSearchResult(reply(intArrayOf(1), floatArrayOf(0f, Float.NaN, 1f, 1f))) }
        assertThrows(IOException::class.java) { decodePdfSearchResult(reply(intArrayOf(1), floatArrayOf(0f, 0f, 1f, 1f), hasText = false)) }
        val valid = decodePdfSearchResult(reply(intArrayOf(1), floatArrayOf(0.1f, 0.2f, 0.5f, 0.3f)))
        assertEquals(listOf(PdfTextBounds(0.1f, 0.2f, 0.5f, 0.3f)), valid.matches.single().bounds)
    }

    @Test
    fun nativeSearchBudgetOverflowIsExplicit() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 2_000, 1).create())
            val paint = Paint().apply { color = Color.BLACK; textSize = 10f }
            repeat(110) { page.canvas.drawText("needle", 20f, 20f + it * 15f, paint) }
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        val failure = runCatching { PdfTextSearcher(context).search(file, 0, "needle", allowOcr = false) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(PdfProtocol.ERROR_LIMIT, failure?.message)
    }

    private fun createPdf(imageOnly: Boolean) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 36f }
            fun draw(canvas: Canvas) {
                canvas.drawColor(Color.WHITE)
                canvas.drawText("Alpha Beta", 60f, 100f, paint)
                canvas.drawText("Alpha Beta", 60f, 200f, paint)
            }
            if (imageOnly) {
                val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
                try { draw(Canvas(bitmap)); page.canvas.drawBitmap(bitmap, 0f, 0f, null) } finally { bitmap.recycle() }
            } else draw(page.canvas)
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
    }
}
