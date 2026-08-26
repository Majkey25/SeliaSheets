package com.majkeylab.seliadocs.editor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PageHistoryTest {
    @Test
    fun historyDropsOldestStateAtOneHundred() {
        val history = PageHistory(0)
        repeat(101) { history.push(it + 1) }

        repeat(100) { history.undo() }

        assertEquals(1, history.current)
        assertNull(history.undo())
    }

    @Test
    fun newEditClearsRedo() {
        val history = PageHistory(0)
        history.push(1)
        history.undo()

        history.push(2)

        assertFalse(history.canRedo)
    }

    @Test
    fun historyDropsOldestStateAboveWeightBudget() {
        val history = PageHistory("", maxWeight = 8, weightOf = String::length)
        history.push("aaaa")
        history.push("bbbb")
        history.push("cccc")

        assertEquals("bbbb", history.undo())
        assertNull(history.undo())
    }

    @Test
    fun undoAndRedoDoNotConsumeMoreWeight() {
        val history = PageHistory("", maxWeight = 8, weightOf = String::length)
        history.push("aaaa")
        history.push("bbbb")
        history.push("cccc")

        assertEquals("bbbb", history.undo())
        assertEquals("cccc", history.redo())
        history.push("dddd")

        assertEquals("cccc", history.undo())
        assertNull(history.undo())
    }

    @Test
    fun pushAfterUndoSubtractsClearedRedoWeight() {
        val history = PageHistory("aaaa", maxWeight = 12, weightOf = String::length)
        history.push("bbbb")
        history.push("cccc")
        history.undo()

        history.push("dddd")

        assertEquals("bbbb", history.undo())
        assertEquals("aaaa", history.undo())
        assertNull(history.undo())
    }

    @Test
    fun oversizedCurrentDropsUndoAndRedo() {
        val history = PageHistory("aaaa", maxWeight = 8, weightOf = String::length)
        history.push("bbbb")
        history.undo()

        history.push("oversized")

        assertEquals("oversized", history.current)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo())
        assertNull(history.redo())
    }

    @Test
    fun failedUndoApplyRestoresHistoryPointerAndOriginalFailure() = runBlocking {
        val history = PageHistory("aaaa", maxWeight = 8, weightOf = String::length)
        history.push("bbbb")
        val expected = IllegalStateException("Room replace failed")

        val actual =
            try {
                history.undo { throw expected }
                fail("Expected undo apply failure")
            } catch (failure: IllegalStateException) {
                failure
            }

        assertSame(expected, actual)
        assertEquals("bbbb", history.current)
        history.push("cccc")
        assertEquals("bbbb", history.undo())
        assertNull(history.undo())
    }

    @Test
    fun failedRedoApplyRestoresHistoryPointerAndOriginalFailure() = runBlocking {
        val history = PageHistory("aaaa", maxWeight = 8, weightOf = String::length)
        history.push("bbbb")
        history.undo()
        val expected = IllegalStateException("Room replace failed")

        val actual =
            try {
                history.redo { throw expected }
                fail("Expected redo apply failure")
            } catch (failure: IllegalStateException) {
                failure
            }

        assertSame(expected, actual)
        assertEquals("aaaa", history.current)
        assertEquals("bbbb", history.redo())
        assertEquals("aaaa", history.undo())
        history.push("cccc")
        assertEquals("aaaa", history.undo())
        assertNull(history.undo())
    }
}
