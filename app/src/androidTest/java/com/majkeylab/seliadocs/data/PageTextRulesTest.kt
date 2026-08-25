package com.majkeylab.seliadocs.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageTextRulesTest {
    @Test
    fun textMustFitThePrintablePage() {
        assertTrue(pageTextFits("Newton's laws", 595, 842))
        assertFalse(pageTextFits(List(80) { "A full line" }.joinToString("\n"), 595, 842))
    }
}
