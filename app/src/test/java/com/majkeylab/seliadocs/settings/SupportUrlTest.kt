package com.majkeylab.seliadocs.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SupportUrlTest {
    @Test
    fun supportUsesPublishedBuyMeACoffeePage() {
        assertEquals("https://www.buymeacoffee.com/majkey", SUPPORT_URL)
    }
}
