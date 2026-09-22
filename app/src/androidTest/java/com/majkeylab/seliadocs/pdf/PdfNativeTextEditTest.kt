package com.majkeylab.seliadocs.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.component.PdfPageTextObject
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class PdfNativeTextEditTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = File(context.cacheDir, "pdf-native-source-${System.nanoTime()}.pdf")
    private val edited = File(context.cacheDir, "pdf-native-edited-${System.nanoTime()}.pdf")

    @After
    fun cleanUp() { source.delete(); edited.delete() }

    @Test
    fun nativeTextObjectEditSurvivesSaveWithoutChangingSourceOrNeighbor() {
        createPdf()
        val originalBytes = source.readBytes()
        val originalObjects = withRenderer(source) { renderer ->
            val before = renderer.openPage(0).use { page ->
                textObjects(page).also { assertEquals(it, textObjects(page)) }
            }
            assertEquals(setOf(ORIGINAL, NEIGHBOR), before.values.toSet())
            val targetId = before.entries.single { it.value == ORIGINAL }.key
            renderer.openPage(0).use { page ->
                assertEquals("Object IDs must survive reopening an unchanged page", before, textObjects(page))
                val target = page.pageObjects.single { it.first == targetId }.second as PdfPageTextObject
                // Reuse the source glyphs to test editing without requiring a new font.
                target.setText(REPLACEMENT)
                assertTrue("Native PDF text update failed", page.updatePageObject(targetId, target))
            }
            ParcelFileDescriptor.open(
                edited,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY,
            ).use { renderer.write(it, false) }
            before
        }
        assertTrue(edited.length() > 0)
        assertArrayEquals("Saving an edited copy must preserve the original bytes", originalBytes, source.readBytes())
        withRenderer(source) { renderer ->
            renderer.openPage(0).use { page ->
                assertEquals(originalObjects.values.toSet(), textObjects(page).values.toSet())
                val text = page.textContents.joinToString("\n") { it.text }
                assertTrue(text.contains(ORIGINAL))
                assertFalse(text.contains(REPLACEMENT))
            }
        }
        withRenderer(edited) { renderer ->
            assertEquals(1, renderer.pageCount)
            renderer.openPage(0).use { page ->
                // Re-enumerate after saving; object IDs are not promised across document mutations.
                val objects = textObjects(page)
                assertEquals(setOf(REPLACEMENT, NEIGHBOR), objects.values.toSet())
                val text = page.textContents.joinToString("\n") { it.text }
                assertTrue(text.contains(REPLACEMENT))
                assertTrue(text.contains(NEIGHBOR))
                assertFalse(text.contains(ORIGINAL))
                val bitmap = Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pixels = IntArray(400 * 80)
                    bitmap.getPixels(pixels, 0, 400, 0, 20, 400, 80)
                    assertTrue("The edited text must remain visible", pixels.any {
                        Color.alpha(it) > 0 && Color.red(it) < 128 && Color.green(it) < 128 && Color.blue(it) < 128
                    })
                } finally { bitmap.recycle() }
            }
        }
    }

    private fun textObjects(page: PdfRenderer.Page): Map<Int, String> {
        val objects = page.pageObjects
        val details = "count=${objects.size}, types=" + objects.take(16).joinToString {
            "${it.first}:${it.second.javaClass.simpleName}/${it.second.pdfObjectType}"
        }
        assertTrue("Fixture exceeds object budget: $details", objects.size <= 16)
        assertTrue("Invalid object ID: $details", objects.all { it.first >= 0 })
        assertEquals("Duplicate object IDs: $details", objects.size, objects.map { it.first }.toSet().size)
        val text = objects.mapNotNull { entry ->
            (entry.second as? PdfPageTextObject)?.let { entry.first to it.text }
        }.toMap()
        assertEquals("Fixture must have two separate text objects: $details", 2, text.size)
        assertTrue(text.values.all { it.length <= 128 })
        return text
    }

    private fun <T> withRenderer(file: File, action: (PdfRenderer) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use(action)
        }

    private fun createPdf() {
        // Direct standard-font text objects avoid Skia's font subsetting and content grouping.
        val content = "BT /F1 28 Tf 0 g 30 170 Td ($ORIGINAL) Tj ET\n" +
            "BT /F1 28 Tf 0 0 1 rg 30 70 Td ($NEIGHBOR) Tj ET\n"
        val bodies = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 240] " +
                "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
            "<< /Length ${content.length} >>\nstream\n${content}endstream",
        )
        val pdf = StringBuilder("%PDF-1.4\n")
        val offsets = bodies.mapIndexed { index, body ->
            val offset = pdf.length
            pdf.append("${index + 1} 0 obj\n$body\nendobj\n")
            offset
        }
        val xref = pdf.length
        pdf.append("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { pdf.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        pdf.append("trailer\n<< /Size ${bodies.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        check(pdf.all { it.code < 128 }) { "Fixture offsets require ASCII bytes" }
        source.writeText(pdf.toString(), Charsets.US_ASCII)
    }

    private companion object {
        const val ORIGINAL = "Alpha Beta"
        const val REPLACEMENT = "Beta Alpha"
        const val NEIGHBOR = "Gamma Delta"
    }
}
