package com.majkeylab.seliadocs.library

import org.junit.Assert.assertEquals
import org.junit.Test

class NotebookTitleTest {
    @Test
    fun blankTitleUsesLocalizedFallback() {
        assertEquals("Untitled notebook", normalizeTitle("  ", "Untitled notebook"))
    }

    @Test
    fun titleIsTrimmed() {
        assertEquals("Physics", normalizeTitle("  Physics  ", "Untitled notebook"))
    }
}
