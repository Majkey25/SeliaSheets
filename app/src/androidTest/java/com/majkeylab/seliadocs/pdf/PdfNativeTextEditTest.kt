package com.majkeylab.seliadocs.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
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
        assertTrue("Unexpected fixture object count", objects.size in 2..16)
        assertTrue(objects.all { it.first >= 0 })
        assertEquals(objects.size, objects.map { it.first }.toSet().size)
        val text = objects.mapNotNull { entry ->
            (entry.second as? PdfPageTextObject)?.let { entry.first to it.text }
        }.toMap()
        assertEquals("Fixture must have two separate text objects", 2, text.size)
        assertTrue(text.values.all { it.length <= 128 })
        return text
    }

    private fun <T> withRenderer(file: File, action: (PdfRenderer) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use(action)
        }

    private fun createPdf() {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(400, 240, 1).create())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f }
            page.canvas.drawText(ORIGINAL, 30f, 70f, paint)
            paint.color = Color.BLUE
            page.canvas.drawText(NEIGHBOR, 30f, 170f, paint)
            document.finishPage(page)
            source.outputStream().use(document::writeTo)
        } finally { document.close() }
    }

    private companion object {
        const val ORIGINAL = "Alpha Beta"
        const val REPLACEMENT = "Beta Alpha"
        const val NEIGHBOR = "Gamma Delta"
    }
}
