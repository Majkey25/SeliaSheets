package com.majkeylab.seliadocs.editor

import com.majkeylab.seliadocs.data.ElementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementTransformTest {
    private val element =
        ElementEntity(
            id = "element",
            pageId = "page",
            zIndex = 2,
            kind = "TEXT",
            x = 20f,
            y = 30f,
            width = 100f,
            height = 60f,
            rotation = 0f,
            text = "Physics",
            assetId = null,
            shapeKind = null,
            expression = null,
            resultText = null,
        )

    @Test
    fun moveAndResizeStayInsidePage() {
        assertEquals(
            ElementTransform(495f, 782f, 100f, 60f, 0f),
            clampElementTransform(
                proposed = ElementTransform(900f, 900f, 100f, 60f, 0f),
                pageWidth = 595f,
                pageHeight = 842f,
            ),
        )
        assertEquals(
            ElementTransform(20f, 30f, 24f, 24f, 0f),
            clampElementTransform(
                proposed = ElementTransform(20f, 30f, 1f, 0f, 0f),
                pageWidth = 595f,
                pageHeight = 842f,
            ),
        )
    }

    @Test
    fun invalidTransformIsRejected() {
        assertNull(
            validElementTransform(
                ElementTransform(Float.NaN, 0f, 10f, 10f, 0f),
            ),
        )
    }

    @Test
    fun tapSelectsTopmostElement() {
        val top = element.copy(id = "top", zIndex = 3)
        assertEquals(
            "top",
            selectElementAt(CanvasPoint(40f, 50f), listOf(element, top)),
        )
    }

    @Test
    fun tapSelectionUsesRotatedBounds() {
        val vertical = element.copy(x = 100f, y = 100f, width = 100f, height = 20f, rotation = 90f)

        assertEquals("element", selectElementAt(CanvasPoint(150f, 150f), listOf(vertical)))
        assertNull(selectElementAt(CanvasPoint(190f, 110f), listOf(vertical)))
    }

    @Test
    fun rotatedTransformCornersStayInsidePage() {
        val clamped =
            requireNotNull(
                clampElementTransform(
                    ElementTransform(-80f, -60f, 220f, 100f, 45f),
                    pageWidth = 595f,
                    pageHeight = 842f,
                ),
            )

        assertTrue(clamped.x > -clamped.width / 2f)
        assertTrue(clamped.y > -clamped.height / 2f)
    }

    @Test
    fun lassoSelectsTopmostElementWhoseCenterIsInside() {
        val top = element.copy(id = "top", zIndex = 3)
        val lasso =
            listOf(
                CanvasPoint(10f, 20f),
                CanvasPoint(140f, 20f),
                CanvasPoint(140f, 110f),
                CanvasPoint(10f, 110f),
            )

        assertEquals("top", selectElementWithLasso(lasso, listOf(element, top)))
    }

    @Test
    fun lassoDoesNotSelectElementOutsideItsDrawnPolygon() {
        val lasso =
            listOf(
                CanvasPoint(0f, 0f),
                CanvasPoint(140f, 0f),
                CanvasPoint(0f, 80f),
            )

        assertNull(selectElementWithLasso(lasso, listOf(element)))
    }
}
