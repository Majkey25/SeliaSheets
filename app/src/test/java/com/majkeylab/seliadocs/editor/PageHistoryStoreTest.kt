package com.majkeylab.seliadocs.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageHistoryStoreTest {
    @Test
    fun returningToRecentPageKeepsUndoHistory() {
        val store = PageHistoryStore<String>(maxPages = 2, stepsPerPage = 3)
        store.history("one", "a").push("b")
        store.history("two", "x")

        assertEquals("a", store.history("one", "ignored").undo())
    }

    @Test
    fun thirdPageEvictsLeastRecentlyUsedHistory() {
        val store = PageHistoryStore<String>(maxPages = 2, stepsPerPage = 3)
        store.history("one", "a")
        store.history("two", "b")
        store.history("one", "ignored")
        store.history("three", "c")

        assertEquals(2, store.size)
        assertNull(store.existing("two"))
        assertEquals("a", store.existing("one")?.current)
    }
}
