package com.majkeylab.seliadocs.documents

import android.app.Application
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.MainActivity
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PowerPointRendererFailureTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun missingChartsInSlidesGroupsAndMastersAbortInsteadOfSavingPlaceholders() = runBlocking {
        for (location in listOf("slide", "group", "master")) rejectChart(location, missing = true)
    }

    @Test
    fun unsupportedChartWithoutSeriesAbortsInsteadOfSavingPlaceholder() = runBlocking {
        rejectChart("slide", missing = false)
    }

    private suspend fun rejectChart(location: String, missing: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(app.cacheDir, "presentation-failure-${System.nanoTime()}").apply { mkdirs() }
        lateinit var host: ViewGroup
        var childCount = 0
        activity.scenario.onActivity {
            host = it.findViewById(android.R.id.content)
            childCount = host.childCount
        }
        try {
            val source = File(directory, "source.pptx")
            val base = File(directory, "base.pptx").also(::createPresentationFixture)
            val parts = ZipFile(base).use { zip ->
                zip.entries().asSequence().associate { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }.toMutableMap()
            }
            val chartPath = "ppt/charts/chart1.${if (missing) "part" else "xml"}"
            // A valid OPC XML part name outside the vendor's .xml-only chart map.
            parts[chartPath] = """<c:chartSpace xmlns:c="$CHART"><c:chart><c:plotArea/></c:chart></c:chartSpace>""".toByteArray()
            var overrides = """<Override PartName="/$chartPath" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>"""
            val frame = """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="99" name="Chart"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x="914400" y="914400"/><a:ext cx="3657600" cy="2743200"/></p:xfrm><a:graphic><a:graphicData uri="$CHART"><c:chart xmlns:c="$CHART" xmlns:r="$REL" r:id="chart"/></a:graphicData></a:graphic></p:graphicFrame>"""
            val slidePath = "ppt/slides/slide1.xml"
            if (location == "master") {
                parts["ppt/slides/_rels/slide1.xml.rels"] = rels("$REL/slideLayout", "../slideLayouts/slideLayout1.xml")
                parts["ppt/slideLayouts/slideLayout1.xml"] = """<p:sldLayout xmlns:p="$PML"><p:cSld><p:spTree/></p:cSld></p:sldLayout>""".toByteArray()
                parts["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = rels("$REL/slideMaster", "../slideMasters/slideMaster1.xml")
                parts["ppt/slideMasters/slideMaster1.xml"] = """<p:sldMaster xmlns:p="$PML" xmlns:a="$DRAWING"><p:cSld><p:spTree>$frame</p:spTree></p:cSld></p:sldMaster>""".toByteArray()
                parts["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = rels("$REL/chart", "../charts/${chartPath.substringAfterLast('/')}")
                overrides += """<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>"""
            } else {
                val shape = if (location == "group") """<p:grpSp><p:nvGrpSpPr><p:cNvPr id="98" name="Group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="9144000" cy="5143500"/><a:chOff x="0" y="0"/><a:chExt cx="9144000" cy="5143500"/></a:xfrm></p:grpSpPr>$frame</p:grpSp>""" else frame
                parts[slidePath] = parts.getValue(slidePath).toString(Charsets.UTF_8).replace("</p:spTree>", "$shape</p:spTree>").toByteArray()
                parts["ppt/slides/_rels/slide1.xml.rels"] = rels("$REL/chart", "../charts/${chartPath.substringAfterLast('/')}")
            }
            parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").toString(Charsets.UTF_8).replace("</Types>", "$overrides</Types>").toByteArray()
            ZipOutputStream(source.outputStream()).use { zip ->
                for ((name, bytes) in parts) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val metadata = PowerPointPreflight.inspect(source)
            val output = File(directory, "failed.pdf")
            val failure = runCatching { PowerPointRenderer.render(host, source, output, metadata) }.exceptionOrNull()
            assertTrue("$location chart must fail explicitly: $failure", failure is IOException && failure.message.orEmpty().contains("unsupported content"))
            assertTrue("Failed chart must not produce an importable PDF", !output.exists())
            activity.scenario.onActivity { assertTrue("Temporary WebView leaked", host.childCount == childCount) }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun rels(type: String, target: String): ByteArray =
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="chart" Type="$type" Target="$target"/></Relationships>""".toByteArray()

    private companion object {
        const val PML = "http://schemas.openxmlformats.org/presentationml/2006/main"
        const val DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
        const val CHART = "http://schemas.openxmlformats.org/drawingml/2006/chart"
        const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    }
}
