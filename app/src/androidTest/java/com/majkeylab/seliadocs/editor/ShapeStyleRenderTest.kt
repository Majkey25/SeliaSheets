package com.majkeylab.seliadocs.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.data.AnnotationRect
import com.majkeylab.seliadocs.data.AssetStore
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.NotebookContent
import com.majkeylab.seliadocs.data.NotebookEntity
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.pdf.PdfSandboxClient
import com.majkeylab.seliadocs.ui.SeliaDocsTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ShapeStyleRenderTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shapePreviewScalesPageSpaceWidthAndAppliesArrowOpacityOnce() {
        rule.setContent {
            SeliaDocsTheme {
                Surface(color = androidx.compose.ui.graphics.Color(0xFFFFFEFA)) {
                    Column {
                        listOf(1, 2).forEach { scale ->
                            CleanShape(shape("LINE").copy(width = 100f, height = 30f),
                                Modifier.size((100 * scale).dp, (30 * scale).dp).testTag("line-$scale"))
                        }
                        CleanShape(shape("ARROW").copy(width = 100f, height = 30f),
                            Modifier.size(200.dp, 60.dp).testTag("arrow"))
                    }
                }
            }
        }
        listOf(1, 2).forEach { scale ->
            val image = rule.onNodeWithTag("line-$scale").captureToImage().toPixelMap()
            assertHalfRed(image[image.width / 2, image.height / 2].toArgb())
            val width = (0 until image.height).count { y -> isRed(image[image.width / 2, y].toArgb()) }
            assertTrue("Width must stay 12 page points at scale $scale: $width/${image.width}",
                abs(width - image.width * 12f / 100f) <= 2f)
        }
        val arrow = rule.onNodeWithTag("arrow").captureToImage().toPixelMap()
        assertHalfRed(arrow[arrow.width - 2, arrow.height / 2].toArgb())
    }

    @Test
    fun pdfAndPngKeepShapeColorOpacityWidthAndSinglePaintArrowJoin() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "shape-style-${System.nanoTime()}").apply { mkdirs() }
        val assets = AssetStore(File(root, "assets"))
        val page = PageEntity("page", "book", 0, "BLANK", 400, 400)
        val elements = listOf(shape("LINE"), shape("ARROW").copy(id = "arrow", y = 180f))
        val content = NotebookContent(
            notebook = NotebookEntity("book", "Style", "SAGE", "SOLID", "BLANK", "PORTRAIT", false, false, 1, 1, null),
            pages = listOf(page), strokes = emptyList(), elements = elements,
        )
        try {
            val pdf = File(root, "shape.pdf")
            pdf.outputStream().use { PdfExporter(assets).write(content, it) }
            val raster = PdfSandboxClient(context).renderPage(pdf, 0, 400, 400)
            try { assertExport(raster) } finally { raster.recycle() }
            val capture = capturePageExcerpt(context, assets, page, null, emptyList(), elements, emptyList(),
                AnnotationRect(0f, 0f, 1f, 1f))
            val png = requireNotNull(BitmapFactory.decodeFile(capture.file.path))
            try { assertExport(png) } finally { png.recycle() }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyShapeStillExportsOpaqueGraphiteWithThreePointWidth() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        try {
            PdfExporter(AssetStore(context.cacheDir)).renderPage(android.graphics.Canvas(bitmap),
                PageEntity("page", "book", 0, "BLANK", 400, 400), emptyList(),
                listOf(shape("LINE").copy(colorArgb = null, strokeWidth = null)), emptyList(), null)
            assertEquals(Color.rgb(32, 33, 36), bitmap.getPixel(120, 100))
            assertEquals(Color.rgb(255, 254, 250), bitmap.getPixel(120, 104))
        } finally { bitmap.recycle() }
    }

    private fun assertExport(bitmap: Bitmap) {
        assertHalfRed(bitmap.getPixel(120, 100))
        assertHalfRed(bitmap.getPixel(259, 200))
        val width = (80..120).count { y -> isRed(bitmap.getPixel(120, y)) }
        assertTrue("Exported line width was $width, expected 12 points", width in 11..13)
    }

    private fun assertHalfRed(color: Int) {
        assertTrue("Expected 50% red, got ${Integer.toHexString(color)}",
            Color.red(color) > 245 && Color.green(color) in 110..145 && Color.blue(color) in 110..145)
    }

    private fun isRed(color: Int) = Color.red(color) > Color.green(color) + 60

    private fun shape(kind: String) = ElementEntity("shape", "page", 0, "SHAPE", 60f, 80f, 200f, 40f,
        0f, null, null, kind, null, null, colorArgb = 0x80FF0000.toInt(), strokeWidth = 12f)
}
