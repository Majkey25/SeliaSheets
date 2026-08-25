package com.majkeylab.seliadocs.editor

import com.majkeylab.seliadocs.data.ElementEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
