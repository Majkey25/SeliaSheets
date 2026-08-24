package cz.majkey.perko.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.majkey.perko.data.AssetStore
import cz.majkey.perko.data.CoverColor
import cz.majkey.perko.data.CoverPattern
import cz.majkey.perko.data.ElementEntity
import cz.majkey.perko.data.ElementKind
import cz.majkey.perko.data.NotebookContent
import cz.majkey.perko.data.NotebookEntity
import cz.majkey.perko.data.PageEntity
import cz.majkey.perko.data.PageOrientation
import cz.majkey.perko.data.PaperTemplate
import cz.majkey.perko.data.StrokeEntity
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfExporterTest {
    @Test
    fun everyPageAndInkIsWritten() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(context.cacheDir, "perko-${System.nanoTime()}.pdf")
        val assetRoot = File(context.cacheDir, "pdf-assets-${System.nanoTime()}")
        val assetStore = AssetStore(assetRoot)
        val imageFile = assetStore.file("image.png")
        assetStore.prepare()
        Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.BLUE)
            imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val page =
            PageEntity("page", "notebook", 0, PaperTemplate.RULED.name, 595, 842)
        val stroke =
            Stroke(
                InkCodec.createBrush(BrushKind.PRESSURE_PEN, 0xFF202124.toInt(), 4f),
                MutableStrokeInputBatch()
                    .add(InputToolType.STYLUS, 50f, 80f, 0L, 0.01f, 0.7f, 0f, 0f)
                    .add(InputToolType.STYLUS, 500f, 700f, 16L, 0.01f, 0.7f, 0f, 0f),
            )
        val encoded = InkCodec.encode(stroke)
        val content =
            NotebookContent(
                notebook =
                    NotebookEntity(
                        "notebook",
                        "Physics",
                        CoverColor.PERIWINKLE.name,
                        CoverPattern.SOLID.name,
                        PaperTemplate.RULED.name,
                        PageOrientation.PORTRAIT.name,
                        false,
                        false,
                        1L,
                        1L,
                        null,
                    ),
                pages = listOf(page, page.copy(id = "page-2", pageIndex = 1)),
                strokes =
                    listOf(
                        StrokeEntity(
                            "stroke",
                            page.id,
                            0,
                            encoded.brushKind.name,
                            encoded.colorArgb,
                            encoded.size,
                            encoded.epsilon,
                            encoded.inputs,
                        ),
                    ),
                elements =
                    listOf(
                        ElementEntity(
                            "text",
                            page.id,
                            0,
                            ElementKind.TEXT.name,
                            60f,
                            120f,
                            300f,
                            80f,
                            0f,
                            "Exported text",
                            null,
                            null,
                            null,
                            null,
                        ),
                        ElementEntity(
                            "image",
                            "page-2",
                            0,
                            ElementKind.IMAGE.name,
                            80f,
                            180f,
                            320f,
                            240f,
                            0f,
                            null,
                            imageFile.name,
                            null,
                            null,
                            null,
                        ),
                    ),
            )

        output.outputStream().use { stream ->
            PdfExporter(assetStore).write(content, stream)
        }

        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(2, renderer.pageCount)
                assertTrue(output.length() > 2_000)
            }
        }
        output.delete()
        assetRoot.deleteRecursively()
    }
}
