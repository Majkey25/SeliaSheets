package com.majkeylab.seliadocs.documents

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WordTextCodecTest {
    @Test
    fun roundTripPreservesTextWhitespaceAndExplicitBreaks() {
        val paragraphs = listOf("  Příliš žluťoučký 🦊 <&> \"text\"  ", "", "A\tB\nC\u000CD", "")
        val output = ByteArrayOutputStream()

        WordTextCodec.write(paragraphs, output)

        assertEquals(paragraphs, readArchive(output.toByteArray()).paragraphs)
        val parts = unzip(output.toByteArray())
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"), parts.keys)
        assertTrue(parts.getValue("word/document.xml").toString(Charsets.UTF_8).contains("xml:space=\"preserve\""))
    }

    @Test
    fun followsPackageRelationshipRatherThanAssumingDocumentPath() {
        val document = read(packageEntries(mainPath = "content/body.xml"))
        assertEquals(listOf("Hello"), document.paragraphs)
    }

    @Test
    fun strictWordNamespacePreservesUnicode() {
        val document = read(packageEntries(body = "<w:p><w:r><w:t>Čeština 🦊</w:t></w:r></w:p>", strict = true))
        assertEquals(listOf("Čeština 🦊"), document.paragraphs)
    }

    @Test
    fun paragraphTabStopsDoNotBecomeTextTabs() {
        val document = read(packageEntries("<w:p><w:pPr><w:tabs><w:tab w:val=\"left\" w:pos=\"720\"/></w:tabs></w:pPr><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t></w:r></w:p>"))
        assertEquals(listOf("A\tB"), document.paragraphs)
    }

    @Test
    fun keepsTableParagraphsAndReportsLossyConversion() {
        val body = """
            <w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>Before</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell 1</w:t></w:r></w:p><w:p/></w:tc>
            <w:tc><w:p><w:r><w:t>Cell 2</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            <w:p><w:hyperlink><w:r><w:t>Link</w:t></w:r></w:hyperlink><w:r><w:drawing/></w:r></w:p>
        """.trimIndent()
        val document = read(packageEntries(body))

        assertEquals(listOf("\u000CBefore", "Cell 1", "", "Cell 2", "Link"), document.paragraphs)
        assertTrue(document.warnings.containsAll(setOf(WordTextWarning.TABLES, WordTextWarning.IMAGES, WordTextWarning.HYPERLINKS)))
    }

    @Test
    fun externalHyperlinksKeepOnlyVisibleTextWithoutFetching() {
        val relationships = relationship("link", "$REL/hyperlink", "https://127.0.0.1:1/private", "External")
        val document = read(packageEntries() + ("word/_rels/document.xml.rels" to relationships.toByteArray()))
        assertEquals(listOf("Hello"), document.paragraphs)
        assertTrue(WordTextWarning.HYPERLINKS in document.warnings)
    }

    @Test
    fun malformedXmlAndMissingPackagePartsAreRejected() {
        rejects(listOf("word/document.xml" to documentXml("<w:p/>").toByteArray()))
        rejects(packageEntries(body = "<w:p>"))
        rejects(packageEntries().filterNot { it.first == "word/document.xml" })
        assertThrows(IOException::class.java) { readArchive("not a ZIP".toByteArray()) }
    }

    @Test
    fun traversalAndCaseAmbiguousArchiveNamesAreRejected() {
        listOf("../outside.xml", "/absolute.xml", "word/../outside.xml", "word\\bad.xml", "word/%2e%2e/bad.xml").forEach {
            rejects(packageEntries() + (it to byteArrayOf(1)))
        }
        rejects(packageEntries() + ("WORD/document.xml" to documentXml("<w:p/>").toByteArray()))
    }

    @Test
    fun exactDuplicateArchiveNamesAreRejected() {
        val bytes = archive(packageEntries() + listOf("sameA" to byteArrayOf(1), "sameB" to byteArrayOf(2)))
        val name = "sameB".toByteArray()
        for (offset in 0..bytes.size - name.size) {
            if (name.indices.all { bytes[offset + it] == name[it] }) bytes[offset + 4] = 'A'.code.toByte()
        }
        assertThrows(IOException::class.java) { readArchive(bytes) }
    }

    @Test
    fun missingTrailerAndCentralDirectoryDisagreementAreRejected() {
        val bytes = archive(packageEntries())
        assertThrows(IOException::class.java) { readArchive(bytes.copyOf(bytes.size - 22)) }
        bytes[firstCentralRecord(bytes) + 46] = 'X'.code.toByte()
        assertThrows(IOException::class.java) { readArchive(bytes) }
    }

    @Test
    fun incorrectEntryCrcIsRejected() {
        val bytes = archive(packageEntries())
        val crcOffset = firstCentralRecord(bytes) + 16
        bytes[crcOffset] = (bytes[crcOffset].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { readArchive(bytes) }
    }

    @Test
    fun doctypesAndEntitiesAreRejectedForUtf8AndUtf16() {
        val xml = "<!DOCTYPE w:document [<!ENTITY x SYSTEM 'file:///data/local/tmp/secret'>]>" +
            documentXml("<w:p><w:r><w:t>&x;</w:t></w:r></w:p>")
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16)) {
            rejects(packageEntries().filterNot { it.first == "word/document.xml" } + ("word/document.xml" to xml.toByteArray(charset)))
        }
        rejects(packageEntries("<w:p><w:r><w:t>&unknown;</w:t></w:r></w:p>"))
    }

    @Test
    fun macrosEmbeddedObjectsAndEncryptionMarkersAreRejected() {
        listOf("word/vbaProject.bin", "word/activeX/activeX1.bin", "word/embeddings/object.bin", "EncryptedPackage").forEach {
            rejects(packageEntries() + (it to byteArrayOf(1)))
        }
        val entries = packageEntries().map { (name, bytes) ->
            name to if (name == "[Content_Types].xml") {
                bytes.toString(Charsets.UTF_8).replace(MAIN_TYPE, "application/vnd.ms-word.document.macroEnabled.main+xml").toByteArray()
            } else bytes
        }
        rejects(entries)
    }

    @Test
    fun trackedChangesFieldsAndAmbiguousAlternateContentAreRejected() {
        listOf(
            "<w:ins><w:r><w:t>Inserted</w:t></w:r></w:ins>",
            "<w:del><w:r><w:delText>Deleted</w:delText></w:r></w:del>",
            "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>",
            "<w:fldSimple w:instr=\"DATE\"><w:r><w:t>Cached</w:t></w:r></w:fldSimple>",
            "<w:altChunk/>",
            "<w:r><w:object/></w:r>",
            "<w:r><w:rPr><w:vanish/></w:rPr><w:t>Hidden</w:t></w:r>",
            "<mc:AlternateContent xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\"/>",
        ).forEach { rejects(packageEntries("<w:p>$it</w:p>")) }
    }

    @Test
    fun externalMainImagesTemplatesAndEmbeddedPackagesAreRejected() {
        listOf("officeDocument", "image", "attachedTemplate", "oleObject", "package", "aFChunk").forEach { kind ->
            val rels = relationship("external", "$REL/$kind", "https://127.0.0.1:1/private", "External")
            rejects(packageEntries() + ("word/_rels/document.xml.rels" to rels.toByteArray()))
        }
        val externalRoot = relationship("main", "$REL/officeDocument", "https://127.0.0.1:1/main.xml", "External")
        rejects(packageEntries().filterNot { it.first == "_rels/.rels" } + ("_rels/.rels" to externalRoot.toByteArray()))
        val traversalRoot = relationship("main", "$REL/officeDocument", "../word/document.xml")
        rejects(packageEntries().filterNot { it.first == "_rels/.rels" } + ("_rels/.rels" to traversalRoot.toByteArray()))
    }

    @Test
    fun duplicateRelationshipIdsAreRejected() {
        val rels = """<Relationships xmlns="$PACKAGE_REL"><Relationship Id="same" Type="$REL/hyperlink" Target="https://example.com" TargetMode="External"/><Relationship Id="same" Type="$REL/hyperlink" Target="https://example.org" TargetMode="External"/></Relationships>"""
        rejects(packageEntries() + ("word/_rels/document.xml.rels" to rels.toByteArray()))
    }

    @Test
    fun actualInflatedEntryBytesAndEntryCountAreBounded() {
        rejects(packageEntries() + ("word/media/big.dat" to ByteArray(8 * 1024 * 1024 + 1)))
        rejects(packageEntries() + List(513) { "word/media/$it.dat" to byteArrayOf(0) })
    }

    @Test
    fun compressedBytesAreBoundedEvenAfterZipEnd() {
        val bytes = archive(packageEntries()) + ByteArray(16 * 1024 * 1024)
        assertThrows(IOException::class.java) { readArchive(bytes) }
    }

    @Test
    fun textAndXmlDepthAreBoundedWithoutSilentTruncation() {
        rejects(packageEntries("<w:p><w:r><w:t>${"x".repeat(1_000_001)}</w:t></w:r></w:p>"))
        rejects(packageEntries("<w:customXml>".repeat(65) + "<w:p/>" + "</w:customXml>".repeat(65)))
    }

    @Test
    fun inflatedTotalXmlEventsAndParagraphCountsAreBounded() {
        rejects(packageEntries() + List(5) { "word/media/$it.dat" to ByteArray(8 * 1024 * 1024) })
        rejects(packageEntries("<w:p><w:r>${"<w:t/>".repeat(125_000)}</w:r></w:p>"))
        rejects(packageEntries("<w:p/>".repeat(20_001)))
    }

    @Test
    fun writerNormalizesWindowsLineBreaks() {
        val output = ByteArrayOutputStream()
        WordTextCodec.write(listOf("A\r\nB\rC"), output)
        assertEquals(listOf("A\nB\nC"), readArchive(output.toByteArray()).paragraphs)
    }

    @Test
    fun writerRejectsIllegalXmlCharactersBeforeWriting() {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { WordTextCodec.write(listOf("bad\u0000text"), output) }
        assertEquals(0, output.size())
        assertThrows(IOException::class.java) { WordTextCodec.write(listOf("bad\uD800text"), output) }
    }

    @Test
    fun writerRejectsSerializedByteAndEventOverflowBeforeTouchingOutput() {
        for (text in listOf("\u000C".repeat(1_000_000), "\t".repeat(125_000), "&".repeat(250_000))) {
            val output = ByteArrayOutputStream()
            assertThrows(IOException::class.java) { WordTextCodec.write(listOf(text), output) }
            assertEquals(0, output.size())
        }
    }

    @Test
    fun acceptedDenseControlsAndEscapedTextRoundTrip() {
        val paragraphs = listOf("A\tB\nC\u000C<&>".repeat(5_000))
        val output = ByteArrayOutputStream()
        WordTextCodec.write(paragraphs, output)
        assertEquals(paragraphs, readArchive(output.toByteArray()).paragraphs)
    }

    @Test
    fun maximumContentCanRoundTripAfterAddingPaginationSeparators() {
        val first = "A".repeat(500_000)
        val second = "B".repeat(500_000)
        val source = read(packageEntries("<w:p><w:r><w:t>$first</w:t></w:r></w:p><w:p><w:r><w:t>$second</w:t></w:r></w:p>"))
        val paginated = listOf(source.paragraphs[0] + "\n\t", "\u000C" + source.paragraphs[1] + "\r\n")
        val output = ByteArrayOutputStream()

        WordTextCodec.write(paginated, output)

        assertEquals(listOf("$first\n\t", "\u000C$second\n"), readArchive(output.toByteArray()).paragraphs)
    }

    @Test
    fun structuralSeparatorsDoNotAllowContentOverTheLimit() {
        val first = "A".repeat(500_001)
        val second = "B".repeat(500_000)
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            WordTextCodec.write(listOf("$first\n\t\u000C\r\n$second"), output)
        }
        assertEquals(0, output.size())
        rejects(packageEntries("<w:p><w:r><w:t>$first</w:t><w:tab/><w:br w:type=\"page\"/><w:t>$second</w:t></w:r></w:p>"))
    }

    @Test
    fun callerRetainsOutputStreamOwnership() {
        val output = object : ByteArrayOutputStream() {
            var wasClosed = false
            override fun close() { wasClosed = true }
        }
        WordTextCodec.write(listOf("Hello"), output)
        assertEquals(listOf("Hello"), readArchive(output.toByteArray()).paragraphs)
        assertTrue(!output.wasClosed)
    }

    private fun read(entries: List<Pair<String, ByteArray>>) = readArchive(archive(entries))

    private fun readArchive(bytes: ByteArray): WordTextDocument {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("word-codec-test-", ".docx", context.cacheDir)
        try {
            file.writeBytes(bytes)
            return WordTextCodec.read(file)
        } finally {
            file.delete()
        }
    }

    private fun rejects(entries: List<Pair<String, ByteArray>>) {
        assertThrows(IOException::class.java) { read(entries) }
    }

    private fun firstCentralRecord(bytes: ByteArray): Int = (0 until bytes.size - 4).first { offset ->
        bytes[offset] == 0x50.toByte() && bytes[offset + 1] == 0x4b.toByte() &&
            bytes[offset + 2] == 1.toByte() && bytes[offset + 3] == 2.toByte()
    }

    private fun packageEntries(
        body: String = "<w:p><w:r><w:t>Hello</w:t></w:r></w:p>",
        mainPath: String = "word/document.xml",
        strict: Boolean = false,
    ): List<Pair<String, ByteArray>> = listOf(
        "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/$mainPath" ContentType="$MAIN_TYPE"/></Types>""".toByteArray(),
        "_rels/.rels" to relationship("main", "${if (strict) STRICT_REL else REL}/officeDocument", mainPath).toByteArray(),
        mainPath to documentXml(body, strict).toByteArray(),
    )

    private fun documentXml(body: String, strict: Boolean = false) =
        """<w:document xmlns:w="${if (strict) STRICT_WORD else WORD}"><w:body>$body</w:body></w:document>"""

    private fun relationship(id: String, type: String, target: String, mode: String = "Internal") =
        """<Relationships xmlns="$PACKAGE_REL"><Relationship Id="$id" Type="$type" Target="$target" TargetMode="$mode"/></Relationships>"""

    private fun archive(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }

    private companion object {
        const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val STRICT_WORD = "http://purl.oclc.org/ooxml/wordprocessingml/main"
        const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val STRICT_REL = "http://purl.oclc.org/ooxml/officeDocument/relationships"
        const val PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
        const val MAIN_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    }
}
