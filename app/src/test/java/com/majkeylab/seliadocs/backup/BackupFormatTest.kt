package com.majkeylab.seliadocs.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupFormatTest {
    @Test
    fun shapeStyleBackupsRequireVersionSevenWithoutDroppingLegacyReaders() {
        assertEquals(7, BACKUP_FORMAT_VERSION)
        assertEquals(1, MIN_BACKUP_FORMAT_VERSION)
    }

    @Test
    fun responsivePenBackupsRequireANewerReaderButOldBackupsRemainReadable() {
        // Released v0.6.0-beta.1 readers support formats 1 through 4, not RESPONSIVE_PEN.
        assertFalse(BACKUP_FORMAT_VERSION in 1..4)
        assertEquals(1, MIN_BACKUP_FORMAT_VERSION)
    }
}
