package com.majkeylab.seliadocs.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration5To6Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SeliaDocsDatabase::class.java)

    @Test
    fun legacyShapesKeepDefaultStyleAndExistingMarkupKeepsItsColor() {
        helper.createDatabase("migration-5-6", 5).use { database ->
            database.execSQL("INSERT INTO notebooks VALUES ('book', 'Study', 'SAGE', 'SOLID', 'BLANK', 'PORTRAIT', 0, 0, 1, 2, NULL)")
            database.execSQL("""INSERT INTO pages (id, notebookId, pageIndex, paper, widthPoints, heightPoints,
                pageMode, bookmarked, createdAt, updatedAt) VALUES ('page', 'book', 0, 'BLANK', 595, 842, 'PAPER', 0, 1, 2)""")
            database.execSQL("""INSERT INTO elements (id, pageId, zIndex, kind, x, y, width, height, rotation, shapeKind)
                VALUES ('shape', 'page', 0, 'SHAPE', 10, 20, 200, 100, 0, 'ELLIPSE')""")
            database.execSQL("""INSERT INTO elements (id, pageId, zIndex, kind, x, y, width, height, rotation, colorArgb, annotationRects)
                VALUES ('mark', 'page', 1, 'HIGHLIGHT', 10, 20, 200, 100, 0, 1728042319, '0,0,1,1')""")
        }
        helper.runMigrationsAndValidate("migration-5-6", 6, true, SeliaDocsDatabase.MIGRATION_5_6).use { database ->
            database.query("SELECT shapeKind, colorArgb, strokeWidth FROM elements WHERE id = 'shape'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ELLIPSE", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
            database.query("SELECT colorArgb, annotationRects, strokeWidth FROM elements WHERE id = 'mark'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1728042319, cursor.getInt(0))
                assertEquals("0,0,1,1", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }
}
