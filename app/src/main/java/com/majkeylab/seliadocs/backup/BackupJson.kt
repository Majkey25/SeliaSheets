package com.majkeylab.seliadocs.backup

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.majkeylab.seliadocs.data.BlockKind
import com.majkeylab.seliadocs.data.CoverColor
import com.majkeylab.seliadocs.data.CoverPattern
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.PageMode
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.PaperTemplate
import com.majkeylab.seliadocs.editor.BrushKind
import com.majkeylab.seliadocs.editor.ShapeKind
import com.majkeylab.seliadocs.recognition.MAX_OCR_REGION_DATA_LENGTH
import com.majkeylab.seliadocs.recognition.decodeImageOcrRegions
import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader
import java.io.Writer

internal object BackupJson {
    private const val MAX_RECORD_CHARS = 1024 * 1024
    private const val MAX_TEXT_CHARS = 100_000
    private const val MAX_ELEMENT_TEXT_CHARS = 10_000
    private const val MAX_STROKE_BYTES = 512 * 1024
    private const val MAX_STROKE_BASE64_CHARS = (MAX_STROKE_BYTES + 2) / 3 * 4
    private const val MAX_UNKNOWN_STRING_CHARS = 64 * 1024
    private const val MAX_JSON_PRIMITIVE_CHARS = 128
    private const val MAX_JSON_NESTING_DEPTH = 32
    private const val MAX_FLAGS = 128
    private const val MAX_SHORT_TEXT_CHARS = 1024
    private const val MAX_PAGE_DIMENSION = 14_400
    private val TEXT_ALIGNMENTS = setOf("START", "CENTER", "END", "JUSTIFY")
    private val JSON_WHITESPACE = setOf(' ', '\t', '\r', '\n')
    private val JSON_VALUE_DELIMITERS = JSON_WHITESPACE + setOf(',', '}', ']')
    private val JSON_PRIMITIVE_DELIMITERS = JSON_VALUE_DELIMITERS + setOf(':', '{', '[', '"')
    private val JSON_NESTED_SEPARATORS = JSON_WHITESPACE + setOf(',', ':')
    private val ELEMENT_TEXT_FIELDS = setOf("text", "expression", "resultText")
    private val SHORT_STRING_FIELDS =
        setOf(
            "kind",
            "id",
            "pageId",
            "notebookId",
            "chapterId",
            "pdfSourceId",
            "payloadId",
            "assetId",
            "sha256",
            "coverColor",
            "coverPattern",
            "defaultPaper",
            "orientation",
            "blockKind",
            "alignment",
            "shapeKind",
            "elementKind",
            "brushKind",
            "pageMode",
        )

    private data class JsonStringSpan(val start: Int, val end: Int, val decodedLength: Int)

    fun writeManifest(output: Writer, manifest: BackupManifest) {
        validate(manifest)
        JsonWriter(output).apply {
            beginObject()
            name("formatVersion").value(manifest.formatVersion.toLong())
            name("appVersion").value(manifest.appVersion)
            name("exportedAt").value(manifest.exportedAt)
            name("notebookCount").value(manifest.notebookCount.toLong())
            name("pageCount").value(manifest.pageCount.toLong())
            name("assetCount").value(manifest.assetCount.toLong())
            name("featureFlags")
            beginArray()
            manifest.featureFlags.sorted().forEach(::value)
            endArray()
            endObject()
            flush()
        }
    }

    fun readManifest(input: Reader): BackupManifest =
        parseJson(input) { reader ->
            var formatVersion: Int? = null
            var appVersion: String? = null
            var exportedAt: Long? = null
            var notebookCount = 0
            var pageCount = 0
            var assetCount = 0
            var featureFlags: Set<String> = emptySet()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "formatVersion" -> formatVersion = reader.nextIntField("formatVersion")
                    "appVersion" ->
                        appVersion = reader.nextBoundedString("appVersion", MAX_SHORT_TEXT_CHARS)
                    "exportedAt" -> exportedAt = reader.nextLongField("exportedAt")
                    "notebookCount" -> notebookCount = reader.nextIntField("notebookCount")
                    "pageCount" -> pageCount = reader.nextIntField("pageCount")
                    "assetCount" -> assetCount = reader.nextIntField("assetCount")
                    "featureFlags" -> featureFlags = reader.readFeatureFlags()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            BackupManifest(
                formatVersion = required(formatVersion, "formatVersion"),
                appVersion = requiredString(appVersion, "appVersion"),
                exportedAt = required(exportedAt, "exportedAt"),
                notebookCount = notebookCount,
                pageCount = pageCount,
                assetCount = assetCount,
                featureFlags = featureFlags,
            ).also(::validate)
        }

    fun writeRecord(output: Writer, record: BackupRecord) {
        validate(record)
        val writer = JsonWriter(output)
        writer.beginObject()
        when (record) {
            is BackupNotebook -> writer.writeNotebook(record)
            is BackupPage -> writer.writePage(record)
            is BackupChapter -> writer.writeChapter(record)
            is BackupPdfSource -> writer.writePdfSource(record)
            is BackupStroke -> writer.writeStroke(record)
            is BackupElement -> writer.writeElement(record)
            is BackupBlock -> writer.writeBlock(record)
        }
        writer.endObject()
        writer.flush()
        output.append('\n')
    }

    fun readRecords(input: Reader, consume: (BackupRecord) -> Unit) {
        records(input).forEach(consume)
    }

    fun records(input: Reader): Sequence<BackupRecord> = sequence {
        val reader = input as? BufferedReader ?: BufferedReader(input)
        while (true) {
            val line =
                try {
                    reader.readBoundedLine()
                } catch (failure: BackupFailure) {
                    throw failure
                } catch (failure: Exception) {
                    throw BackupFailure.Malformed(failure)
                } ?: return@sequence
            if (line.isBlank()) continue
            yield(readRecord(line))
        }
    }

    private fun readRecord(line: String): BackupRecord {
        val kind = readRecordKind(line)
        return parseJson(StringReader(line)) { reader ->
            when (kind) {
                "notebook" -> reader.readNotebook()
                "page" -> reader.readPage()
                "chapter" -> reader.readChapter()
                "pdfSource" -> reader.readPdfSource()
                "stroke" -> reader.readStroke()
                "element" -> reader.readElement()
                "block" -> reader.readBlock()
                else -> throw BackupFailure.UnknownRecordKind(kind)
            }
        }
    }

    private fun readRecordKind(line: String): String {
        var index = line.skipJsonWhitespace(0)
        if (line.getOrNull(index) != '{') throw BackupFailure.Malformed()
        index++
        var kind: String? = null
        while (true) {
            index = line.skipJsonWhitespace(index)
            if (line.getOrNull(index) == '}') break
            val keySpan = line.jsonStringSpan(index)
            if (keySpan.decodedLength > MAX_SHORT_TEXT_CHARS) {
                throw BackupFailure.LimitExceeded("field")
            }
            val key = line.decodeJsonString(keySpan, "field", MAX_SHORT_TEXT_CHARS)
            index = line.skipJsonWhitespace(keySpan.end)
            if (line.getOrNull(index) != ':') throw BackupFailure.Malformed()
            index = line.skipJsonWhitespace(index + 1)
            if (line.getOrNull(index) == '"') {
                val valueSpan = line.jsonStringSpan(index)
                if (key == "kind") {
                    if (kind != null) throw BackupFailure.Malformed()
                    kind = line.decodeJsonString(valueSpan, "kind", MAX_SHORT_TEXT_CHARS)
                }
                index = valueSpan.end
            } else {
                if (key == "kind") throw BackupFailure.Malformed()
                index = line.jsonValueEnd(index, Int.MAX_VALUE)
            }
            index = line.skipJsonWhitespace(index)
            when (line.getOrNull(index)) {
                ',' -> index++
                '}' -> break
                else -> throw BackupFailure.Malformed()
            }
        }
        return requiredString(kind, "kind").also { validateRecordStringBounds(line, it) }
    }

    private fun validateRecordStringBounds(line: String, kind: String) {
        var index = line.skipJsonWhitespace(0) + 1
        while (true) {
            index = line.skipJsonWhitespace(index)
            if (line.getOrNull(index) == '}') return
            val keySpan = line.jsonStringSpan(index)
            val key = line.decodeJsonString(keySpan, "field", MAX_SHORT_TEXT_CHARS)
            index = line.skipJsonWhitespace(keySpan.end)
            if (line.getOrNull(index) != ':') throw BackupFailure.Malformed()
            index = line.skipJsonWhitespace(index + 1)
            if (line.getOrNull(index) == '"') {
                val valueSpan = line.jsonStringSpan(index)
                if (valueSpan.decodedLength > stringFieldLimit(kind, key)) {
                    throw BackupFailure.LimitExceeded(key)
                }
                index = valueSpan.end
            } else {
                index = line.jsonValueEnd(index, MAX_UNKNOWN_STRING_CHARS)
            }
            index = line.skipJsonWhitespace(index)
            when (line.getOrNull(index)) {
                ',' -> index++
                '}' -> return
                else -> throw BackupFailure.Malformed()
            }
        }
    }

    private fun String.decodeJsonString(
        span: JsonStringSpan,
        field: String,
        limit: Int,
    ): String =
        parseJson(StringReader("[${substring(span.start, span.end)}]")) { reader ->
            reader.beginArray()
            val value = reader.nextBoundedString(field, limit)
            reader.endArray()
            value
        }

    private fun String.jsonStringSpan(start: Int): JsonStringSpan {
        if (getOrNull(start) != '"') throw BackupFailure.Malformed()
        var index = start + 1
        var decodedLength = 0
        while (index < length) {
            when (val character = this[index]) {
                '"' -> return JsonStringSpan(start, index + 1, decodedLength)
                '\\' -> {
                    index++
                    when (getOrNull(index)) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> {
                            if (
                                index + 4 >= length ||
                                    !this[index + 1].isHexDigit() ||
                                    !this[index + 2].isHexDigit() ||
                                    !this[index + 3].isHexDigit() ||
                                    !this[index + 4].isHexDigit()
                            ) {
                                throw BackupFailure.Malformed()
                            }
                            index += 4
                        }
                        else -> throw BackupFailure.Malformed()
                    }
                    decodedLength++
                }
                else -> {
                    if (character.code < 0x20) throw BackupFailure.Malformed()
                    decodedLength++
                }
            }
            index++
        }
        throw BackupFailure.Malformed()
    }

    private fun String.jsonValueEnd(start: Int, stringLimit: Int): Int {
        when (getOrNull(start)) {
            '"' -> {
                val span = jsonStringSpan(start)
                if (span.decodedLength > stringLimit) throw BackupFailure.LimitExceeded("unknown")
                return span.end
            }
            '{', '[' -> {
                var depth = 0
                var index = start
                while (index < length) {
                    when (this[index]) {
                        '"' -> {
                            val span = jsonStringSpan(index)
                            if (span.decodedLength > stringLimit) {
                                throw BackupFailure.LimitExceeded("unknown")
                            }
                            index = span.end
                        }
                        '{', '[' -> {
                            depth++
                            if (depth > MAX_JSON_NESTING_DEPTH) {
                                throw BackupFailure.LimitExceeded("nesting")
                            }
                            index++
                        }
                        '}', ']' -> {
                            depth--
                            index++
                            if (depth == 0) return index
                        }
                        else ->
                            if (this[index] in JSON_NESTED_SEPARATORS) {
                                index++
                            } else {
                                index = jsonPrimitiveEnd(index)
                            }
                    }
                }
                throw BackupFailure.Malformed()
            }
            null -> throw BackupFailure.Malformed()
            else -> return jsonPrimitiveEnd(start)
        }
    }

    private fun String.jsonPrimitiveEnd(start: Int): Int {
        var index = start
        while (index < length && this[index] !in JSON_PRIMITIVE_DELIMITERS) {
            if (index - start >= MAX_JSON_PRIMITIVE_CHARS) {
                throw BackupFailure.LimitExceeded("unknown")
            }
            index++
        }
        if (index == start) throw BackupFailure.Malformed()
        return index
    }

    private fun String.skipJsonWhitespace(start: Int): Int {
        var index = start
        while (index < length && this[index] in JSON_WHITESPACE) index++
        return index
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun stringFieldLimit(kind: String, field: String): Int =
        when {
            kind == "stroke" && field == "inputs" -> MAX_STROKE_BASE64_CHARS
            kind == "element" && field == "ocrRegions" -> MAX_OCR_REGION_DATA_LENGTH
            kind == "notebook" && field == "title" -> MAX_TEXT_CHARS
            kind == "page" && field == "title" -> MAX_TEXT_CHARS
            kind == "chapter" && field == "title" -> MAX_TEXT_CHARS
            kind == "pdfSource" && field == "displayName" -> MAX_TEXT_CHARS
            kind == "element" && field in ELEMENT_TEXT_FIELDS -> MAX_TEXT_CHARS
            kind == "block" && field == "text" -> MAX_TEXT_CHARS
            field in SHORT_STRING_FIELDS -> MAX_SHORT_TEXT_CHARS
            else -> MAX_UNKNOWN_STRING_CHARS
        }

    private fun JsonWriter.writeNotebook(record: BackupNotebook) {
        name("kind").value("notebook")
        name("id").value(record.id)
        name("title").value(record.title)
        name("coverColor").value(record.coverColor)
        name("coverPattern").value(record.coverPattern)
        name("defaultPaper").value(record.defaultPaper)
        name("orientation").value(record.orientation)
        name("fingerDrawing").value(record.fingerDrawing)
        name("favorite").value(record.favorite)
        name("createdAt").value(record.createdAt)
        name("updatedAt").value(record.updatedAt)
        name("trashedAt")
        if (record.trashedAt == null) nullValue() else value(record.trashedAt)
    }

    private fun JsonWriter.writePage(record: BackupPage) {
        name("kind").value("page")
        name("id").value(record.id)
        name("notebookId").value(record.notebookId)
        name("pageIndex").value(record.pageIndex.toLong())
        name("paper").value(record.paper)
        name("widthPoints").value(record.widthPoints.toLong())
        name("heightPoints").value(record.heightPoints.toLong())
        writeNullableString("chapterId", record.chapterId)
        writeNullableString("title", record.title)
        name("pageMode").value(record.pageMode)
        name("bookmarked").value(record.bookmarked)
        name("createdAt").value(record.createdAt)
        name("updatedAt").value(record.updatedAt)
        writeNullableString("pdfSourceId", record.pdfSourceId)
        name("pdfPageIndex")
        if (record.pdfPageIndex == null) nullValue() else value(record.pdfPageIndex.toLong())
    }

    private fun JsonWriter.writeChapter(record: BackupChapter) {
        name("kind").value("chapter")
        name("id").value(record.id)
        name("notebookId").value(record.notebookId)
        name("title").value(record.title)
        name("colorArgb").value(record.colorArgb.toLong())
        name("orderIndex").value(record.orderIndex.toLong())
    }

    private fun JsonWriter.writePdfSource(record: BackupPdfSource) {
        name("kind").value("pdfSource")
        name("id").value(record.id)
        name("notebookId").value(record.notebookId)
        name("assetId").value(record.assetId)
        name("displayName").value(record.displayName)
        name("pageCount").value(record.pageCount.toLong())
        name("byteSize").value(record.byteSize)
        name("sha256").value(record.sha256)
        name("createdAt").value(record.createdAt)
    }

    private fun JsonWriter.writeStroke(record: BackupStroke) {
        name("kind").value("stroke")
        name("id").value(record.id)
        name("pageId").value(record.pageId)
        name("zIndex").value(record.zIndex.toLong())
        name("brushKind").value(record.brushKind)
        name("colorArgb").value(record.colorArgb.toLong())
        name("size").value(record.size.toDouble())
        name("epsilon").value(record.epsilon.toDouble())
        name("inputs").value(Base64.encodeToString(record.inputs, Base64.NO_WRAP))
    }

    private fun JsonWriter.writeElement(record: BackupElement) {
        name("kind").value("element")
        name("id").value(record.id)
        name("pageId").value(record.pageId)
        name("zIndex").value(record.zIndex.toLong())
        name("elementKind").value(record.kind)
        name("x").value(record.x.toDouble())
        name("y").value(record.y.toDouble())
        name("width").value(record.width.toDouble())
        name("height").value(record.height.toDouble())
        name("rotation").value(record.rotation.toDouble())
        writeNullableString("text", record.text)
        writeNullableString("assetId", record.assetId)
        writeNullableString("shapeKind", record.shapeKind)
        writeNullableString("expression", record.expression)
        writeNullableString("resultText", record.resultText)
        writeNullableString("ocrRegions", record.ocrRegions)
    }

    private fun JsonWriter.writeBlock(record: BackupBlock) {
        name("kind").value("block")
        name("id").value(record.id)
        name("pageId").value(record.pageId)
        name("orderIndex").value(record.orderIndex.toLong())
        name("blockKind").value(record.kind)
        writeNullableString("text", record.text)
        name("checked").value(record.checked)
        name("indent").value(record.indent.toLong())
        name("alignment").value(record.alignment)
        writeNullableString("payloadId", record.payloadId)
    }

    private fun JsonWriter.writeNullableString(name: String, value: String?) {
        name(name)
        if (value == null) nullValue() else value(value)
    }

    private fun JsonReader.readNotebook(): BackupNotebook {
        var id: String? = null
        var title: String? = null
        var coverColor: String? = null
        var coverPattern: String? = null
        var defaultPaper: String? = null
        var orientation: String? = null
        var fingerDrawing: Boolean? = null
        var favorite: Boolean? = null
        var createdAt: Long? = null
        var updatedAt: Long? = null
        var trashedAt: Long? = null
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "kind" -> verifyKind("notebook")
                "id" -> id = nextBoundedString("id", MAX_SHORT_TEXT_CHARS)
                "title" -> title = nextBoundedString("title", MAX_TEXT_CHARS)
                "coverColor" -> coverColor = nextBoundedString("coverColor", MAX_SHORT_TEXT_CHARS)
                "coverPattern" ->
                    coverPattern = nextBoundedString("coverPattern", MAX_SHORT_TEXT_CHARS)
                "defaultPaper" ->
                    defaultPaper = nextBoundedString("defaultPaper", MAX_SHORT_TEXT_CHARS)
                "orientation" ->
                    orientation = nextBoundedString("orientation", MAX_SHORT_TEXT_CHARS)
                "fingerDrawing" -> fingerDrawing = nextBoolean()
                "favorite" -> favorite = nextBoolean()
                "createdAt" -> createdAt = nextLongField("createdAt")
                "updatedAt" -> updatedAt = nextLongField("updatedAt")
                "trashedAt" -> trashedAt = nextNullableLong("trashedAt")
                else -> skipValue()
            }
        }
        endObject()
        return BackupNotebook(
            id = requiredString(id, "id"),
            title = requiredString(title, "title"),
            coverColor = requiredString(coverColor, "coverColor"),
            coverPattern = requiredString(coverPattern, "coverPattern"),
            defaultPaper = requiredString(defaultPaper, "defaultPaper"),
            orientation = requiredString(orientation, "orientation"),
            fingerDrawing = required(fingerDrawing, "fingerDrawing"),
            favorite = required(favorite, "favorite"),
            createdAt = required(createdAt, "createdAt"),
            updatedAt = required(updatedAt, "updatedAt"),
            trashedAt = trashedAt,
        ).also(::validate)
    }

    private fun JsonReader.readPage(): BackupPage {
        var id: String? = null
        var notebookId: String? = null
        var pageIndex: Int? = null
        var paper: String? = null
        var widthPoints: Int? = null
        var heightPoints: Int? = null
        var chapterId: String? = null
        var title: String? = null
        var pageMode = "PAPER"
        var bookmarked = false
        var createdAt = 0L
        var updatedAt = 0L
        var pdfSourceId: String? = null
        var pdfPageIndex: Int? = null
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "kind" -> verifyKind("page")
                "id" -> id = nextBoundedString("id", MAX_SHORT_TEXT_CHARS)
                "notebookId" ->
                    notebookId = nextBoundedString("notebookId", MAX_SHORT_TEXT_CHARS)
                "pageIndex" -> pageIndex = nextIntField("pageIndex")
                "paper" -> paper = nextBoundedString("paper", MAX_SHORT_TEXT_CHARS)
                "widthPoints" -> widthPoints = nextIntField("widthPoints")
                "heightPoints" -> heightPoints = nextIntField("heightPoints")
                "chapterId" -> chapterId = nextNullableString("chapterId", MAX_SHORT_TEXT_CHARS)
                "title" -> title = nextNullableString("title", MAX_TEXT_CHARS)
                "pageMode" -> pageMode = nextBoundedString("pageMode", MAX_SHORT_TEXT_CHARS)
                "bookmarked" -> bookmarked = nextBoolean()
                "createdAt" -> createdAt = nextLongField("createdAt")
                "updatedAt" -> updatedAt = nextLongField("updatedAt")
                "pdfSourceId" -> pdfSourceId = nextNullableString("pdfSourceId", MAX_SHORT_TEXT_CHARS)
                "pdfPageIndex" -> pdfPageIndex = nextNullableInt("pdfPageIndex")
                else -> skipValue()
            }
        }
        endObject()
        return BackupPage(
            id = requiredString(id, "id"),
            notebookId = requiredString(notebookId, "notebookId"),
            pageIndex = required(pageIndex, "pageIndex"),
            paper = requiredString(paper, "paper"),
            widthPoints = required(widthPoints, "widthPoints"),
            heightPoints = required(heightPoints, "heightPoints"),
            chapterId = chapterId,
            title = title,
            pageMode = pageMode,
            bookmarked = bookmarked,
            createdAt = createdAt,
            updatedAt = updatedAt,
            pdfSourceId = pdfSourceId,
            pdfPageIndex = pdfPageIndex,
        ).also(::validate)
    }

    private fun JsonReader.readStroke(): BackupStroke {
        var id: String? = null
        var pageId: String? = null
        var zIndex: Int? = null
        var brushKind: String? = null
        var colorArgb: Int? = null
        var size: Float? = null
        var epsilon: Float? = null
        var inputs: ByteArray? = null
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "kind" -> verifyKind("stroke")
                "id" -> id = nextBoundedString("id", MAX_SHORT_TEXT_CHARS)
                "pageId" -> pageId = nextBoundedString("pageId", MAX_SHORT_TEXT_CHARS)
                "zIndex" -> zIndex = nextIntField("zIndex")
                "brushKind" ->
                    brushKind = nextBoundedString("brushKind", MAX_SHORT_TEXT_CHARS)
                "colorArgb" -> colorArgb = nextIntField("colorArgb")
                "size" -> size = nextFiniteFloat("size")
                "epsilon" -> epsilon = nextFiniteFloat("epsilon")
                "inputs" -> {
                    val encoded = nextBoundedString("inputs", MAX_RECORD_CHARS)
                    inputs = Base64.decode(encoded, Base64.NO_WRAP)
                }
                else -> skipValue()
            }
        }
        endObject()
        return BackupStroke(
            id = requiredString(id, "id"),
            pageId = requiredString(pageId, "pageId"),
            zIndex = required(zIndex, "zIndex"),
            brushKind = requiredString(brushKind, "brushKind"),
            colorArgb = required(colorArgb, "colorArgb"),
            size = required(size, "size"),
            epsilon = required(epsilon, "epsilon"),
            inputs = required(inputs, "inputs"),
        ).also(::validate)
    }

    private fun JsonReader.readChapter(): BackupChapter {
        var id: String? = null
        var notebookId: String? = null
        var title: String? = null
        var colorArgb: Int? = null
        var orderIndex: Int? = null
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "kind" -> verifyKind("chapter")
                "id" -> id = nextBoundedString("id", MAX_SHORT_TEXT_CHARS)
                "notebookId" -> notebookId = nextBoundedString("notebookId", MAX_SHORT_TEXT_CHARS)
                "title" -> title = nextBoundedString("title", MAX_TEXT_CHARS)
                "colorArgb" -> colorArgb = nextIntField("colorArgb")
                "orderIndex" -> orderIndex = nextIntField("orderIndex")
                else -> skipValue()
            }
        }
        endObject()
        return BackupChapter(
            id = requiredString(id, "id"),
            notebookId = requiredString(notebookId, "notebookId"),
            title = requiredString(title, "title"),
            colorArgb = required(colorArgb, "colorArgb"),
            orderIndex = required(orderIndex, "orderIndex"),
        ).also(::validate)
    }

    private fun JsonReader.readPdfSource(): BackupPdfSource {
        var id: String? = null
        var notebookId: String? = null
        var assetId: String? = null
        var displayName: String? = null
        var pageCount: Int? = null
        var byteSize: Long? = null
        var sha256: String? = null
        var createdAt: Long? = null
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "kind" -> verifyKind("pdfSource")
                "id" -> id = nextBoundedString("id", MAX_SHORT_TEXT_CHARS)
                "notebookId" -> notebookId = nextBoundedString("notebookId", MAX_SHORT_TEXT_CHARS)
                "assetId" -> assetId = nextBoundedString("assetId", MAX_SHORT_TEXT_CHARS)
                "displayName" -> displayName = nextBoundedString("displayName", MAX_TEXT_CHARS)
                "pageCount" -> pageCount = nextIntField("pageCount")
                "byteSize" -> byteSize = nextLongField("byteSize")
                "sha256" -> sha256 = nextBoundedString("sha256", MAX_SHORT_TEXT_CHARS)
                "createdAt" -> createdAt = nextLongField("createdAt")
                else -> skipValue()
            }
        }
        endObject()
        return BackupPdfSource(
            id = requiredString(id, "id"),
            notebookId = requiredString(notebookId, "notebookId"),
            assetId = requiredString(assetId, "assetId"),
            displayName = requiredString(displayName, "displayName"),
            pageCount = required(pageCount, "pageCount"),
            byteSize = required(byteSize, "byteSize"),
            sha256 = requiredString(sha256, "sha256"),
            createdAt = required(createdAt, "createdAt"),
        ).also(::validate)
    }

    private fun JsonReader.readElement(): BackupElement {
        var id: String? = null
        var pageId: String? = null
        var zIndex: Int? = null
        var elementKind: String? = null
        var x: Float? = null
        var y: Float? = null
        var width: Float? = null
        var height: Float? = null
        var rotation: Float? = null
        var text: String? = null
        var assetId: String? = null
        var shapeKind: String? = null
        var expression: String? = null
        var resultText: String? = null
        var ocrRegions: String? = null
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "kind" -> verifyKind("element")
                "id" -> id = nextBoundedString("id", MAX_SHORT_TEXT_CHARS)
                "pageId" -> pageId = nextBoundedString("pageId", MAX_SHORT_TEXT_CHARS)
                "zIndex" -> zIndex = nextIntField("zIndex")
                "elementKind" ->
                    elementKind = nextBoundedString("elementKind", MAX_SHORT_TEXT_CHARS)
                "x" -> x = nextFiniteFloat("x")
                "y" -> y = nextFiniteFloat("y")
                "width" -> width = nextFiniteFloat("width")
                "height" -> height = nextFiniteFloat("height")
                "rotation" -> rotation = nextFiniteFloat("rotation")
                "text" -> text = nextNullableString("text", MAX_TEXT_CHARS)
                "assetId" -> assetId = nextNullableString("assetId", MAX_SHORT_TEXT_CHARS)
                "shapeKind" ->
                    shapeKind = nextNullableString("shapeKind", MAX_SHORT_TEXT_CHARS)
                "expression" -> expression = nextNullableString("expression", MAX_TEXT_CHARS)
                "resultText" -> resultText = nextNullableString("resultText", MAX_TEXT_CHARS)
                "ocrRegions" ->
                    ocrRegions = nextNullableString("ocrRegions", MAX_OCR_REGION_DATA_LENGTH)
                else -> skipValue()
            }
        }
        endObject()
        return BackupElement(
            id = requiredString(id, "id"),
            pageId = requiredString(pageId, "pageId"),
            zIndex = required(zIndex, "zIndex"),
            kind = requiredString(elementKind, "elementKind"),
            x = required(x, "x"),
            y = required(y, "y"),
            width = required(width, "width"),
            height = required(height, "height"),
            rotation = required(rotation, "rotation"),
            text = text,
            assetId = assetId,
            shapeKind = shapeKind,
            expression = expression,
            resultText = resultText,
            ocrRegions = ocrRegions,
        ).also(::validate)
    }

    private fun JsonReader.readBlock(): BackupBlock {
        var id: String? = null
        var pageId: String? = null
        var orderIndex: Int? = null
        var blockKind: String? = null
        var text: String? = null
        var checked = false
        var indent = 0
        var alignment = "START"
        var payloadId: String? = null
        beginObject()
        while (hasNext()) {
            when (nextName()) {
                "kind" -> verifyKind("block")
                "id" -> id = nextBoundedString("id", MAX_SHORT_TEXT_CHARS)
                "pageId" -> pageId = nextBoundedString("pageId", MAX_SHORT_TEXT_CHARS)
                "orderIndex" -> orderIndex = nextIntField("orderIndex")
                "blockKind" -> blockKind = nextBoundedString("blockKind", MAX_SHORT_TEXT_CHARS)
                "text" -> text = nextNullableString("text", MAX_TEXT_CHARS)
                "checked" -> checked = nextBoolean()
                "indent" -> indent = nextIntField("indent")
                "alignment" -> alignment = nextBoundedString("alignment", MAX_SHORT_TEXT_CHARS)
                "payloadId" -> payloadId = nextNullableString("payloadId", MAX_SHORT_TEXT_CHARS)
                else -> skipValue()
            }
        }
        endObject()
        return BackupBlock(
            id = requiredString(id, "id"),
            pageId = requiredString(pageId, "pageId"),
            orderIndex = required(orderIndex, "orderIndex"),
            kind = requiredString(blockKind, "blockKind"),
            text = text,
            checked = checked,
            indent = indent,
            alignment = alignment,
            payloadId = payloadId,
        ).also(::validate)
    }

    private fun JsonReader.verifyKind(expected: String) {
        val actual = nextBoundedString("kind", MAX_SHORT_TEXT_CHARS)
        if (actual != expected) throw BackupFailure.UnknownRecordKind(actual)
    }

    private fun JsonReader.readFeatureFlags(): Set<String> {
        val flags = linkedSetOf<String>()
        beginArray()
        while (hasNext()) {
            if (flags.size >= MAX_FLAGS) throw BackupFailure.LimitExceeded("featureFlags")
            flags += nextBoundedString("featureFlags", MAX_SHORT_TEXT_CHARS)
        }
        endArray()
        return flags
    }

    private fun JsonReader.nextBoundedString(field: String, limit: Int): String {
        val value = nextString()
        if (value.length > limit) throw BackupFailure.LimitExceeded(field)
        return value
    }

    private fun JsonReader.nextNullableString(field: String, limit: Int): String? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextBoundedString(field, limit)
        }

    private fun JsonReader.nextIntField(field: String): Int =
        try {
            nextInt()
        } catch (failure: NumberFormatException) {
            throw BackupFailure.InvalidNumber(field)
        }

    private fun JsonReader.nextLongField(field: String): Long =
        try {
            nextLong()
        } catch (failure: NumberFormatException) {
            throw BackupFailure.InvalidNumber(field)
        }

    private fun JsonReader.nextNullableLong(field: String): Long? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextLongField(field)
        }

    private fun JsonReader.nextNullableInt(field: String): Int? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextIntField(field)
        }

    private fun JsonReader.nextFiniteFloat(field: String): Float {
        val value =
            try {
                nextDouble().toFloat()
            } catch (failure: NumberFormatException) {
                throw BackupFailure.InvalidNumber(field)
            }
        if (!value.isFinite()) throw BackupFailure.InvalidNumber(field)
        return value
    }

    private fun BufferedReader.readBoundedLine(): String? {
        val line = StringBuilder()
        while (true) {
            when (val value = read()) {
                -1 -> return if (line.isEmpty()) null else line.toString()
                '\n'.code -> return line.toString()
                '\r'.code -> Unit
                else -> {
                    if (line.length >= MAX_RECORD_CHARS) {
                        throw BackupFailure.LimitExceeded("record")
                    }
                    line.append(value.toChar())
                }
            }
        }
    }

    private inline fun <T> parseJson(input: Reader, block: (JsonReader) -> T): T {
        val reader = JsonReader(input)
        return try {
            val result = block(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) throw BackupFailure.Malformed()
            result
        } catch (failure: BackupFailure) {
            throw failure
        } catch (failure: Exception) {
            throw BackupFailure.Malformed(failure)
        }
    }

    private fun validate(manifest: BackupManifest) {
        if (manifest.formatVersion !in MIN_BACKUP_FORMAT_VERSION..BACKUP_FORMAT_VERSION) {
            throw BackupFailure.UnsupportedVersion(manifest.formatVersion)
        }
        requireText(manifest.appVersion, "appVersion", MAX_SHORT_TEXT_CHARS)
        requireNonNegative(manifest.exportedAt, "exportedAt")
        requireNonNegative(manifest.notebookCount, "notebookCount")
        requireNonNegative(manifest.pageCount, "pageCount")
        requireNonNegative(manifest.assetCount, "assetCount")
        if (manifest.featureFlags.size > MAX_FLAGS) {
            throw BackupFailure.LimitExceeded("featureFlags")
        }
        manifest.featureFlags.forEach { requireText(it, "featureFlags", MAX_SHORT_TEXT_CHARS) }
    }

    private fun validate(record: BackupRecord) {
        when (record) {
            is BackupNotebook -> {
                requireText(record.id, "id", MAX_SHORT_TEXT_CHARS)
                requireText(record.title, "title", MAX_TEXT_CHARS)
                requireText(record.coverColor, "coverColor", MAX_SHORT_TEXT_CHARS)
                requireText(record.coverPattern, "coverPattern", MAX_SHORT_TEXT_CHARS)
                requireText(record.defaultPaper, "defaultPaper", MAX_SHORT_TEXT_CHARS)
                requireText(record.orientation, "orientation", MAX_SHORT_TEXT_CHARS)
                requireNonNegative(record.createdAt, "createdAt")
                requireNonNegative(record.updatedAt, "updatedAt")
                record.trashedAt?.let { requireNonNegative(it, "trashedAt") }
                requireEnum<CoverColor>(record.coverColor)
                requireEnum<CoverPattern>(record.coverPattern)
                requireEnum<PaperTemplate>(record.defaultPaper)
                requireEnum<PageOrientation>(record.orientation)
            }
            is BackupPage -> {
                requireText(record.id, "id", MAX_SHORT_TEXT_CHARS)
                requireText(record.notebookId, "notebookId", MAX_SHORT_TEXT_CHARS)
                requireText(record.paper, "paper", MAX_SHORT_TEXT_CHARS)
                requireNonNegative(record.pageIndex, "pageIndex")
                requirePositive(record.widthPoints, "widthPoints")
                requirePositive(record.heightPoints, "heightPoints")
                record.chapterId?.let { requireText(it, "chapterId", MAX_SHORT_TEXT_CHARS) }
                record.title?.let { requireSize(it, "title", MAX_TEXT_CHARS) }
                requireText(record.pageMode, "pageMode", MAX_SHORT_TEXT_CHARS)
                requireNonNegative(record.createdAt, "createdAt")
                requireNonNegative(record.updatedAt, "updatedAt")
                record.pdfSourceId?.let { requireText(it, "pdfSourceId", MAX_SHORT_TEXT_CHARS) }
                record.pdfPageIndex?.let { requireNonNegative(it, "pdfPageIndex") }
                if (record.widthPoints > MAX_PAGE_DIMENSION || record.heightPoints > MAX_PAGE_DIMENSION) {
                    throw BackupFailure.LimitExceeded("pageDimensions")
                }
                requireEnum<PaperTemplate>(record.paper)
                requireEnum<PageMode>(record.pageMode)
            }
            is BackupChapter -> {
                requireText(record.id, "id", MAX_SHORT_TEXT_CHARS)
                requireText(record.notebookId, "notebookId", MAX_SHORT_TEXT_CHARS)
                requireText(record.title, "title", MAX_TEXT_CHARS)
                requireNonNegative(record.orderIndex, "orderIndex")
            }
            is BackupPdfSource -> {
                requireText(record.id, "id", MAX_SHORT_TEXT_CHARS)
                requireText(record.notebookId, "notebookId", MAX_SHORT_TEXT_CHARS)
                requireText(record.assetId, "assetId", MAX_SHORT_TEXT_CHARS)
                requireText(record.displayName, "displayName", MAX_TEXT_CHARS)
                requirePositive(record.pageCount, "pageCount")
                requirePositive(record.byteSize, "byteSize")
                if (!record.sha256.matches(Regex("[0-9a-f]{64}"))) throw BackupFailure.Malformed()
                requireNonNegative(record.createdAt, "createdAt")
            }
            is BackupStroke -> {
                requireText(record.id, "id", MAX_SHORT_TEXT_CHARS)
                requireText(record.pageId, "pageId", MAX_SHORT_TEXT_CHARS)
                requireText(record.brushKind, "brushKind", MAX_SHORT_TEXT_CHARS)
                requireNonNegative(record.zIndex, "zIndex")
                requirePositive(record.size, "size")
                requireNonNegative(record.epsilon, "epsilon")
                if (record.inputs.size > MAX_STROKE_BYTES) {
                    throw BackupFailure.LimitExceeded("inputs")
                }
                requireEnum<BrushKind>(record.brushKind)
            }
            is BackupElement -> {
                requireText(record.id, "id", MAX_SHORT_TEXT_CHARS)
                requireText(record.pageId, "pageId", MAX_SHORT_TEXT_CHARS)
                requireText(record.kind, "elementKind", MAX_SHORT_TEXT_CHARS)
                requireNonNegative(record.zIndex, "zIndex")
                requireFinite(record.x, "x")
                requireFinite(record.y, "y")
                requirePositive(record.width, "width")
                requirePositive(record.height, "height")
                requireFinite(record.rotation, "rotation")
                record.text?.let { requireSize(it, "text", MAX_TEXT_CHARS) }
                record.assetId?.let { requireSize(it, "assetId", MAX_SHORT_TEXT_CHARS) }
                record.shapeKind?.let { requireSize(it, "shapeKind", MAX_SHORT_TEXT_CHARS) }
                record.expression?.let { requireSize(it, "expression", MAX_TEXT_CHARS) }
                record.resultText?.let { requireSize(it, "resultText", MAX_TEXT_CHARS) }
                record.ocrRegions?.let {
                    requireSize(it, "ocrRegions", MAX_OCR_REGION_DATA_LENGTH)
                    if (decodeImageOcrRegions(it).isEmpty()) throw BackupFailure.Malformed()
                }
                when (enumValue<ElementKind>(record.kind)) {
                    ElementKind.TEXT -> {
                        if (
                            record.text == null ||
                                record.assetId != null ||
                                record.shapeKind != null ||
                                record.expression != null ||
                                record.resultText != null ||
                                record.ocrRegions != null
                        ) throw BackupFailure.Malformed()
                        requireText(record.text, "text", MAX_ELEMENT_TEXT_CHARS)
                    }
                    ElementKind.IMAGE -> {
                        if (
                            record.assetId == null ||
                                record.shapeKind != null ||
                                record.expression != null ||
                                record.resultText != null
                        ) throw BackupFailure.Malformed()
                        record.text?.let { requireSize(it, "text", MAX_ELEMENT_TEXT_CHARS) }
                    }
                    ElementKind.SHAPE ->
                        if (
                            record.text != null ||
                                record.assetId != null ||
                                record.expression != null ||
                                record.resultText != null ||
                                record.ocrRegions != null
                        ) {
                            throw BackupFailure.Malformed()
                        } else {
                            requireEnum<ShapeKind>(record.shapeKind ?: throw BackupFailure.Malformed())
                        }
                    ElementKind.MATH -> {
                        if (
                            record.text != null ||
                                record.assetId != null ||
                                record.shapeKind != null ||
                                record.expression == null ||
                                record.resultText == null ||
                                record.ocrRegions != null
                        ) throw BackupFailure.Malformed()
                        requireText(record.expression, "expression", MAX_TEXT_CHARS)
                        requireText(record.resultText, "resultText", MAX_TEXT_CHARS)
                    }
                }
            }
            is BackupBlock -> {
                requireText(record.id, "id", MAX_SHORT_TEXT_CHARS)
                requireText(record.pageId, "pageId", MAX_SHORT_TEXT_CHARS)
                requireText(record.kind, "blockKind", MAX_SHORT_TEXT_CHARS)
                requireNonNegative(record.orderIndex, "orderIndex")
                record.text?.let { requireSize(it, "text", MAX_TEXT_CHARS) }
                requireNonNegative(record.indent, "indent")
                requireText(record.alignment, "alignment", MAX_SHORT_TEXT_CHARS)
                record.payloadId?.let { requireSize(it, "payloadId", MAX_SHORT_TEXT_CHARS) }
                requireEnum<BlockKind>(record.kind)
                if (record.alignment !in TEXT_ALIGNMENTS) throw BackupFailure.Malformed()
            }
        }
    }

    private inline fun <reified T : Enum<T>> requireEnum(value: String) {
        enumValue<T>(value)
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: throw BackupFailure.Malformed()

    private fun requireText(value: String, field: String, limit: Int) {
        if (value.isBlank()) throw BackupFailure.Malformed()
        requireSize(value, field, limit)
    }

    private fun requireSize(value: String, field: String, limit: Int) {
        if (value.length > limit) throw BackupFailure.LimitExceeded(field)
    }

    private fun requireNonNegative(value: Int, field: String) {
        if (value < 0) throw BackupFailure.InvalidNumber(field)
    }

    private fun requireNonNegative(value: Long, field: String) {
        if (value < 0L) throw BackupFailure.InvalidNumber(field)
    }

    private fun requireNonNegative(value: Float, field: String) {
        requireFinite(value, field)
        if (value < 0f) throw BackupFailure.InvalidNumber(field)
    }

    private fun requirePositive(value: Int, field: String) {
        if (value <= 0) throw BackupFailure.InvalidNumber(field)
    }

    private fun requirePositive(value: Long, field: String) {
        if (value <= 0L) throw BackupFailure.InvalidNumber(field)
    }

    private fun requirePositive(value: Float, field: String) {
        requireFinite(value, field)
        if (value <= 0f) throw BackupFailure.InvalidNumber(field)
    }

    private fun requireFinite(value: Float, field: String) {
        if (!value.isFinite()) throw BackupFailure.InvalidNumber(field)
    }

    private fun <T : Any> required(value: T?, field: String): T =
        value ?: throw BackupFailure.MissingField(field)

    private fun requiredString(value: String?, field: String): String =
        required(value, field).also { requireText(it, field, MAX_TEXT_CHARS) }
}
