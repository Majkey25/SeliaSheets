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
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.recognition.recognizeBitmap
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfTextSelectionBackendTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.cacheDir, "pdf-selection-${System.nanoTime()}.pdf")

    @After
    fun cleanUp() { file.delete() }

    @Test
    fun nativeTextAboveGraphicIsNotSelectedWhenLassoOnlyContainsGraphic() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        createPdf(drawDiagram = true)
        val selector = PdfTextSelector(context)
        assertNull(selector.select(file, 0, 0.55f, 0.54f, 0.88f, 0.72f, allowOcr = false))
        assertNull(selector.select(file, 0, 0.55f, 0.54f, 0.88f, 0.72f, allowOcr = true))
    }

    @Test
    fun nativeTextSelectionReturnsTextAndNormalizedGeometry() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        createPdf()
        val selection = requireNotNull(PdfSandboxClient(context).selectText(file, 0, 0.1f, 0.11f, 0.95f, 0.11f))
        assertTrue(selection.text.contains("Alpha"))
        assertTrue(selection.text.contains("Gamma"))
        assertFalse(selection.isOcr)
        assertTrue(selection.bounds.all { it.left >= 0f && it.right <= 1f && it.top >= 0f && it.bottom <= 1f })
    }

    @Test
    fun imageOnlyPdfFallsBackToWordOcr() = runBlocking {
        createPdf(imageOnly = true)
        val selection = requireNotNull(PdfTextSelector(context).select(file, 0, 0.08f, 0.11f, 0.95f, 0.11f))
        assertTrue(selection.isOcr)
        assertEquals("Alpha Beta Gamma", selection.text)
        assertEquals(3, selection.bounds.size)
        assertNull(PdfTextSelector(context).select(file, 0, 0.8f, 0.8f, 0.9f, 0.9f))
    }

    @Test
    fun disabledOcrDoesNotPretendOldSdkHasNativeSelection() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT < 35)
        createPdf()
        val failure = runCatching {
            PdfTextSelector(context).select(file, 0, 0f, 0f, 1f, 1f, allowOcr = false)
        }.exceptionOrNull()
        assertTrue(failure is UnsupportedOperationException)
        assertEquals(PdfProtocol.ERROR_SELECTION_UNSUPPORTED, failure?.message)
    }

    @Test
    fun corruptPdfFailureIsNotHiddenAsAnEmptySelection() = runBlocking {
        file.writeText("Not a PDF")
        val failure = runCatching { PdfTextSelector(context).select(file, 0, 0f, 0f, 1f, 1f) }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun invalidCoordinatesCloseReceivedDescriptorAndReturnAnError() {
        createPdf()
        val (reader, writer) = ParcelFileDescriptor.createPipe()
        try {
            withBoundPdfSandbox(context, file) { service ->
                val result = service.selectText(reader, 0, Float.NaN, 0f, 1f, 1f)
                assertFalse(result.getBoolean(PdfProtocol.SUCCESS))
                assertEquals(PdfProtocol.ERROR_INVALID, result.getString(PdfProtocol.ERROR))
                assertPipeReaderClosed(reader, writer)
            }
        } finally {
            reader.close()
            writer.close()
        }
    }

    @Test
    fun renderLimitClosesBothDescriptorsBeforeParsing() {
        createPdf()
        val (sourceReader, sourceWriter) = ParcelFileDescriptor.createPipe()
        val (outputReader, outputWriter) = ParcelFileDescriptor.createPipe()
        try {
            withBoundPdfSandbox(context, file) { service ->
                val result = service.renderPage(sourceReader, 0, 0, 1, outputWriter)
                assertFalse(result.getBoolean(PdfProtocol.SUCCESS))
                assertEquals(PdfProtocol.ERROR_LIMIT, result.getString(PdfProtocol.ERROR))
                assertPipeReaderClosed(sourceReader, sourceWriter)
                assertTrue(outputWriter.fileDescriptor.valid())
                outputWriter.close()
                awaitPipeClosure(outputReader, OsConstants.POLLHUP)
                Os.fcntlInt(outputReader.fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
                ParcelFileDescriptor.AutoCloseInputStream(outputReader).use { input ->
                    assertEquals(-1, input.read())
                }
            }
        } finally {
            sourceReader.close()
            sourceWriter.close()
            outputReader.close()
            outputWriter.close()
        }
    }

    @Test
    fun binderSelectionPayloadRejectsOversizedAndMalformedGeometry() {
        fun payload(text: String, bounds: FloatArray) = Bundle().apply {
            putBoolean(PdfProtocol.SELECTION_FOUND, true)
            putString(PdfProtocol.SELECTION_TEXT, text)
            putFloatArray(PdfProtocol.SELECTION_BOUNDS, bounds)
        }
        assertThrows(IOException::class.java) { decodePdfTextSelection(payload("x".repeat(10_001), floatArrayOf(0f, 0f, 1f, 1f))) }
        assertThrows(IOException::class.java) { decodePdfTextSelection(payload("x", FloatArray(8_004))) }
        assertThrows(IOException::class.java) { decodePdfTextSelection(payload("x", floatArrayOf(0f, 0f, 1f))) }
        assertThrows(IOException::class.java) { decodePdfTextSelection(payload("x", floatArrayOf(0f, Float.NaN, 1f, 1f))) }
        assertThrows(IOException::class.java) { decodePdfTextSelection(Bundle()) }
        assertThrows(IOException::class.java) {
            decodePdfTextSelection(Bundle().apply { putString(PdfProtocol.SELECTION_FOUND, "false") })
        }
    }

    @Test
    fun oversizedNativeSelectionFailsBeforeBinderSerialization() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 2_000, 1).create())
            val paint = Paint().apply { color = Color.BLACK; textSize = 6f }
            repeat(120) { page.canvas.drawText("0123456789".repeat(12), 20f, 30f + it * 12f, paint) }
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        val failure = runCatching { PdfSandboxClient(context).selectText(file, 0, 0f, 0f, 1f, 1f) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(PdfProtocol.ERROR_LIMIT, failure?.message)
    }

    @Test
    fun bitmapOcrPreservesLineDefaultAndCallerBitmapOwnership() = runBlocking {
        val bitmap = textBitmap()
        try {
            val lines = recognizeBitmap(bitmap)
            val words = recognizeBitmap(bitmap, wordLevel = true)
            assertFalse(bitmap.isRecycled)
            assertEquals(1, lines.regions.size)
            assertEquals(3, words.regions.size)
            assertEquals("Alpha Beta Gamma", words.regions.joinToString(" ") { it.text })
        } finally { bitmap.recycle() }
    }

    private fun textBitmap(): Bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        Canvas(this).drawText("Alpha Beta Gamma", 60f, 100f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 36f })
    }

    private fun createPdf(imageOnly: Boolean = false, drawDiagram: Boolean = false) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            if (imageOnly) {
                val bitmap = textBitmap()
                try { page.canvas.drawBitmap(bitmap, 0f, 0f, null) } finally { bitmap.recycle() }
            } else {
                page.canvas.drawText("Alpha Beta Gamma", 60f, 100f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 36f })
            }
            if (drawDiagram) page.canvas.drawRect(350f, 450f, 500f, 550f, Paint().apply { color = Color.BLUE })
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
    }
}
