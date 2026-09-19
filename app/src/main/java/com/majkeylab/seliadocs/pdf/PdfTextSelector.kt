package com.majkeylab.seliadocs.pdf

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PdfTextSelector(context: Context) {
    private val sandbox = PdfSandboxClient(context)

    suspend fun select(
        file: File,
        pageIndex: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        allowOcr: Boolean = true,
    ): PdfTextSelection? {
        require(pageIndex >= 0)
        validatePdfSelectionCoordinates(startX, startY, endX, endY)
        if (Build.VERSION.SDK_INT >= 35) {
            sandbox.selectText(file, pageIndex, startX, startY, endX, endY)
                ?.takeIf { it.intersectsRegion(startX, startY, endX, endY) }
                ?.let { return it }
        } else if (!allowOcr) {
            throw UnsupportedOperationException(PdfProtocol.ERROR_SELECTION_UNSUPPORTED)
        }
        if (!allowOcr) return null
        val words = sandbox.recognizePageWords(file, pageIndex)
        return withContext(Dispatchers.Default) { selectOcrText(words, startX, startY, endX, endY) }
    }
}
