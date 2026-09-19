package com.majkeylab.seliadocs.pdf

import com.majkeylab.seliadocs.recognition.ImageOcrResult

internal data class PdfTextSearchMatch(val bounds: List<PdfTextBounds>, val isOcr: Boolean) {
    init {
        require(bounds.isNotEmpty() && bounds.size <= PdfProtocol.MAX_SELECTION_BOUNDS) { PdfProtocol.ERROR_LIMIT }
    }
}

internal data class PdfSearchPageResult(val matches: List<PdfTextSearchMatch>, val hasText: Boolean)

internal fun normalizePdfSearchQuery(query: String): String {
    require(query.length <= PdfProtocol.MAX_SEARCH_QUERY_LENGTH) { PdfProtocol.ERROR_LIMIT }
    require('\u0000' !in query) { PdfProtocol.ERROR_INVALID }
    return query.trim()
}

internal fun searchOcrText(ocr: ImageOcrResult, query: String): List<PdfTextSearchMatch> {
    val term = normalizePdfSearchQuery(query)
    if (term.isEmpty()) return emptyList()
    require(ocr.regions.size <= PdfProtocol.MAX_SELECTION_BOUNDS) { PdfProtocol.ERROR_LIMIT }
    val words = ocr.regions.filter { it.text.isNotBlank() }
    val text = StringBuilder()
    val starts = IntArray(words.size)
    words.forEachIndexed { index, word ->
        require(text.length.toLong() + word.text.length + 1 <= MAX_SEARCH_OCR_TEXT_LENGTH) { PdfProtocol.ERROR_LIMIT }
        if (text.isNotEmpty()) text.append(' ')
        starts[index] = text.length
        text.append(word.text)
    }
    val content = text.toString()
    val matches = mutableListOf<PdfTextSearchMatch>()
    var offset = content.indexOf(term, ignoreCase = true)
    var rectangleCount = 0
    while (offset >= 0) {
        require(matches.size < PdfProtocol.MAX_SEARCH_MATCHES) { PdfProtocol.ERROR_LIMIT }
        val end = offset + term.length
        val bounds = words.indices.filter { starts[it] < end && starts[it] + words[it].text.length > offset }.map { index ->
            val word = words[index]
            PdfTextBounds(word.left, word.top, word.right, word.bottom)
        }
        rectangleCount += bounds.size
        require(rectangleCount <= PdfProtocol.MAX_SELECTION_BOUNDS) { PdfProtocol.ERROR_LIMIT }
        matches += PdfTextSearchMatch(bounds, isOcr = true)
        offset = content.indexOf(term, end, ignoreCase = true)
    }
    return matches
}

private const val MAX_SEARCH_OCR_TEXT_LENGTH = 100_000
