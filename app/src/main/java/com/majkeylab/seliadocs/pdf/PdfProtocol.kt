package com.majkeylab.seliadocs.pdf

import kotlin.math.roundToInt

internal data class PdfRenderSize(val width: Int, val height: Int)

internal fun fitPdfRenderSize(width: Int, height: Int): PdfRenderSize {
    require(width > 0 && height > 0)
    val scale = minOf(1.0, PdfProtocol.MAX_RENDER_DIMENSION.toDouble() / maxOf(width, height))
    return PdfRenderSize(
        (width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1),
    )
}

internal object PdfProtocol {
    const val SUCCESS = "success"
    const val ERROR = "error"
    const val PAGE_COUNT = "pageCount"
    const val PAGE_WIDTHS = "pageWidths"
    const val PAGE_HEIGHTS = "pageHeights"
    const val SANDBOX_UID = "sandboxUid"
    const val ERROR_INVALID = "invalid_pdf"
    const val ERROR_LIMIT = "pdf_limit"
    const val MAX_PAGES = 2_000
    const val MAX_RENDER_DIMENSION = 4_096
    const val MAX_RENDER_PIXELS = 16_777_216L
}
