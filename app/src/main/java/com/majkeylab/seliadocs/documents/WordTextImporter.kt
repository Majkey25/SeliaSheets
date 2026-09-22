package com.majkeylab.seliadocs.documents

import android.content.ContentResolver
import android.net.Uri
import com.majkeylab.seliadocs.data.PAGE_TEXT_BOTTOM
import com.majkeylab.seliadocs.data.PAGE_TEXT_MAX_LENGTH
import com.majkeylab.seliadocs.data.PAGE_TEXT_TOP
import com.majkeylab.seliadocs.data.PageOrientation
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.data.pageTextFits
import com.majkeylab.seliadocs.data.pageTextLayout
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val MAX_WORD_TEXT_PAGES = 2_000
internal const val WORD_DOCUMENT_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

internal class WordTextImporter(
    private val resolver: ContentResolver,
    private val cacheDir: File,
    private val repository: SeliaDocsRepository,
) {
    suspend fun import(notebookId: String, uri: Uri, afterPageId: String? = null): List<String> =
        withContext(Dispatchers.IO) {
            val temporary = File.createTempFile("word-import-", ".docx", cacheDir)
            try {
                val input = resolver.openInputStream(uri) ?: throw IOException("Word source unavailable")
                input.use { source ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(8_192)
                        var total = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (count == 0) throw IOException("Word source could not be read")
                            if (count > WordTextCodec.MAX_INPUT_BYTES - total) throw IOException("DOCX exceeds 16 MiB")
                            output.write(buffer, 0, count)
                            total += count
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val document = WordTextCodec.read(temporary)
                currentCoroutineContext().ensureActive()
                val notebook = repository.getNotebook(notebookId)
                val anchor = afterPageId?.let { requireNotNull(repository.getPage(it)) { "Page not found" } }
                require(anchor == null || anchor.notebookId == notebookId) { "Page belongs to another notebook" }
                val (defaultWidth, defaultHeight) = repository.pageSize(PageOrientation.valueOf(notebook.orientation))
                val width = anchor?.widthPoints ?: defaultWidth
                val height = anchor?.heightPoints ?: defaultHeight
                val pages = paginateWordText(document.paragraphs, width, height)
                currentCoroutineContext().ensureActive()
                repository.importWordText(notebookId, pages, width, height, afterPageId)
            } finally {
                temporary.delete()
            }
        }
}

internal suspend fun paginateWordText(paragraphs: List<String>, width: Int, height: Int): List<String> {
    val text = paragraphs.joinToString("\n")
    if (text.isEmpty()) return listOf("")
    val pages = mutableListOf<String>()
    var offset = 0
    while (offset < text.length) {
        currentCoroutineContext().ensureActive()
        require(pages.size < MAX_WORD_TEXT_PAGES) { "Word text exceeds $MAX_WORD_TEXT_PAGES pages" }
        var limit = minOf(text.length, offset + PAGE_TEXT_MAX_LENGTH)
        val explicitBreak = text.indexOf('\u000C', offset)
        if (explicitBreak >= 0 && explicitBreak < limit) limit = explicitBreak + 1
        if (limit < text.length && Character.isHighSurrogate(text[limit - 1])) limit--
        var end = minOf(limit, offset + 4_096)
        if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
        var layout = pageTextLayout(text.substring(offset, end), width)
        val availableHeight = height - PAGE_TEXT_TOP - PAGE_TEXT_BOTTOM
        while (layout.height <= availableHeight && end < limit) {
            currentCoroutineContext().ensureActive()
            end = minOf(limit, offset + (end - offset) * 2)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            layout = pageTextLayout(text.substring(offset, end), width)
        }
        if (layout.height > availableHeight) {
            var line = layout.lineCount - 1
            while (line >= 0 && layout.getLineBottom(line) > availableHeight) line--
            if (line < 0) throw IOException("Page is too small for Word text")
            end = offset + layout.getLineEnd(line)
            if (end < text.length && end > offset && Character.isHighSurrogate(text[end - 1])) end--
            // A trailing newline adds an empty line when the slice is laid out on its own.
            while (end > offset && !pageTextFits(text.substring(offset, end), width, height)) {
                end = text.offsetByCodePoints(end, -1)
            }
        }
        if (end <= offset) throw IOException("Page is too small for Word text")
        pages += text.substring(offset, end)
        offset = end
    }
    return pages
}
