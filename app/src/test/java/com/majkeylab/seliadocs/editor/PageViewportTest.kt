package com.majkeylab.seliadocs.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun inputCoordinateUsesMeasuredViewSize() {
        assertEquals(600f, viewportCoordinateToPage(300f, 500f, 1_000f), 0.001f)
        assertEquals(
            300f,
            viewportCoordinateToPage(
                coordinate = 300f,
                viewSize = 1_000f,
                pageSize = 1_000f,
            ),
            0.001f,
        )
        assertEquals(
            200f,
            viewportCoordinateToPage(
                coordinate = 200f,
                viewSize = 1_000f,
                pageSize = 1_000f,
            ),
            0.001f,
        )
    }

    @Test
    fun pageTransitionDirectionMatchesPageOrder() {
        assertEquals(1, pageTransitionDirection(1, 2))
        assertEquals(-1, pageTransitionDirection(2, 1))
        assertEquals(0, pageTransitionDirection(2, 2))
    }

    @Test
    fun overlayOwnershipBlocksViewportUpdates() {
        assertFalse(
            canUpdatePageViewport(
                hasStylus = false,
                overlayOwned = true,
                touchCount = 2,
                fingerDrawing = false,
            ),
        )
        assertTrue(
            canUpdatePageViewport(
                hasStylus = false,
                overlayOwned = false,
                touchCount = 2,
                fingerDrawing = false,
            ),
        )
    }
}
