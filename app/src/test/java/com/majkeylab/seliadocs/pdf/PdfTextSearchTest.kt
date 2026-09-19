package com.majkeylab.seliadocs.pdf

import com.majkeylab.seliadocs.recognition.ImageOcrRegion
import com.majkeylab.seliadocs.recognition.ImageOcrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfTextSearchTest {
    private val first = ImageOcrRegion("Alpha", 0.1f, 0.1f, 0.3f, 0.2f)
    private val second = ImageOcrRegion("Beta", 0.4f, 0.1f, 0.6f, 0.2f)

    @Test
    fun ocrSearchIsLiteralCaseInsensitiveAndSupportsPhrases() {
        val words = ImageOcrResult("Alpha Beta Alpha", listOf(first, second, first.copy(top = 0.3f, bottom = 0.4f)))
        assertEquals(2, searchOcrText(words, "ALPHA").size)
        val phrase = searchOcrText(words, "alpha beta").single()
        assertEquals(2, phrase.bounds.size)
        assertTrue(phrase.isOcr)
        assertEquals(listOf(PdfTextBounds(0.1f, 0.1f, 0.3f, 0.2f), PdfTextBounds(0.4f, 0.1f, 0.6f, 0.2f)), phrase.bounds)
        assertTrue(searchOcrText(words, "*?").isEmpty())
        assertTrue(searchOcrText(words, "missing").isEmpty())
        assertTrue(searchOcrText(words, "  ").isEmpty())
    }

    @Test
    fun queryAndResultBudgetsRejectOversizedInputExplicitly() {
        assertThrows(IllegalArgumentException::class.java) { normalizePdfSearchQuery("x".repeat(257)) }
        assertThrows(IllegalArgumentException::class.java) { normalizePdfSearchQuery("a\u0000b") }
        assertThrows(IllegalArgumentException::class.java) {
            searchOcrText(ImageOcrResult("", List(101) { first }), "Alpha")
        }
        assertThrows(IllegalArgumentException::class.java) {
            searchOcrText(ImageOcrResult("", List(2_001) { first }), "missing")
        }
        assertThrows(IllegalArgumentException::class.java) { PdfTextSearchMatch(emptyList(), true) }
    }
}
