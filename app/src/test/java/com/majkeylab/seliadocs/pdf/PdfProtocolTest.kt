package com.majkeylab.seliadocs.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfProtocolTest {
    @Test
    fun renderSizeFitsSandboxLimitsWithoutChangingAspectRatio() {
        assertEquals(PdfRenderSize(595, 842), fitPdfRenderSize(595, 842))
        assertEquals(PdfRenderSize(4_096, 2_048), fitPdfRenderSize(14_400, 7_200))
    }
}
