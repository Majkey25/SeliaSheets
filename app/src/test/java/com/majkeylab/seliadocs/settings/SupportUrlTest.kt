package com.majkeylab.seliadocs.settings

import com.majkeylab.seliadocs.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportUrlTest {
    @Test
    fun supportLinkExistsOnlyOutsidePlay() {
        val expected = if (BuildConfig.EXTERNAL_DONATIONS_ENABLED) "https://www.buymeacoffee.com/majkey" else ""
        assertEquals(expected, SUPPORT_URL)
    }

    @Test
    fun playSourceLinkAvoidsTheDonationReadme() {
        val expected = if (BuildConfig.EXTERNAL_DONATIONS_ENABLED) {
            "https://github.com/Majkey25/SeliaSheets"
        } else {
            "https://github.com/Majkey25/SeliaSheets/tree/main/app/src"
        }
        assertEquals(expected, SOURCE_URL)
    }

    @Test
    fun privacyLinkUsesTheSelectedDistributionChannel() {
        val expected = if (BuildConfig.EXTERNAL_DONATIONS_ENABLED) {
            "https://majkey25.github.io/SeliaSheets/privacy/"
        } else {
            "https://majkey25.github.io/SeliaSheets/play/privacy/"
        }
        assertEquals(expected, PRIVACY_URL)
    }
}
