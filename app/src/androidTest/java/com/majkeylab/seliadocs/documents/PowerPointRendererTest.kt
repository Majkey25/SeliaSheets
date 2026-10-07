package com.majkeylab.seliadocs.documents

import android.app.Application
import android.graphics.Color
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.majkeylab.seliadocs.pdf.PdfSandboxClient
import com.majkeylab.seliadocs.MainActivity
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PowerPointRendererTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private lateinit var renderHost: ViewGroup

    @Before fun attachHost() {
        activity.scenario.onActivity { renderHost = it.findViewById(android.R.id.content) }
    }

    @Test
    fun detachedWindowCancelsRenderingAndRemovesTheTemporaryView(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(app.cacheDir, "presentation-cancel-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(directory, "lecture.pptx").also(::createPresentationFixture)
            val metadata = PowerPointPreflight.inspect(source)
            var initialChildren = 0
            activity.scenario.onActivity { initialChildren = renderHost.childCount }
            val rendering = async { PowerPointRenderer.render(renderHost, source, File(directory, "cancelled.pdf"), metadata) }
            withTimeout(5_000) {
                var detached = false
                while (!detached) {
                    activity.scenario.onActivity {
                        val child = renderHost.getChildAt(0)
                        if (child is WebView) {
                            renderHost.removeView(child)
                            detached = true
                        }
                    }
                    if (!detached) delay(5)
                }
            }
            assertTrue(runCatching { rendering.await() }.exceptionOrNull() is CancellationException)
            activity.scenario.onActivity { assertEquals(initialChildren, renderHost.childCount) }
            val retry = File(directory, "retry.pdf")
            PowerPointRenderer.render(renderHost, source, retry, metadata)
            assertEquals(2, PdfSandboxClient(app).inspect(retry).pages.size)
            activity.scenario.onActivity { assertEquals(initialChildren, renderHost.childCount) }
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test
    fun commonOfficePartsKeepTablesPicturesAndChartsVisible() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(app.cacheDir, "presentation-rich-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(directory, "lecture.pptx")
            InstrumentationRegistry.getInstrumentation().context.assets.open("presentations/lecture.pptx").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val metadata = PowerPointPreflight.inspect(source)
            val output = File(directory, "lecture.pdf")
            PowerPointRenderer.render(renderHost, source, output, metadata)
            val sandbox = PdfSandboxClient(app)
            assertEquals(2, sandbox.inspect(output).pages.size)
            val evidence = File(requireNotNull(app.getExternalFilesDir(null)), "presentation-qa").apply { mkdirs() }
            output.copyTo(File(evidence, "lecture.pdf"), overwrite = true)
            for (index in 0..1) {
                val image = sandbox.renderPage(output, index, 1280, 720)
                try {
                    File(evidence, "slide-${index + 1}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (index == 0) {
                        val pixel = image.getPixel(100, 610)
                        assertTrue("Slide shape missing", Color.green(pixel) > 120 && Color.red(pixel) < 80)
                        assertTrue("Slide image missing", (940 until 1080 step 4).any { x ->
                            (160 until 300 step 4).any { y -> Color.blue(image.getPixel(x, y)) < 230 }
                        })
                    } else {
                        var bluePixels = 0
                        for (x in 80 until 1100 step 4) for (y in 160 until 580 step 4) {
                            val pixel = image.getPixel(x, y)
                            if (Color.blue(pixel) > 120 && Color.red(pixel) < 80) bluePixels++
                        }
                        assertTrue("Chart bars missing ($bluePixels)", bluePixels > 500)
                    }
                } finally {
                    image.recycle()
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun slidesRenderLocallyToSeparateCorrectlySizedPdfPages() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(app.cacheDir, "presentation-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(directory, "lecture.pptx")
            createPresentationFixture(source)
            val output = File(directory, "lecture.pdf")
            val metadata = PowerPointPreflight.inspect(source)
            assertEquals(2, metadata.slideCount)
            PowerPointRenderer.render(renderHost, source, output, metadata)
            val sandbox = PdfSandboxClient(app)
            val pdf = sandbox.inspect(output)
            assertEquals(2, pdf.pages.size)
            assertEquals(720, pdf.pages[0].width)
            assertEquals(405, pdf.pages[0].height)
            for (index in 0..1) {
                val image = sandbox.renderPage(output, index, 960, 540)
                try {
                    val pixel = image.getPixel(150, 170)
                    if (index == 0) assertTrue("First slide red shape missing: $pixel", Color.red(pixel) > 200 && Color.blue(pixel) < 50)
                    else assertTrue("Second slide blue shape missing: $pixel", Color.blue(pixel) > 200 && Color.red(pixel) < 50)
                    assertTrue("Slide title missing", (100 until 450 step 2).any { x ->
                        (20 until 90 step 2).any { y -> Color.red(image.getPixel(x, y)) < 100 }
                    })
                } finally {
                    image.recycle()
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}

internal fun createPresentationFixture(file: File) {
    val parts = linkedMapOf(
        "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/><Override PartName="/ppt/slides/slide2.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/></Types>""",
        "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>""",
        "ppt/presentation.xml" to """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="rId1"/><p:sldId id="257" r:id="rId2"/></p:sldIdLst><p:sldSz cx="9144000" cy="5143500"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>""",
        "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide2.xml"/></Relationships>""",
    )
    for (index in 1..2) {
        val color = if (index == 1) "FF0000" else "0000FF"
        parts["ppt/slides/slide$index.xml"] = """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr><p:sp><p:nvSpPr><p:cNvPr id="2" name="Color rectangle"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="914400" y="1371600"/><a:ext cx="1828800" cy="914400"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="$color"/></a:solidFill></p:spPr></p:sp><p:sp><p:nvSpPr><p:cNvPr id="3" name="Title"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="914400" y="182880"/><a:ext cx="6400800" cy="731520"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="en-US" sz="3200"><a:solidFill><a:srgbClr val="000000"/></a:solidFill><a:latin typeface="Arial"/></a:rPr><a:t>Lecture $index</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"""
    }
    ZipOutputStream(file.outputStream()).use { zip ->
        parts.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
