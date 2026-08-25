package com.majkeylab.seliadocs.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration1To2Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SeliaDocsDatabase::class.java,
        )

    @Test
    fun existingNotebookContentSurvivesMigration() {
        helper.createDatabase(DATABASE_NAME, 1).use { database ->
            database.execSQL(
                """INSERT INTO notebooks VALUES
                    ('notebook', 'Physics', 'PERIWINKLE', 'SOLID', 'RULED', 'PORTRAIT', 0, 0, 100, 200, NULL)
                """.trimIndent(),
            )
            database.execSQL(
                "INSERT INTO pages VALUES ('page', 'notebook', 0, 'RULED', 595, 842)",
            )
            database.execSQL(
                "INSERT INTO strokes VALUES ('stroke', 'page', 0, 'PRESSURE_PEN', -1, 4.0, 0.1, X'00')",
            )
            database.execSQL(
                """INSERT INTO elements VALUES
                    ('element', 'page', 0, 'TEXT', 10.0, 20.0, 200.0, 80.0, 0.0,
                     'Existing', NULL, NULL, NULL, NULL)
                """.trimIndent(),
            )
        }

        helper
            .runMigrationsAndValidate(
                DATABASE_NAME,
                2,
                true,
                SeliaDocsDatabase.MIGRATION_1_2,
            ).use { database ->
                database.query(
                    "SELECT pageMode, chapterId, bookmarked, createdAt, updatedAt FROM pages WHERE id = 'page'",
                ).use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(PageMode.PAPER.name, cursor.getString(0))
                    assertNull(cursor.getString(1))
                    assertFalse(cursor.getInt(2) != 0)
                    assertEquals(100L, cursor.getLong(3))
                    assertEquals(200L, cursor.getLong(4))
                }
                assertEquals(1, count(database, "strokes"))
                assertEquals(1, count(database, "elements"))
                assertEquals(0, count(database, "chapters"))
                assertEquals(0, count(database, "blocks"))
            }
    }

    private fun count(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Int =
        database.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val DATABASE_NAME = "migration-1-2"
    }
}
