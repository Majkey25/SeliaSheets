package com.majkeylab.seliadocs.pdf

import kotlin.math.roundToInt

internal data class PdfRenderSize(val width: Int, val height: Int)

internal fun fitPdfRenderSize(width: Int, height: Int, maxDimension: Int = PdfProtocol.MAX_RENDER_DIMENSION): PdfRenderSize {
    require(width > 0 && height > 0 && maxDimension in 1..PdfProtocol.MAX_RENDER_DIMENSION)
    val scale = minOf(1.0, maxDimension.toDouble() / maxOf(width, height))
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
    const val SELECTION_FOUND = "selectionFound"
    const val SELECTION_TEXT = "selectionText"
    const val SELECTION_BOUNDS = "selectionBounds"
    const val SEARCH_HAS_TEXT = "searchHasText"
    const val SEARCH_MATCH_COUNTS = "searchMatchCounts"
    const val SEARCH_BOUNDS = "searchBounds"
    const val ERROR_INVALID = "invalid_pdf"
    const val ERROR_LIMIT = "pdf_limit"
    const val ERROR_SELECTION_UNSUPPORTED = "pdf_selection_requires_android_15"
    const val ERROR_SEARCH_UNSUPPORTED = "pdf_search_requires_android_15"
    const val MAX_SEARCH_QUERY_LENGTH = 256
    const val MAX_SEARCH_MATCHES = 100
    const val MAX_SELECTION_TEXT = 10_000
    const val MAX_SELECTION_BOUNDS = 2_000
    const val MAX_PAGES = 2_000
    const val MAX_RENDER_DIMENSION = 4_096
    const val MAX_RENDER_PIXELS = 16_777_216L
}
