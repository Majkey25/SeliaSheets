package com.majkeylab.seliadocs.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupMemoryBudgetTest {
    @Test
    fun pageCostsCombineInkAndRepeatedImagesButDoNotAccumulateAcrossPages() {
        val budget = BackupMemoryBudget(48 * 1024)
        budget.stroke("first", 1024, 32) // 8192 bytes, half the page budget.
        budget.image("first", 1024)
        budget.image("second", 2048)
        assertEquals("pageContent", assertThrows(BackupFailure.LimitExceeded::class.java) {
            budget.image("first", 1)
        }.field)
    }

    @Test
    fun manySmallRecordsCannotGrowTheIndexPastTheHeapBudget() {
        val budget = BackupMemoryBudget(64 * 1024)
        val stroke = BackupStroke("id", "page", 0, "PRESSURE_PEN", 0, 1f, 0.1f, byteArrayOf())
        repeat(15) { budget.record(stroke) }
        assertEquals("recordIndex", assertThrows(BackupFailure.LimitExceeded::class.java) {
            budget.record(stroke)
        }.field)
    }

    @Test
    fun longRelationshipIdsCountTowardIndexMemory() {
        val budget = BackupMemoryBudget(64 * 1024)
        val stroke = BackupStroke("a".repeat(1024), "b".repeat(1024), 0, "PRESSURE_PEN", 0, 1f, 0.1f, byteArrayOf())
        repeat(3) { budget.record(stroke) }
        assertEquals("recordIndex", assertThrows(BackupFailure.LimitExceeded::class.java) {
            budget.record(stroke)
        }.field)
        assertEquals("recordIndex", assertThrows(BackupFailure.LimitExceeded::class.java) {
            budget.checkIndex(budget.indexBytes, budget.indexBytes)
        }.field)
    }

    @Test
    fun libraryBudgetIncludesExistingAndImportedTitles() {
        val budget = BackupMemoryBudget(64 * 1024)
        budget.checkLibrary(2048, 2048)
        assertEquals("libraryMetadata", assertThrows(BackupFailure.LimitExceeded::class.java) {
            budget.checkLibrary(2049, 2048)
        }.field)
    }

    @Test
    fun ordinaryLargeNotebookAndLongTitleRemainSupported() {
        val budget = BackupMemoryBudget(128L * 1024 * 1024)
        budget.record(BackupNotebook("book", "x".repeat(100_000), "PERIWINKLE", "SOLID", "GRID", "PORTRAIT", false, false, 1, 1, null))
        repeat(2000) { budget.record(BackupPage("page-$it", "book", it, "GRID", 595, 842)) }
        repeat(500) { budget.stroke("page-0", 1024, 100) }
        budget.image("page-0", 2048L * 2048)
        assertEquals(201_024L, budget.notebookBytes)
    }
}
