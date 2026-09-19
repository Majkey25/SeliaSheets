package com.majkeylab.seliadocs.pdf

import android.content.Context
import android.os.Build
import com.majkeylab.seliadocs.recognition.ImageOcrResult
import com.majkeylab.seliadocs.recognition.recognizeBitmap
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class PdfTextSearcher(context: Context) {
    private val sandbox = PdfSandboxClient(context)

    suspend fun search(file: File, pageIndex: Int, query: String, allowOcr: Boolean = true): List<PdfTextSearchMatch> {
        require(pageIndex >= 0)
        val term = normalizePdfSearchQuery(query)
        currentCoroutineContext().ensureActive()
        if (term.isEmpty()) return emptyList()
        if (Build.VERSION.SDK_INT >= 35) {
            val result = sandbox.searchText(file, pageIndex, term)
            if (result.hasText || !allowOcr) return result.matches
        } else if (!allowOcr) {
            throw UnsupportedOperationException(PdfProtocol.ERROR_SEARCH_UNSUPPORTED)
        }
        val words = sandbox.recognizePageWords(file, pageIndex)
        return withContext(Dispatchers.Default) { searchOcrText(words, term) }
    }
}

// ponytail: one OCR bitmap at a time; use a bounded pool only if profiling justifies it.
private val pdfOcrMutex = Mutex()

internal suspend fun PdfSandboxClient.recognizePageWords(file: File, pageIndex: Int): ImageOcrResult = pdfOcrMutex.withLock {
    currentCoroutineContext().ensureActive()
    val pages = inspect(file).pages
    require(pageIndex in pages.indices) { PdfProtocol.ERROR_INVALID }
    val size = pages[pageIndex]
    require(size.width > 0 && size.height > 0) { PdfProtocol.ERROR_INVALID }
    val scale = 2_048f / maxOf(size.width, size.height)
    currentCoroutineContext().ensureActive()
    val bitmap = withContext(NonCancellable) {
        renderPage(file, pageIndex, (size.width * scale).roundToInt().coerceAtLeast(1),
            (size.height * scale).roundToInt().coerceAtLeast(1))
    }
    try {
        currentCoroutineContext().ensureActive()
        recognizeBitmap(bitmap, wordLevel = true)
    } finally {
        bitmap.recycle()
    }
}
