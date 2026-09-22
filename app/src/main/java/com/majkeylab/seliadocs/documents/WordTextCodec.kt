package com.majkeylab.seliadocs.documents

import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlSerializer

internal data class WordTextDocument(
    val paragraphs: List<String>,
    val warnings: Set<WordTextWarning>,
)

internal enum class WordTextWarning {
    FORMATTING,
    TABLES,
    IMAGES,
    NOTES_AND_HEADERS,
    HYPERLINKS,
    OTHER_CONTENT,
}

/** Text conversion only. Tabs, line breaks and explicit page breaks use \t, \n and \u000C; CR/CRLF normalize to LF.
 * The private input copy and output stream remain caller-owned. Call on an I/O dispatcher.
 * Unsupported/unsafe input throws IOException.
 */
internal object WordTextCodec {
    const val MAX_INPUT_BYTES = 16 * 1024 * 1024
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val STRICT_WORD = "http://purl.oclc.org/ooxml/wordprocessingml/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val STRICT_REL = "http://purl.oclc.org/ooxml/officeDocument/relationships"
    private const val PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val MAIN_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    private const val MAX_INFLATED_BYTES = 32 * 1024 * 1024
    private const val MAX_PART_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 512
    private const val MAX_XML_EVENTS = 250_000
    private const val MAX_XML_DEPTH = 64
    private const val MAX_CONTENT_CHARS = 1_000_000
    private const val MAX_PARAGRAPHS = 20_000

    fun read(file: File): WordTextDocument {
        val entries = readEntries(file)
        val budget = XmlBudget()
        val types = readContentTypes(entries["[Content_Types].xml"] ?: invalid("Missing DOCX content types"), budget)
        val warnings = linkedSetOf<WordTextWarning>()
        var mainPart: String? = null
        for ((name, bytes) in entries) {
            val type = types.forPart(name)
            if (name != "[Content_Types].xml" && type.isEmpty()) invalid("Missing DOCX part content type")
            rejectActiveContent(name)
            rejectActiveContent(type)
            if (bytes.size >= OLE_SIGNATURE.size && OLE_SIGNATURE.indices.all { bytes[it] == OLE_SIGNATURE[it] }) {
                invalid("Embedded OLE content is not supported")
            }
            if (name.endsWith(".rels", ignoreCase = true)) {
                val main = readRelationships(name, bytes, entries.keys, warnings, budget)
                if (name == "_rels/.rels") mainPart = main
            }
            when {
                type.contains("styles", ignoreCase = true) || type.contains("numbering", ignoreCase = true) ->
                    warnings += WordTextWarning.FORMATTING
                type.startsWith("image/") -> warnings += WordTextWarning.IMAGES
                listOf("header", "footer", "footnotes", "endnotes", "comments").any { type.contains(it, ignoreCase = true) } ->
                    warnings += WordTextWarning.NOTES_AND_HEADERS
            }
        }
        val main = mainPart ?: invalid("Missing Word document relationship")
        if (types.forPart(main) != MAIN_TYPE) invalid("Only ordinary DOCX documents are supported")
        val paragraphs = readDocument(entries[main] ?: invalid("Missing Word document part"), warnings, budget)
        for ((name, bytes) in entries) {
            if (name != main && name != "[Content_Types].xml" && !name.endsWith(".rels", ignoreCase = true) &&
                (name.endsWith(".xml", ignoreCase = true) || types.forPart(name).endsWith("+xml") ||
                    types.forPart(name) in setOf("application/xml", "text/xml"))
            ) {
                parseXml(bytes, budget) { parser, event ->
                    if (event == XmlPullParser.START_TAG) rejectUnsupportedElement(parser)
                }
            }
        }
        return WordTextDocument(paragraphs, warnings)
    }

    fun write(paragraphs: List<String>, output: OutputStream) {
        if (paragraphs.size > MAX_PARAGRAPHS || paragraphs.sumOf { contentLength(it).toLong() } > MAX_CONTENT_CHARS) {
            invalid("Word text exceeds conversion limits")
        }
        paragraphs.forEach(::validateText)
        val parts = linkedMapOf<String, ByteArray>()
        parts["[Content_Types].xml"] = xmlPart(CONTENT_TYPES) { xml ->
            xml.startTag(CONTENT_TYPES, "Types")
            xml.startTag(CONTENT_TYPES, "Default")
                .attribute(null, "Extension", "rels")
                .attribute(null, "ContentType", "application/vnd.openxmlformats-package.relationships+xml")
                .endTag(CONTENT_TYPES, "Default")
            xml.startTag(CONTENT_TYPES, "Override")
                .attribute(null, "PartName", "/word/document.xml")
                .attribute(null, "ContentType", MAIN_TYPE)
                .endTag(CONTENT_TYPES, "Override")
            xml.endTag(CONTENT_TYPES, "Types")
        }
        parts["_rels/.rels"] = xmlPart(PACKAGE_REL) { xml ->
            xml.startTag(PACKAGE_REL, "Relationships")
            xml.startTag(PACKAGE_REL, "Relationship")
                .attribute(null, "Id", "rId1")
                .attribute(null, "Type", "$REL/officeDocument")
                .attribute(null, "Target", "word/document.xml")
                .endTag(PACKAGE_REL, "Relationship")
            xml.endTag(PACKAGE_REL, "Relationships")
        }
        parts["word/document.xml"] = xmlPart(WORD) { xml ->
            xml.startTag(WORD, "document").startTag(WORD, "body")
            for (paragraph in paragraphs.ifEmpty { listOf("") }) {
                xml.startTag(WORD, "p").startTag(WORD, "r")
                var start = 0
                for (index in paragraph.indices) {
                    val character = paragraph[index]
                    if (character != '\t' && character != '\n' && character != '\u000C' && character != '\r') continue
                    writeText(xml, paragraph.substring(start, index))
                    if (character == '\t') {
                        xml.startTag(WORD, "tab").endTag(WORD, "tab")
                    } else if (character != '\n' || index == 0 || paragraph[index - 1] != '\r') {
                        xml.startTag(WORD, "br")
                        if (character == '\u000C') xml.attribute(WORD, "type", "page")
                        xml.endTag(WORD, "br")
                    }
                    start = index + 1
                }
                writeText(xml, paragraph.substring(start))
                xml.endTag(WORD, "r").endTag(WORD, "p")
            }
            xml.endTag(WORD, "body").endTag(WORD, "document")
        }
        val budget = XmlBudget()
        parts.values.forEach { bytes -> parseXml(bytes, budget) { _, _ -> } }
        val destination = object : FilterOutputStream(output) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() = flush()
        }
        ZipOutputStream(destination).use { zip ->
            for ((name, bytes) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun readEntries(file: File): Map<String, ByteArray> {
        // ponytail: bounded in-memory packages; use staged streaming if larger documents become necessary.
        if (!file.isFile || file.length() !in 1..MAX_INPUT_BYTES.toLong()) invalid("DOCX exceeds compressed byte limit")
        val entries = linkedMapOf<String, ByteArray>()
        val names = hashSetOf<String>()
        var totalBytes = 0
        ZipFile(file).use { zip ->
            val parts = zip.entries()
            while (parts.hasMoreElements()) {
                val entry = parts.nextElement()
                if (names.size >= MAX_ENTRIES) invalid("Too many DOCX parts")
                val name = entry.name.removeSuffix("/")
                validatePartName(name)
                if (!names.add(name.lowercase(Locale.ROOT))) invalid("Duplicate or ambiguous DOCX part")
                rejectActiveContent(name)
                val bytes = zip.getInputStream(entry).use { part ->
                    readBounded(part, minOf(MAX_PART_BYTES, MAX_INFLATED_BYTES - totalBytes))
                }
                if (entry.size != bytes.size.toLong() || entry.crc != CRC32().apply { update(bytes) }.value) {
                    invalid("Corrupt DOCX ZIP entry")
                }
                totalBytes += bytes.size
                if (!entry.isDirectory) entries[name] = bytes
            }
        }
        return entries
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (count == 0) invalid("Unable to read DOCX input")
            if (count > limit - output.size()) invalid("DOCX exceeds byte limits")
            output.write(buffer, 0, count)
        }
    }

    private data class ContentTypes(val defaults: Map<String, String>, val overrides: Map<String, String>) {
        fun forPart(name: String): String = overrides[name] ?: defaults[name.substringAfterLast('.', "").lowercase(Locale.ROOT)].orEmpty()
    }

    private fun readContentTypes(bytes: ByteArray, budget: XmlBudget): ContentTypes {
        val defaults = mutableMapOf<String, String>()
        val overrides = mutableMapOf<String, String>()
        val partNames = hashSetOf<String>()
        parseXml(bytes, budget) { parser, event ->
            if (event != XmlPullParser.START_TAG) return@parseXml
            if (parser.namespace != CONTENT_TYPES) invalid("Invalid DOCX content types")
            if (parser.depth == 1) {
                if (parser.name != "Types") invalid("Invalid DOCX content types")
            } else {
                if (parser.depth != 2) invalid("Invalid DOCX content types")
                val type = parser.requiredAttribute("ContentType")
                rejectActiveContent(type)
                when (parser.name) {
                    "Default" -> {
                        val extension = parser.requiredAttribute("Extension").lowercase(Locale.ROOT)
                        if (defaults.put(extension, type) != null) invalid("Duplicate content type")
                    }
                    "Override" -> {
                        val part = parser.requiredAttribute("PartName")
                        if (!part.startsWith('/')) invalid("Invalid content type part")
                        val name = part.substring(1)
                        validatePartName(name)
                        if (!partNames.add(name.lowercase(Locale.ROOT))) invalid("Duplicate content type")
                        overrides[name] = type
                    }
                    else -> invalid("Invalid content type declaration")
                }
            }
        }
        return ContentTypes(defaults, overrides)
    }

    private fun readRelationships(
        name: String,
        bytes: ByteArray,
        entries: Set<String>,
        warnings: MutableSet<WordTextWarning>,
        budget: XmlBudget,
    ): String? {
        val ids = hashSetOf<String>()
        var main: String? = null
        val directory = if (name == "_rels/.rels") "" else name.substringBeforeLast("_rels/", "")
        if (name != "_rels/.rels") {
            if (!name.startsWith("_rels/") && !name.contains("/_rels/")) invalid("Invalid relationship part name")
            val source = directory + name.substringAfterLast('/').removeSuffix(".rels")
            if (source !in entries) invalid("Missing relationship source")
        }
        parseXml(bytes, budget) { parser, event ->
            if (event != XmlPullParser.START_TAG) return@parseXml
            if (parser.namespace != PACKAGE_REL) invalid("Invalid DOCX relationships")
            if (parser.depth == 1) {
                if (parser.name != "Relationships") invalid("Invalid DOCX relationships")
                return@parseXml
            }
            if (parser.depth != 2 || parser.name != "Relationship") invalid("Invalid DOCX relationship")
            if (!ids.add(parser.requiredAttribute("Id"))) invalid("Duplicate DOCX relationship")
            val type = parser.requiredAttribute("Type")
            rejectActiveContent(type)
            val target = parser.requiredAttribute("Target")
            val mode = parser.getAttributeValue(null, "TargetMode") ?: "Internal"
            if (type == "$REL/hyperlink" || type == "$STRICT_REL/hyperlink") {
                warnings += WordTextWarning.HYPERLINKS
                return@parseXml
            }
            if (mode != "Internal") invalid("External DOCX content is not supported")
            val path = resolvePart(directory, target)
            if (path !in entries) invalid("Missing related DOCX part")
            if (type == "$REL/officeDocument" || type == "$STRICT_REL/officeDocument") {
                if (name != "_rels/.rels" || main != null) invalid("Ambiguous Word document relationship")
                main = path
            }
        }
        return main
    }

    private fun resolvePart(directory: String, target: String): String {
        if (target.length > 1024 || target.any { it == '\\' || it == '%' || it == ':' || it == '?' || it == '#' || it.code < 32 }) {
            invalid("Invalid DOCX relationship target")
        }
        val segments = mutableListOf<String>()
        val combined = if (target.startsWith('/')) target.substring(1) else directory + target
        for (segment in combined.split('/')) {
            when (segment) {
                ".", "" -> invalid("Ambiguous DOCX relationship target")
                ".." -> if (segments.isEmpty()) invalid("DOCX relationship escapes package") else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return segments.joinToString("/").also(::validatePartName)
    }

    private fun readDocument(bytes: ByteArray, warnings: MutableSet<WordTextWarning>, budget: XmlBudget): List<String> {
        val paragraphs = mutableListOf<String>()
        var paragraph: StringBuilder? = null
        var paragraphDepth = 0
        var runDepth = 0
        var textDepth = 0
        var documentNamespace = ""
        var bodySeen = false
        var inBody = false
        var characters = 0
        fun append(text: String) {
            val contentCharacters = contentLength(text)
            if (contentCharacters > MAX_CONTENT_CHARS - characters) invalid("Word text exceeds conversion limits")
            val current = paragraph ?: invalid("Word text outside a paragraph")
            current.append(text)
            characters += contentCharacters
        }
        parseXml(bytes, budget) { parser, event ->
            val isWord = parser.namespace == WORD || parser.namespace == STRICT_WORD
            if (event == XmlPullParser.START_TAG) {
                rejectUnsupportedElement(parser)
                if (textDepth > 0) invalid("Nested Word text markup")
                if (parser.depth == 1) {
                    if (!isWord || parser.name != "document") invalid("Invalid Word document")
                    documentNamespace = parser.namespace
                }
                if (isWord && parser.namespace != documentNamespace) invalid("Mixed Word namespaces")
                if (isWord && parser.name == "body") {
                    if (parser.depth != 2 || bodySeen) invalid("Invalid Word body")
                    bodySeen = true
                    inBody = true
                } else if (inBody && isWord) {
                    when (parser.name) {
                        "p" -> {
                            if (paragraph != null) invalid("Nested Word text is not supported")
                            if (paragraphs.size >= MAX_PARAGRAPHS) invalid("Too many Word paragraphs")
                            paragraph = StringBuilder()
                            paragraphDepth = parser.depth
                        }
                        "t" -> {
                            if (paragraph == null || runDepth == 0 || parser.depth != runDepth + 1) invalid("Invalid Word text")
                            textDepth = parser.depth
                        }
                        "r" -> {
                            if (runDepth != 0 || paragraph == null) invalid("Invalid Word run")
                            runDepth = parser.depth
                        }
                        "tab" -> if (runDepth > 0 && parser.depth == runDepth + 1) append("\t")
                        "br", "cr", "noBreakHyphen", "softHyphen" -> {
                            if (runDepth == 0 || parser.depth != runDepth + 1) invalid("Invalid Word run content")
                            append(when (parser.name) {
                                "noBreakHyphen" -> "\u2011"
                                "softHyphen" -> "\u00AD"
                                "br" -> if (parser.getAttributeValue(parser.namespace, "type") == "page") "\u000C" else "\n"
                                else -> "\n"
                            })
                        }
                        "pageBreakBefore" -> if (parser.getAttributeValue(parser.namespace, "val") !in setOf("0", "false", "off")) append("\u000C")
                        "tbl" -> warnings += WordTextWarning.TABLES
                        "drawing", "pict" -> warnings += WordTextWarning.IMAGES
                        "hyperlink" -> warnings += WordTextWarning.HYPERLINKS
                        "footnoteReference", "endnoteReference", "commentReference", "headerReference", "footerReference" ->
                            warnings += WordTextWarning.NOTES_AND_HEADERS
                        "pPr", "rPr", "sectPr", "sdt" -> warnings += WordTextWarning.FORMATTING
                    }
                } else if (inBody && !isWord) {
                    warnings += WordTextWarning.OTHER_CONTENT
                }
            } else if (event == XmlPullParser.END_TAG && isWord) {
                when {
                    parser.name == "t" && parser.depth == textDepth -> textDepth = 0
                    parser.name == "r" && parser.depth == runDepth -> runDepth = 0
                    parser.name == "p" && parser.depth == paragraphDepth -> {
                        paragraphs += paragraph?.toString() ?: invalid("Invalid Word paragraph")
                        paragraph = null
                        paragraphDepth = 0
                    }
                    parser.name == "body" -> inBody = false
                }
            } else if (textDepth > 0 && event in TEXT_EVENTS) {
                append(parser.text ?: invalid("Unresolved XML entity"))
            }
        }
        if (!bodySeen) invalid("Missing Word body")
        paragraphs.forEach(::validateText)
        return paragraphs.ifEmpty { listOf("") }
    }

    private class XmlBudget(var events: Int = 0)

    private fun parseXml(bytes: ByteArray, budget: XmlBudget, visit: (XmlPullParser, Int) -> Unit) {
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16
            bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> Charsets.UTF_16BE
            bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        val source = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        // Reject declarations before the parser can inspect an internal/external entity subset.
        if (source.contains("<!DOCTYPE", ignoreCase = true) || source.contains("<!ENTITY", ignoreCase = true)) {
            invalid("DTD and XML entities are not supported")
        }
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            if (!parser.getFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES) || parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
                invalid("Safe XML parsing is unavailable")
            }
            parser.setInput(StringReader(source))
            var rootSeen = false
            while (true) {
                val event = parser.nextToken()
                if (++budget.events > MAX_XML_EVENTS || parser.depth > MAX_XML_DEPTH) invalid("DOCX XML exceeds conversion limits")
                if (event == XmlPullParser.DOCDECL) invalid("DTD is not supported")
                if (event == XmlPullParser.ENTITY_REF && parser.text == null) invalid("Unresolved XML entity")
                if (event == XmlPullParser.START_TAG && parser.depth == 1) {
                    if (rootSeen) invalid("Multiple XML roots")
                    rootSeen = true
                }
                if (event == XmlPullParser.END_DOCUMENT) {
                    if (!rootSeen) invalid("Missing XML root")
                    break
                }
                visit(parser, event)
            }
        } catch (failure: XmlPullParserException) {
            throw IOException("Invalid or unsupported DOCX XML", failure)
        }
    }

    private fun rejectUnsupportedElement(parser: XmlPullParser) {
        val name = parser.name
        if (name == "AlternateContent" && parser.namespace == "http://schemas.openxmlformats.org/markup-compatibility/2006") {
            invalid("Alternate Word content is not supported")
        }
        if (parser.namespace != WORD && parser.namespace != STRICT_WORD) return
        if (name in UNSUPPORTED_ELEMENTS || name.endsWith("Change") || name.startsWith("moveFrom") || name.startsWith("moveTo") ||
            name.startsWith("customXmlIns") || name.startsWith("customXmlDel") || name.startsWith("customXmlMove")
        ) invalid("Word feature is not supported: $name")
    }

    private fun rejectActiveContent(value: String) {
        val lower = value.lowercase(Locale.ROOT)
        if (listOf("macroenabled", "vba", "activex", "oleobject", "embeddings/", "encryptedpackage", "encryptioninfo").any(lower::contains) ||
            lower.endsWith("/afchunk") || lower.endsWith("/package")
        ) invalid("Active, embedded or encrypted Word content is not supported")
    }

    private fun validatePartName(name: String) {
        if (name.isEmpty() || name.length > 1024 || name.any { it == '\\' || it == ':' || it == '%' || it == '?' || it == '#' || it.code < 32 } ||
            name.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) invalid("Unsafe DOCX part name")
    }

    // Pagination adds separators; XML byte/event limits bound their serialized representation.
    private fun contentLength(text: String): Int = text.count { it != '\t' && it != '\r' && it != '\n' && it != '\u000C' }

    private fun validateText(text: String) {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint != 9 && codePoint != 10 && codePoint != 12 && codePoint != 13 &&
                codePoint !in 0x20..0xD7FF && codePoint !in 0xE000..0xFFFD && codePoint !in 0x10000..0x10FFFF
            ) invalid("Text contains characters unsupported by Word XML")
            index += Character.charCount(codePoint)
        }
    }

    private fun xmlPart(namespace: String, write: (XmlSerializer) -> Unit): ByteArray {
        val output = object : ByteArrayOutputStream() {
            override fun write(value: Int) {
                if (size() >= MAX_PART_BYTES) invalid("DOCX exceeds part byte limit")
                super.write(value)
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length > MAX_PART_BYTES - size()) invalid("DOCX exceeds part byte limit")
                super.write(bytes, offset, length)
            }
        }
        val xml = Xml.newSerializer()
        xml.setOutput(output, "UTF-8")
        xml.startDocument("UTF-8", true)
        xml.setPrefix(if (namespace == WORD) "w" else "", namespace)
        write(xml)
        xml.endDocument()
        xml.flush()
        return output.toByteArray()
    }

    private fun writeText(xml: XmlSerializer, text: String) {
        if (text.isEmpty()) return
        xml.startTag(WORD, "t")
            .attribute("http://www.w3.org/XML/1998/namespace", "space", "preserve")
            .text(text)
            .endTag(WORD, "t")
    }

    private fun XmlPullParser.requiredAttribute(name: String): String =
        getAttributeValue(null, name)?.takeIf(String::isNotEmpty) ?: invalid("Missing DOCX attribute: $name")

    private fun invalid(message: String): Nothing = throw IOException(message)

    private val TEXT_EVENTS = setOf(XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF)
    private val OLE_SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private val UNSUPPORTED_ELEMENTS = setOf(
        "ins", "del", "delText", "fldChar", "fldSimple", "instrText", "delInstrText", "altChunk", "object",
        "control", "subDoc", "dataBinding", "txbxContent", "sym", "vanish", "webHidden", "trackRevisions",
        "cellIns", "cellDel", "cellMerge", "numberingChange",
    )
}
