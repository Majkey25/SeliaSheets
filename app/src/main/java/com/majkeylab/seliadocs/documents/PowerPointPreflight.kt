package com.majkeylab.seliadocs.documents

import android.graphics.BitmapFactory
import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

internal data class PowerPointMetadata(val widthCssPx: Double, val heightCssPx: Double, val slideCount: Int)

/** Inspect the caller-owned private copy on an I/O dispatcher before handing it to the renderer. */
internal object PowerPointPreflight {
    const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 128 * 1024 * 1024
    const val MAX_PART_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRIES = 2048
    const val MAX_SLIDES = 100
    const val MAX_IMAGE_PIXELS = 32_000_000L
    private const val MAX_XML_EVENTS = 500_000
    private const val MAX_XML_DEPTH = 64
    private const val PML = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val MAIN_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
    private const val SLIDE_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    private const val CHART_TYPE = "application/vnd.openxmlformats-officedocument.drawingml.chart+xml"
    private const val WORKBOOK_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    fun inspect(file: File): PowerPointMetadata {
        if (!file.isFile || file.length() !in 1..MAX_INPUT_BYTES.toLong()) invalid("PPTX must be at most 32 MiB")
        ZipFile(file).use { zip ->
            val entries = linkedMapOf<String, ZipEntry>()
            val names = hashSetOf<String>()
            var declaredBytes = 0L
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entries.size >= MAX_ENTRIES) invalid("PPTX has too many parts")
                validateName(entry.name.removeSuffix("/"))
                validateExtra(entry.extra)
                if (!names.add(entry.name.removeSuffix("/").lowercase(Locale.ROOT))) invalid("Duplicate PPTX part")
                rejectActive(entry.name)
                if (entry.method !in setOf(ZipEntry.STORED, ZipEntry.DEFLATED) || entry.size !in 0..MAX_PART_BYTES.toLong() ||
                    entry.compressedSize !in 0..file.length() || entry.crc < 0
                ) invalid("Invalid or oversized PPTX ZIP entry")
                declaredBytes += entry.size
                if (declaredBytes > MAX_TOTAL_BYTES) invalid("PPTX expands beyond 128 MiB")
                entries[entry.name] = entry
            }
            val budget = XmlBudget()
            val contentEntry = entries["[Content_Types].xml"] ?: invalid("Missing PPTX content types")
            val types = zip.getInputStream(contentEntry).use { readTypes(readBounded(it, MAX_PART_BYTES), budget) }
            val state = Inspection(entries.keys, types, budget)
            val localNames = hashSetOf<String>()
            var actualBytes = 0
            // Verify local records too: the browser must not see a different archive than native preflight.
            ZipInputStream(file.inputStream().buffered()).use { input ->
                while (true) {
                    val local = input.nextEntry ?: break
                    validateExtra(local.extra)
                    val entry = entries[local.name] ?: invalid("PPTX ZIP directories disagree")
                    if (!localNames.add(local.name) || local.method != entry.method) invalid("Ambiguous PPTX ZIP entry")
                    val bytes = readBounded(input, minOf(MAX_PART_BYTES, MAX_TOTAL_BYTES - actualBytes))
                    actualBytes += bytes.size
                    if (entry.size != bytes.size.toLong() || entry.crc != CRC32().apply { update(bytes) }.value) {
                        invalid("Corrupt PPTX ZIP entry")
                    }
                    zip.getInputStream(entry).use { verifyContents(it, bytes) }
                    if (entry.isDirectory) {
                        if (bytes.isNotEmpty()) invalid("PPTX directory contains data")
                    } else {
                        state.inspectPart(entry.name, bytes)
                    }
                    input.closeEntry()
                }
            }
            if (localNames != entries.keys) invalid("PPTX ZIP directories disagree")
            return state.finish()
        }
    }

    private class Inspection(val entries: Set<String>, val types: ContentTypes, val budget: XmlBudget) {
        var width = 0.0
        var height = 0.0
        var rootMain: String? = null
        var imagePixels = 0L
        var fontBytes = 0
        val slideIds = linkedSetOf<String>()
        val slideParts = hashSetOf<String>()
        val slideRelationships = mutableMapOf<String, String>()
        val workbookParts = hashSetOf<String>()
        val workbookRelationships = hashSetOf<String>()

        fun inspectPart(name: String, bytes: ByteArray) {
            if (name == "[Content_Types].xml") return
            val type = types.forPart(name).ifEmpty { invalid("Missing PPTX part content type") }
            rejectActive(type)
            if (bytes.startsWith(OLE_SIGNATURE) || bytes.startsWith(byteArrayOf(0x4d, 0x5a))) invalid("Embedded executable/OLE content is unsupported")
            when {
                name.endsWith(".rels", true) -> relationships(name, bytes)
                type.startsWith("image/") || name.startsWith("ppt/media/") -> {
                    imagePixels += inspectImage(bytes, type)
                    if (imagePixels > MAX_IMAGE_PIXELS) invalid("PPTX images exceed 32 million pixels in total")
                }
                type == WORKBOOK_TYPE -> {
                    if (!name.startsWith("ppt/embeddings/") || !name.endsWith(".xlsx") || !bytes.startsWith(ZIP_SIGNATURE)) {
                        invalid("Unsupported embedded Office document")
                    }
                    // Chart renderers consume XML caches; this workbook stays opaque and is never opened.
                    workbookParts += name
                }
                type == "application/x-fontdata" -> {
                    if (!name.startsWith("ppt/fonts/") || !name.endsWith(".fntdata") || bytes.size !in 82..(8 * 1024 * 1024)) {
                        invalid("Unsupported embedded font")
                    }
                    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    if ((header.getShort(34).toInt() and 0xffff) != 0x504c || header.getInt(0) != bytes.size ||
                        (header.getInt(12) and 0x10000004) != 0
                    ) invalid("Compressed or encrypted embedded fonts are unsupported")
                    fontBytes += bytes.size
                    if (fontBytes > 16 * 1024 * 1024) invalid("PPTX embedded fonts exceed 16 MiB")
                }
                name.endsWith(".xml", true) || type.endsWith("+xml") || type in setOf("application/xml", "text/xml") -> {
                    parseXml(bytes, budget) { parser ->
                        if (parser.name in ACTIVE_ELEMENTS) invalid("PPTX contains unsupported active or audio/video content")
                        if (type == MAIN_TYPE) presentation(name, parser)
                        if (type == SLIDE_TYPE && parser.depth == 1) {
                            if (parser.namespace != PML || parser.name != "sld") invalid("Unsupported PowerPoint slide format")
                            slideParts += name
                        }
                    }
                }
                else -> invalid("Unsupported PPTX part type: $type")
            }
        }

        private fun presentation(name: String, parser: XmlPullParser) {
            if (name != "ppt/presentation.xml") invalid("Unsupported PowerPoint package layout")
            if (parser.depth == 1 && (parser.namespace != PML || parser.name != "presentation")) invalid("Unsupported PowerPoint format")
            if (parser.namespace != PML) return
            if (parser.name == "sldSz") {
                if (parser.depth != 2 || width != 0.0) invalid("Ambiguous slide dimensions")
                width = (parser.required("cx").toLongOrNull() ?: invalid("Invalid slide width")) / 9525.0
                height = (parser.required("cy").toLongOrNull() ?: invalid("Invalid slide height")) / 9525.0
                if (width !in 96.0..4096.0 || height !in 96.0..4096.0 || width / height !in 0.2..5.0) invalid("Unsupported slide dimensions")
            }
            if (parser.name == "sldId") {
                val id = parser.getAttributeValue(REL, "id") ?: invalid("Missing slide relationship")
                if (parser.depth != 3 || !slideIds.add(id) || slideIds.size > MAX_SLIDES) invalid("Invalid slide list or more than 100 slides")
            }
        }

        private fun relationships(name: String, bytes: ByteArray) {
            val ids = hashSetOf<String>()
            val directory = if (name == "_rels/.rels") "" else name.substringBeforeLast("_rels/", "")
            val source = if (name == "_rels/.rels") "" else directory + name.substringAfterLast('/').removeSuffix(".rels")
            if (source.isNotEmpty() && (source !in entries || !(name.startsWith("_rels/") || name.contains("/_rels/")))) {
                invalid("Missing PPTX relationship source")
            }
            parseXml(bytes, budget) { parser ->
                if (parser.namespace != PACKAGE_REL) invalid("Invalid PPTX relationships")
                if (parser.depth == 1) {
                    if (parser.name != "Relationships") invalid("Invalid PPTX relationships")
                    return@parseXml
                }
                if (parser.depth != 2 || parser.name != "Relationship") invalid("Invalid PPTX relationship")
                val id = parser.required("Id")
                if (!ids.add(id)) invalid("Duplicate PPTX relationship")
                val type = parser.required("Type")
                rejectActive(type)
                val target = parser.required("Target")
                val mode = parser.getAttributeValue(null, "TargetMode") ?: "Internal"
                if (mode == "External") {
                    val scheme = try { URI(target).scheme?.lowercase(Locale.ROOT) } catch (_: Exception) { null }
                    if (type != "$REL/hyperlink" || scheme !in setOf("http", "https", "mailto")) invalid("External PPTX content is unsupported")
                    return@parseXml
                }
                if (mode != "Internal") invalid("Invalid PPTX relationship mode")
                val path = resolvePart(directory, target)
                if (path !in entries || path.endsWith('/')) invalid("Missing related PPTX part")
                when (type) {
                    "$REL/officeDocument" -> {
                        if (name != "_rels/.rels" || rootMain != null) invalid("Ambiguous PowerPoint document")
                        rootMain = path
                    }
                    "$REL/slide" -> if (source == "ppt/presentation.xml") slideRelationships[id] = path
                    "$REL/package" -> {
                        if (types.forPart(source) != CHART_TYPE || types.forPart(path) != WORKBOOK_TYPE) invalid("Embedded Office objects are unsupported")
                        workbookRelationships += path
                    }
                }
                if (types.forPart(path) == WORKBOOK_TYPE && type != "$REL/package") invalid("Unsupported embedded workbook relationship")
            }
        }

        fun finish(): PowerPointMetadata {
            if (rootMain != "ppt/presentation.xml" || types.forPart(rootMain.orEmpty()) != MAIN_TYPE) invalid("Only ordinary PPTX presentations are supported")
            if (width == 0.0 || slideIds.isEmpty()) invalid("PPTX has no valid slides or dimensions")
            val orderedParts = slideIds.map { slideRelationships[it] ?: invalid("Missing presentation slide") }
            if (orderedParts.toSet().size != orderedParts.size || orderedParts.toSet() != slideParts) invalid("Ambiguous presentation slide list")
            if (workbookParts != workbookRelationships) invalid("Unreferenced embedded Office document")
            return PowerPointMetadata(width, height, slideIds.size)
        }
    }

    private data class ContentTypes(val defaults: Map<String, String>, val overrides: Map<String, String>) {
        fun forPart(name: String): String = overrides[name] ?: defaults[name.substringAfterLast('.', "").lowercase(Locale.ROOT)].orEmpty()
    }

    private fun readTypes(bytes: ByteArray, budget: XmlBudget): ContentTypes {
        val defaults = mutableMapOf<String, String>()
        val overrides = mutableMapOf<String, String>()
        val names = hashSetOf<String>()
        parseXml(bytes, budget) { parser ->
            if (parser.namespace != CONTENT_TYPES) invalid("Invalid PPTX content types")
            if (parser.depth == 1) {
                if (parser.name != "Types") invalid("Invalid PPTX content types")
            } else {
                if (parser.depth != 2) invalid("Invalid PPTX content types")
                val type = parser.required("ContentType")
                // Declarations for unused extensions do not add content. Actual parts are checked above.
                when (parser.name) {
                    "Default" -> if (defaults.put(parser.required("Extension").lowercase(Locale.ROOT), type) != null) invalid("Duplicate PPTX content type")
                    "Override" -> {
                        val path = parser.required("PartName")
                        if (!path.startsWith('/')) invalid("Invalid PPTX content type path")
                        val name = path.substring(1)
                        validateName(name)
                        if (!names.add(name.lowercase(Locale.ROOT))) invalid("Duplicate PPTX content type")
                        overrides[name] = type
                    }
                    else -> invalid("Invalid PPTX content type declaration")
                }
            }
        }
        return ContentTypes(defaults, overrides)
    }

    private class XmlBudget(var events: Int = 0)

    private fun parseXml(bytes: ByteArray, budget: XmlBudget, visit: (XmlPullParser) -> Unit) {
        // Match browser TextDecoder: UTF-16 XML is rejected instead of parsing differently in each runtime.
        val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        if ('\u0000' in source || source.contains("<!DOCTYPE", true) || source.contains("<!ENTITY", true)) invalid("PPTX DTD/entities or non-UTF-8 XML are unsupported")
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            if (!parser.getFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES) || parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
                invalid("Safe PPTX XML parsing is unavailable")
            }
            parser.setInput(StringReader(source))
            var rootSeen = false
            while (true) {
                val event = parser.nextToken()
                if (++budget.events > MAX_XML_EVENTS || parser.depth > MAX_XML_DEPTH) invalid("PPTX XML is too complex")
                if (event == XmlPullParser.DOCDECL || event == XmlPullParser.ENTITY_REF && parser.text == null) invalid("Unsupported PPTX XML entity")
                if (event == XmlPullParser.START_TAG) {
                    if (parser.depth == 1) {
                        if (rootSeen) invalid("Multiple PPTX XML roots")
                        rootSeen = true
                    }
                    if (parser.attributeCount > 128 || (0 until parser.attributeCount).any { parser.getAttributeValue(it).length > 32_768 }) invalid("PPTX XML attributes are too large")
                    visit(parser)
                }
                if (event == XmlPullParser.END_DOCUMENT) {
                    if (!rootSeen) invalid("Missing PPTX XML root")
                    return
                }
            }
        } catch (failure: XmlPullParserException) {
            throw IOException("Invalid PPTX XML. Export the presentation to PDF and import that file.", failure)
        }
    }

    private fun inspectImage(bytes: ByteArray, type: String): Long {
        if (type !in RASTER_TYPES) invalid("Unsupported PPTX image ($type)")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outMimeType !in RASTER_TYPES || options.outWidth !in 1..16_384 || options.outHeight !in 1..16_384) invalid("Invalid or oversized PPTX image")
        rejectAnimation(bytes, options.outMimeType)
        val pixels = options.outWidth.toLong() * options.outHeight
        if (pixels > MAX_IMAGE_PIXELS) invalid("PPTX image exceeds 32 million pixels")
        return pixels
    }

    private fun rejectAnimation(bytes: ByteArray, type: String?) {
        // ponytail: reject all GIFs; add a bounded frame parser only if static GIF support is needed.
        if (type == "image/gif") invalid("GIF images are unsupported for static slide import")
        if (type != "image/png" && type != "image/webp") return
        val png = type == "image/png"
        val view = ByteBuffer.wrap(bytes).order(if (png) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        var offset = if (png) 8 else 12
        while (offset <= bytes.size - 8) {
            val nameOffset = offset + if (png) 4 else 0
            val kind = String(bytes, nameOffset, 4, Charsets.US_ASCII)
            val length = view.getInt(offset + if (png) 0 else 4).toLong() and 0xffffffffL
            if (kind == "acTL" || kind == "ANIM" || kind == "ANMF") invalid("Animated images are unsupported for static slide import")
            val next = offset.toLong() + 8 + length + if (png) 4 else length % 2
            if (next > bytes.size) invalid("Truncated PPTX image")
            offset = next.toInt()
        }
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (count == 0) invalid("Cannot read PPTX part")
            if (count > limit - output.size()) invalid("PPTX exceeds decompressed byte limits")
            output.write(buffer, 0, count)
        }
    }

    private fun verifyContents(input: InputStream, expected: ByteArray) {
        val buffer = ByteArray(8192)
        var offset = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0 || count > expected.size - offset) invalid("PPTX ZIP payloads disagree")
            for (index in 0 until count) {
                if (buffer[index] != expected[offset + index]) invalid("PPTX ZIP payloads disagree")
            }
            offset += count
        }
        if (offset != expected.size) invalid("PPTX ZIP payloads disagree")
    }

    private fun resolvePart(directory: String, target: String): String {
        if (target.length > 1024 || target.any { it == '\\' || it == '%' || it == ':' || it == '?' || it == '#' || it.code < 32 }) invalid("Unsafe PPTX relationship target")
        val parts = mutableListOf<String>()
        val combined = if (target.startsWith('/')) target.substring(1) else directory + target
        for (part in combined.split('/')) {
            when (part) {
                "", "." -> invalid("Ambiguous PPTX relationship target")
                ".." -> if (parts.isEmpty()) invalid("PPTX relationship escapes package") else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/").also(::validateName)
    }

    private fun validateName(name: String) {
        if (name.isEmpty() || name.length > 1024 || name.any { it == '\\' || it == ':' || it == '%' || it == '?' || it == '#' || it.code < 32 } ||
            name.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) invalid("Unsafe PPTX part name")
    }

    private fun validateExtra(extra: ByteArray?) {
        if (extra == null) return
        val view = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        while (offset < extra.size) {
            if (extra.size - offset < 4) invalid("Invalid PPTX ZIP extra field")
            val kind = view.getShort(offset).toInt() and 0xffff
            val length = view.getShort(offset + 2).toInt() and 0xffff
            if (length > extra.size - offset - 4) invalid("Invalid PPTX ZIP extra field")
            // JSZip honors this alias; Java's ZipEntry name need not match it.
            if (kind == 0x7075) invalid("Ambiguous PPTX ZIP filename encoding")
            offset += 4 + length
        }
    }

    private fun rejectActive(value: String) {
        val lower = value.lowercase(Locale.ROOT)
        if (listOf("macroenabled", "vba", "activex", "oleobject", "encryptedpackage", "encryptioninfo").any(lower::contains) ||
            lower.endsWith("/afchunk") || lower.endsWith("/audio") || lower.endsWith("/video") || lower.endsWith("/media") ||
            lower.startsWith("audio/") || lower.startsWith("video/")
        ) invalid("Active, encrypted, or audio/video PowerPoint content is unsupported")
    }

    private fun XmlPullParser.required(name: String): String = getAttributeValue(null, name)?.takeIf(String::isNotEmpty) ?: invalid("Missing PPTX attribute: $name")
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    private fun invalid(message: String): Nothing = throw IOException("$message. Export the presentation to PDF and import that file.")
    private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    private val OLE_SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private val RASTER_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp", "image/x-ms-bmp")
    private val ACTIVE_ELEMENTS = setOf("oleObj", "control", "audio", "video", "audioFile", "videoFile", "wavAudioFile", "quickTimeFile", "audioCd", "snd", "sndTgt")
}
