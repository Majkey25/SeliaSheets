package com.majkeylab.seliadocs.documents

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PowerPointPreflightTest {
    @Test
    fun readsSlideCountAndDimensionsInCssPixels() {
        assertEquals(PowerPointMetadata(1280.0, 720.0, 2), inspect(parts(slides = 2)))
    }

    @Test
    fun unusedMediaTypeDeclarationDoesNotTurnOrdinarySlidesIntoVideo() {
        val deck = replace(parts(), "[Content_Types].xml") {
            it.replace("</Types>", "<Default Extension=\"mp4\" ContentType=\"video/mp4\"/></Types>")
        }
        assertEquals(1, inspect(deck).slideCount)
        rejects(deck + ("ppt/media/video.mp4" to byteArrayOf(1, 2, 3)))
    }

    @Test
    fun acceptsOrdinaryChartCachesTablesAndHyperlinks() {
        val chart = """<c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart><c:plotArea><c:barChart><c:ser><c:val><c:numCache><c:pt idx="0"><c:v>42</c:v></c:pt></c:numCache></c:val></c:ser></c:barChart></c:plotArea></c:chart></c:chartSpace>"""
        val deck = parts(slideBody = """<a:tbl xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:tr h="100"><a:tc><a:txBody><a:p><a:r><a:t>Čeština &amp; text</a:t></a:r></a:p></a:txBody></a:tc></a:tr></a:tbl>""") + listOf(
            "ppt/charts/chart1.xml" to chart.toByteArray(),
            "ppt/charts/_rels/chart1.xml.rels" to relationships(relationship("book", "$REL/package", "../embeddings/data.xlsx")).toByteArray(),
            "ppt/embeddings/data.xlsx" to archive(listOf("[Content_Types].xml" to "<Types/>".toByteArray())),
            "ppt/slides/_rels/slide1.xml.rels" to relationships(
                relationship("chart", "$REL/chart", "../charts/chart1.xml") +
                    relationship("link", "$REL/hyperlink", "https://example.com", "External"),
            ).toByteArray(),
        )
        assertEquals(1, inspect(deck).slideCount)
    }

    @Test
    fun rejectsMissingPartsAndUnsupportedPresentationKinds() {
        rejects(parts().filterNot { it.first == "ppt/slides/slide1.xml" })
        rejects(parts().filterNot { it.first == "_rels/.rels" })
        for (type in listOf(
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow.main+xml",
            "application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml",
        )) rejects(parts(mainType = type))
        assertThrows(IOException::class.java) { inspectArchive("not a ZIP".toByteArray()) }
    }

    @Test
    fun rejectsWrongDimensionsDuplicateSlidesAndSlideOverflow() {
        rejects(parts(dimensions = "cx=\"0\" cy=\"6858000\""))
        rejects(parts(dimensions = "cx=\"99999999999\" cy=\"6858000\""))
        rejects(parts(dimensions = "cx=\"12192000\" cy=\"914400\""))
        rejects(parts(slides = 101))
        rejects(parts(slides = 0))
        rejects(replace(parts(), "ppt/presentation.xml") { it.replace("<p:sldId id=\"256\" r:id=\"s1\"/>", "<p:sldId id=\"256\" r:id=\"s1\"/><p:sldId id=\"257\" r:id=\"s1\"/>") })
    }

    @Test
    fun rejectsTraversalCaseAmbiguityAndExactDuplicateNames() {
        for (name in listOf("../bad.xml", "/bad.xml", "ppt/../bad.xml", "ppt\\bad.xml", "ppt/%2e%2e/bad.xml", "PPT/presentation.xml")) {
            rejects(parts() + (name to "<x/>".toByteArray()))
        }
        val bytes = archive(parts() + listOf("sameA.xml" to "<x/>".toByteArray(), "sameB.xml" to "<x/>".toByteArray()))
        val name = "sameB.xml".toByteArray()
        for (offset in 0..bytes.size - name.size) {
            if (name.indices.all { bytes[offset + it] == name[it] }) bytes[offset + 4] = 'A'.code.toByte()
        }
        assertThrows(IOException::class.java) { inspectArchive(bytes) }
    }

    @Test
    fun rejectsCorruptCrcLocalCentralDisagreementAndMissingTrailer() {
        val original = archive(parts())
        val badCrc = original.copyOf()
        val central = centralRecord(original)
        badCrc[central + 16] = (badCrc[central + 16].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { inspectArchive(badCrc) }
        val badLocal = original.copyOf()
        badLocal[30] = 'X'.code.toByte()
        assertThrows(IOException::class.java) { inspectArchive(badLocal) }
        assertThrows(IOException::class.java) { inspectArchive(original.copyOf(original.size - 22)) }
    }

    @Test
    fun rejectsForgedDeclaredSizeAndActualEntryOverflow() {
        val bytes = archive(parts())
        val central = centralRecord(bytes)
        for (index in 0..3) bytes[central + 24 + index] = 0
        assertThrows(IOException::class.java) { inspectArchive(bytes) }
        rejects(parts() + ("large.xml" to ByteArray(PowerPointPreflight.MAX_PART_BYTES + 1)))
    }

    @Test
    fun rejectsCentralOffsetsPointingToAnotherValidLocalPayload() {
        val bytes = archive(parts() + listOf("docProps/a.xml" to "<safeA/>".toByteArray(), "docProps/b.xml" to "<safeB/>".toByteArray()))
        val records = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        records.putInt(centralRecord(bytes, "docProps/a.xml") + 42, records.getInt(centralRecord(bytes, "docProps/b.xml") + 42))
        assertThrows(IOException::class.java) { inspectArchive(bytes) }
    }

    @Test
    fun rejectsUnicodeZipNameAliasesThatDifferAcrossParsers() {
        val failure = assertThrows(IOException::class.java) {
            inspectArchive(archive(parts(), unicodeAliasFor = "ppt/slides/slide1.xml"))
        }
        assertTrue(failure.message.orEmpty().contains("filename encoding"))
    }

    @Test
    fun boundsCompressedBytesExpandedTotalAndEntryCount() {
        assertThrows(IOException::class.java) { inspectArchive(archive(parts()) + ByteArray(PowerPointPreflight.MAX_INPUT_BYTES)) }
        val repeated = ByteArray(PowerPointPreflight.MAX_PART_BYTES)
        rejects(parts() + List(8) { "large$it.xml" to repeated })
        rejects(parts() + List(PowerPointPreflight.MAX_ENTRIES) { "part$it.xml" to "<x/>".toByteArray() })
    }

    @Test
    fun rejectsDtdEntitiesMalformedXmlAndDeepTrees() {
        rejects(replace(parts(), "ppt/slides/slide1.xml") { "<!DOCTYPE p:sld [<!ENTITY x SYSTEM 'file:///data/local/tmp/private'>]>$it" })
        rejects(parts(slideBody = "&unknown;"))
        rejects(parts(slideBody = "<x>".repeat(65) + "</x>".repeat(65)))
        rejects(parts(slideBody = "<x>"))
        val utf16 = parts().map { (name, bytes) -> name to if (name == "ppt/slides/slide1.xml") bytes.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_16) else bytes }
        rejects(utf16)
    }

    @Test
    fun boundsXmlEventsAndAttributes() {
        rejects(parts(slideBody = "<x/>".repeat(250_000)))
        rejects(parts(slideBody = "<x value=\"${"a".repeat(32_769)}\"/>"))
    }

    @Test
    fun rejectsMacrosOleActiveXMediaAndExternalDependencies() {
        for (name in listOf("ppt/vbaProject.bin", "ppt/activeX/activeX1.bin", "EncryptedPackage", "ppt/oleObject.bin")) {
            rejects(parts() + (name to byteArrayOf(1)))
        }
        for (kind in listOf("image", "chart", "audio", "video", "officeDocument", "attachedTemplate")) {
            rejects(parts() + ("ppt/slides/_rels/slide1.xml.rels" to relationships(relationship("external", "$REL/$kind", "https://example.com/resource", "External")).toByteArray()))
        }
        for (element in listOf("oleObj", "control", "audio", "video", "snd")) rejects(parts(slideBody = "<p:$element/>"))
        rejects(parts() + ("ppt/slides/_rels/slide1.xml.rels" to relationships(relationship("link", "$REL/hyperlink", "javascript:alert(1)", "External")).toByteArray()))
    }

    @Test
    fun acceptsBoundedRasterAndRejectsVectorAndForgedImageContent() {
        assertEquals(1, inspect(parts() + ("ppt/media/image1.png" to png())).slideCount)
        rejects(parts() + ("ppt/media/image1.svg" to "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>".toByteArray()))
        rejects(parts() + ("ppt/media/image1.png" to "<svg/>".toByteArray()))
    }

    @Test
    fun boundsImageDimensionsAndCumulativePixelCount() {
        rejects(parts() + ("ppt/media/image1.png" to png(width = 16_385, height = 1)))
        val failure = assertThrows(IOException::class.java) {
            inspect(parts() + listOf("ppt/media/image1.png" to png(5000, 4000), "ppt/media/image2.png" to png(5000, 4000)))
        }
        assertTrue(failure.message.orEmpty().contains("32 million pixels"))
    }

    @Test
    fun rejectsAnimatedPngInsteadOfImportingAnArbitraryFrame() {
        val still = png()
        val animation = ByteBuffer.allocate(20).putInt(8).put("acTL".toByteArray()).putInt(2).putInt(0).putInt(0).array()
        ByteBuffer.wrap(animation).putInt(16, CRC32().apply { update(animation, 4, 12) }.value.toInt())
        val animated = still.copyOfRange(0, 33) + animation + still.copyOfRange(33, still.size)
        val failure = assertThrows(IOException::class.java) { inspect(parts() + ("ppt/media/image1.png" to animated)) }
        assertTrue(failure.message.orEmpty().contains("Animated images"))
    }

    @Test
    fun rejectsUnreferencedEmbeddedWorkbooksAndCompressedFonts() {
        rejects(parts() + ("ppt/embeddings/data.xlsx" to archive(listOf("x" to byteArrayOf(1)))))
        val eot = ByteBuffer.allocate(82).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(0, 82).putInt(12, 4).putShort(34, 0x504c.toShort()).array()
        rejects(parts() + ("ppt/fonts/font1.fntdata" to eot))
    }

    private fun inspect(entries: List<Pair<String, ByteArray>>) = inspectArchive(archive(entries))

    private fun inspectArchive(bytes: ByteArray): PowerPointMetadata {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("pptx-preflight-", ".pptx", context.cacheDir)
        try {
            file.writeBytes(bytes)
            return PowerPointPreflight.inspect(file)
        } finally {
            file.delete()
        }
    }

    private fun rejects(entries: List<Pair<String, ByteArray>>) {
        val failure = assertThrows(IOException::class.java) { inspect(entries) }
        assertTrue(failure.message.orEmpty().isNotBlank())
    }

    private fun replace(entries: List<Pair<String, ByteArray>>, target: String, edit: (String) -> String) =
        entries.map { (name, bytes) -> name to if (name == target) edit(bytes.toString(Charsets.UTF_8)).toByteArray() else bytes }

    private fun parts(
        slides: Int = 1,
        dimensions: String = "cx=\"12192000\" cy=\"6858000\"",
        mainType: String = MAIN_TYPE,
        slideBody: String = "",
    ): List<Pair<String, ByteArray>> = buildList {
        val slideTypes = (1..slides).joinToString("") { """<Override PartName="/ppt/slides/slide$it.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""" }
        add("[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="svg" ContentType="image/svg+xml"/><Default Extension="xlsx" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"/><Default Extension="fntdata" ContentType="application/x-fontdata"/><Override PartName="/ppt/presentation.xml" ContentType="$mainType"/><Override PartName="/ppt/charts/chart1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>$slideTypes</Types>""".toByteArray())
        add("_rels/.rels" to relationships(relationship("main", "$REL/officeDocument", "ppt/presentation.xml")).toByteArray())
        val ids = (1..slides).joinToString("") { """<p:sldId id="${255 + it}" r:id="s$it"/>""" }
        add("ppt/presentation.xml" to """<p:presentation xmlns:p="$PML" xmlns:r="$REL"><p:sldIdLst>$ids</p:sldIdLst><p:sldSz $dimensions/></p:presentation>""".toByteArray())
        add("ppt/_rels/presentation.xml.rels" to relationships((1..slides).joinToString("") { relationship("s$it", "$REL/slide", "slides/slide$it.xml") }).toByteArray())
        for (index in 1..slides) add("ppt/slides/slide$index.xml" to """<p:sld xmlns:p="$PML"><p:cSld><p:spTree>$slideBody</p:spTree></p:cSld></p:sld>""".toByteArray())
    }

    private fun relationship(id: String, type: String, target: String, mode: String = "Internal") =
        """<Relationship Id="$id" Type="$type" Target="$target" TargetMode="$mode"/>"""

    private fun relationships(body: String) = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$body</Relationships>"""

    private fun archive(entries: List<Pair<String, ByteArray>>, unicodeAliasFor: String? = null): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (name == unicodeAliasFor) {
                    val alias = "ppt/slides/aliased.xml".toByteArray()
                    entry.extra = ByteBuffer.allocate(9 + alias.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putShort(0x7075.toShort()).putShort((5 + alias.size).toShort()).put(1.toByte())
                        .putInt(CRC32().apply { update(name.toByteArray()) }.value.toInt()).put(alias).array()
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun centralRecord(bytes: ByteArray, name: String? = null): Int = (0 until bytes.size - 46).first { offset ->
        bytes[offset] == 0x50.toByte() && bytes[offset + 1] == 0x4b.toByte() && bytes[offset + 2] == 1.toByte() && bytes[offset + 3] == 2.toByte() &&
            (name == null || name == String(bytes, offset + 46, ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(offset + 28).toInt() and 0xffff, Charsets.UTF_8))
    }

    private fun png(width: Int = 2, height: Int = 2): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        ByteBuffer.wrap(bytes).putInt(16, width).putInt(20, height)
        ByteBuffer.wrap(bytes).putInt(29, CRC32().apply { update(bytes, 12, 17) }.value.toInt())
        return bytes
    }

    private companion object {
        const val PML = "http://schemas.openxmlformats.org/presentationml/2006/main"
        const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val MAIN_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
    }
}
