package com.majkeylab.seliadocs.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class SeliaWindowClassTest {
    @Test
    fun exactBreakpointsSelectExpectedWindowClass() {
        assertEquals(SeliaWindowClass.COMPACT, seliaWindowClass(599))
        assertEquals(SeliaWindowClass.MEDIUM, seliaWindowClass(600))
        assertEquals(SeliaWindowClass.MEDIUM, seliaWindowClass(839))
        assertEquals(SeliaWindowClass.EXPANDED, seliaWindowClass(840))
    }
}
