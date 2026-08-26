package com.majkeylab.seliadocs.editor

import com.majkeylab.seliadocs.data.BlockEntity
import com.majkeylab.seliadocs.data.ElementEntity
import com.majkeylab.seliadocs.data.StrokeEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PageSnapshotWeightTest {
    @Test
    fun estimatorCountsStrokeInputsStringsAndEntityOverhead() {
        val stroke =
            StrokeEntity(
                id = "",
                pageId = "",
                zIndex = 0,
                brushKind = "",
                colorArgb = 0,
                size = 0f,
                epsilon = 0f,
                inputs = ByteArray(5),
            )
        val element =
            ElementEntity(
                id = "a",
                pageId = "b",
                zIndex = 0,
                kind = "c",
                x = 0f,
                y = 0f,
                width = 0f,
                height = 0f,
                rotation = 0f,
                text = "d",
                assetId = "e",
                shapeKind = "f",
                expression = "g",
                resultText = "h",
            )
        val block =
            BlockEntity(
                id = "i",
                pageId = "j",
                orderIndex = 0,
                kind = "k",
                text = "l",
                checked = false,
                indent = 0,
                alignment = "m",
                payloadId = "n",
            )

        assertEquals(225, estimatePageSnapshotWeight(listOf(stroke), listOf(element), listOf(block)))
    }

    @Test
    fun estimatorSaturatesAtIntMax() {
        assertEquals(Int.MAX_VALUE, saturatePageSnapshotWeight(Int.MAX_VALUE.toLong() + 1))
    }
}
