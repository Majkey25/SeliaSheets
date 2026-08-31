package com.majkeylab.seliadocs.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOcrRegionTest {
    @Test
    fun regionsRoundTripAndMatchCaseInsensitiveText() {
        val regions =
            listOf(
                ImageOcrRegion("Organic Chemistry", 0.1f, 0.2f, 0.8f, 0.4f),
                ImageOcrRegion("Lecture 12", 0.2f, 0.5f, 0.6f, 0.7f),
            )

        val encoded = encodeImageOcrRegions(regions)

        assertEquals(regions, decodeImageOcrRegions(encoded))
        assertEquals(listOf(regions.first()), matchingImageOcrRegions(encoded, "CHEMISTRY"))
        assertTrue(matchingImageOcrRegions(encoded, "missing").isEmpty())
    }

    @Test
    fun malformedAndInvalidRegionsAreIgnored() {
        val valid = encodeImageOcrRegions(listOf(ImageOcrRegion("Valid", 0f, 0f, 1f, 1f)))

        assertEquals(1, decodeImageOcrRegions("broken\n$valid\n%%%%,0,0,1,1").size)
        assertTrue(decodeImageOcrRegions("VmFsaWQ,0.8,0,0.2,1").isEmpty())
        assertTrue(matchingImageOcrRegions(valid, "   ").isEmpty())
    }
}
