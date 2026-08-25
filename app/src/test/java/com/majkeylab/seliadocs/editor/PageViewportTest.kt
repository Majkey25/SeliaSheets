package com.majkeylab.seliadocs.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class PageViewportTest {
    @Test
    fun zoomKeepsGestureFocusStable() {
        val result =
            updatePageViewport(
                current = PageViewport(),
                zoomChange = 2f,
                gesturePanX = 0f,
                gesturePanY = 0f,
                focusFromCenterX = 100f,
                focusFromCenterY = -50f,
                viewportWidth = 500f,
                viewportHeight = 700f,
                pageWidth = 500f,
                pageHeight = 700f,
            )

        assertEquals(2f, result.zoom)
        assertEquals(-100f, result.panX)
        assertEquals(50f, result.panY)
    }

    @Test
    fun panIsClampedToScaledPageBounds() {
        val result =
            updatePageViewport(
                current = PageViewport(zoom = 2f),
                zoomChange = 1f,
                gesturePanX = 10_000f,
                gesturePanY = -10_000f,
                focusFromCenterX = 0f,
                focusFromCenterY = 0f,
                viewportWidth = 500f,
                viewportHeight = 700f,
                pageWidth = 500f,
                pageHeight = 700f,
            )

        assertEquals(250f, result.panX)
        assertEquals(-350f, result.panY)
    }

    @Test
    fun fitWidthNeverShrinksOrExceedsMaximumZoom() {
        assertEquals(1f, fitPageWidth(400f, 500f).zoom)
        assertEquals(2f, fitPageWidth(1_000f, 500f).zoom)
        assertEquals(5f, fitPageWidth(10_000f, 500f).zoom)
    }
}
