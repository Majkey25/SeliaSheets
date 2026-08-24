package cz.majkey.perko.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
