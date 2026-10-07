package com.majkeylab.seliadocs.documents

import android.content.ContentResolver
import android.net.Uri
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class PlainTextImporter(
    private val resolver: ContentResolver,
    private val repository: SeliaDocsRepository,
) {
    suspend fun import(notebookId: String, uri: Uri, afterPageId: String? = null): List<String> =
        withContext(Dispatchers.IO) {
            val text = readText(uri)
            val notebook = repository.getNotebook(notebookId)
            val anchor = afterPageId?.let { requireNotNull(repository.getPage(it)) { "Page not found" } }
            require(anchor == null || anchor.notebookId == notebookId) { "Page belongs to another notebook" }
            val (defaultWidth, defaultHeight) = repository.pageSize(PageOrientation.valueOf(notebook.orientation))
            val width = anchor?.widthPoints ?: defaultWidth
            val height = anchor?.heightPoints ?: defaultHeight
            val pages = paginateWordText(listOf(text), width, height)
            currentCoroutineContext().ensureActive()
            repository.importWordText(notebookId, pages, width, height, afterPageId)
        }

    private suspend fun readText(uri: Uri): String {
        val input = resolver.openInputStream(uri) ?: throw IOException("Text source unavailable")
        val output = ByteArrayOutputStream()
        input.use { source ->
            val buffer = ByteArray(8_192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("Text source could not be read")
                if (count > MAX_INPUT_BYTES - output.size()) throw IOException("Text file exceeds 4 MiB")
                output.write(buffer, 0, count)
            }
        }
        currentCoroutineContext().ensureActive()
        val bytes = output.toByteArray()
        val (charset, offset) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        val decoded = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (error: CharacterCodingException) {
            throw IOException("Save the text as UTF-8 or BOM-marked UTF-16, then import it again", error)
        }
        if (decoded.length > MAX_TEXT_CHARS) throw IOException("Text exceeds 1,000,000 characters")
        val text = decoded.replace("\r\n", "\n").replace('\r', '\n')
        if (text.any { it != '\t' && it != '\n' && it != '\u000C' && Character.isISOControl(it) }) {
            throw IOException("Text contains binary control characters")
        }
        currentCoroutineContext().ensureActive()
        return text
    }

    private companion object {
        const val MAX_INPUT_BYTES = 4 * 1024 * 1024
        const val MAX_TEXT_CHARS = 1_000_000
    }
}
