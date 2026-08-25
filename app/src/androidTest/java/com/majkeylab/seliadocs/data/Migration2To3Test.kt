package com.majkeylab.seliadocs.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration2To3Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SeliaDocsDatabase::class.java,
        )

    @Test
    fun existingPageAndBlocksSurvivePdfSchemaMigration() {
        helper.createDatabase(DATABASE_NAME, 2).use { database ->
            database.execSQL(
                """INSERT INTO notebooks VALUES
                    ('notebook', 'Physics', 'PERIWINKLE', 'SOLID', 'RULED', 'PORTRAIT', 0, 0, 100, 200, NULL)
                """.trimIndent(),
            )
            database.execSQL(
                """INSERT INTO pages
                    (id, notebookId, pageIndex, paper, widthPoints, heightPoints, chapterId, title,
                     pageMode, bookmarked, createdAt, updatedAt)
                    VALUES ('page', 'notebook', 0, 'RULED', 595, 842, NULL, 'Lecture', 'PAPER', 1, 100, 200)
                """.trimIndent(),
            )
            database.execSQL(
                "INSERT INTO blocks VALUES ('block', 'page', 0, 'PARAGRAPH', 'Existing text', 0, 0, 'START', NULL)",
            )
        }

        helper
            .runMigrationsAndValidate(DATABASE_NAME, 3, true, SeliaDocsDatabase.MIGRATION_2_3)
            .use { database ->
                database.query("SELECT pdfSourceId, pdfPageIndex, title FROM pages WHERE id = 'page'").use { cursor ->
                    cursor.moveToFirst()
                    assertNull(cursor.getString(0))
                    assertNull(cursor.getString(1))
                    assertEquals("Lecture", cursor.getString(2))
                }
                database.query("SELECT text FROM blocks WHERE id = 'block'").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("Existing text", cursor.getString(0))
                }
                database.query("SELECT COUNT(*) FROM pdf_sources").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(0, cursor.getInt(0))
                }
            }
    }

    private companion object {
        const val DATABASE_NAME = "migration-2-3"
    }
}
