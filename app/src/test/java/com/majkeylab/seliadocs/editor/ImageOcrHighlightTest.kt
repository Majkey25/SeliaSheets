package com.majkeylab.seliadocs.editor

import com.majkeylab.seliadocs.recognition.ImageOcrRegion
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageOcrHighlightTest {
    @Test
    fun regionFollowsFittedImageInsideResizedElement() {
        val rect =
            fittedImageRegionRect(
                region = ImageOcrRegion("Match", 0.25f, 0.25f, 0.75f, 0.75f),
                containerWidth = 200f,
                containerHeight = 200f,
                imageWidth = 200f,
                imageHeight = 100f,
            )

        assertEquals(50f, rect.left)
        assertEquals(75f, rect.top)
        assertEquals(150f, rect.right)
        assertEquals(125f, rect.bottom)
    }
}
