package com.majkeylab.seliadocs.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkContentRevisionTest {
    @Test
    fun equalRevisionsRemainUsableAtBothEndsOfTheSerialSpace() {
        assertTrue(isSameOrNewerInkRevision(0L, 0L))
        assertTrue(isSameOrNewerInkRevision(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun oneStepAdvancesAcrossOverflowButStaleRevisionsDoNot() {
        assertTrue(isSameOrNewerInkRevision(1L, 0L))
        assertFalse(isSameOrNewerInkRevision(0L, 1L))
        assertTrue(isSameOrNewerInkRevision(0L, Long.MAX_VALUE))
        assertTrue(isSameOrNewerInkRevision(1L, Long.MAX_VALUE))
        assertFalse(isSameOrNewerInkRevision(Long.MAX_VALUE, 0L))
    }

    @Test
    fun advancesMustStayStrictlyBelowHalfTheSerialSpace() {
        assertTrue(isSameOrNewerInkRevision(4_611_686_018_427_387_903L, 0L))
        assertFalse(isSameOrNewerInkRevision(4_611_686_018_427_387_904L, 0L))
        assertFalse(isSameOrNewerInkRevision(4_611_686_018_427_387_905L, 0L))
        assertFalse(isSameOrNewerInkRevision(0L, 4_611_686_018_427_387_904L))
    }
}
