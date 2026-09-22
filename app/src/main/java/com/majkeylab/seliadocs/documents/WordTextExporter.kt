package com.majkeylab.seliadocs.documents

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.NotebookContent
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object WordTextExporter {
    fun write(content: NotebookContent, output: OutputStream) {
        val blocks = content.blocks.groupBy { it.pageId }
        val elements = content.elements.filter { it.kind == ElementKind.TEXT.name }.groupBy { it.pageId }
        val pageTexts = content.pages.sortedBy { it.pageIndex }.map { page ->
            (blocks[page.id].orEmpty().sortedBy { it.orderIndex }.map { it.text } +
                elements[page.id].orEmpty().sortedWith(compareBy({ it.y }, { it.x }, { it.zIndex })).map { it.text.orEmpty() })
                .joinToString("\n")
        }
        val paragraphs = pageTexts.mapIndexed { index, text ->
            if (index == 0 || pageTexts[index - 1].endsWith('\u000C')) text else "\u000C$text"
        }
        WordTextCodec.write(paragraphs, output)
    }

    suspend fun export(content: NotebookContent, cacheDir: File, resolver: ContentResolver, uri: Uri) = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("word-export-", ".docx", cacheDir)
        var destinationOpened = false
        try {
            temporary.outputStream().use { write(content, it) }
            currentCoroutineContext().ensureActive()
            val destination = resolver.openOutputStream(uri, "rwt") ?: throw IOException("Word destination unavailable")
            destinationOpened = true
            destination.use { output ->
                temporary.inputStream().use { input ->
                    val buffer = ByteArray(8_192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (failure: Throwable) {
            if (destinationOpened) {
                try {
                    check(DocumentsContract.deleteDocument(resolver, uri)) { "Incomplete Word copy could not be deleted" }
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        } finally {
            temporary.delete()
        }
    }
}
