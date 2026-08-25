package com.majkeylab.seliadocs.backup

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader
import java.io.Writer

internal object BackupJson {
    private const val MAX_RECORD_CHARS = 16 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 1024 * 1024
    private const val MAX_STROKE_BYTES = 8 * 1024 * 1024
    private const val MAX_FLAGS = 128
    private const val MAX_SHORT_TEXT_CHARS = 1024

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
            is BackupStroke -> writer.writeStroke(record)
            is BackupElement -> writer.writeElement(record)
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
                "stroke" -> reader.readStroke()
                "element" -> reader.readElement()
                else -> throw BackupFailure.UnknownRecordKind(kind)
            }
        }
    }

    private fun readRecordKind(line: String): String =
        parseJson(StringReader(line)) { reader ->
            var kind: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "kind") {
                    if (kind != null) throw BackupFailure.Malformed()
                    kind = reader.nextBoundedString("kind", MAX_SHORT_TEXT_CHARS)
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            requiredString(kind, "kind")
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
        if (manifest.formatVersion != BACKUP_FORMAT_VERSION) {
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
            }
            is BackupPage -> {
                requireText(record.id, "id", MAX_SHORT_TEXT_CHARS)
                requireText(record.notebookId, "notebookId", MAX_SHORT_TEXT_CHARS)
                requireText(record.paper, "paper", MAX_SHORT_TEXT_CHARS)
                requireNonNegative(record.pageIndex, "pageIndex")
                requirePositive(record.widthPoints, "widthPoints")
                requirePositive(record.heightPoints, "heightPoints")
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
            }
        }
    }

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
