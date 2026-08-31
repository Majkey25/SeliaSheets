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
class Migration3To4Test {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SeliaDocsDatabase::class.java,
        )

    @Test
    fun existingImageTextSurvivesForLazyRegionRegeneration() {
        helper.createDatabase(DATABASE_NAME, 3).use { database ->
            database.execSQL(
                """INSERT INTO notebooks VALUES
                    ('notebook', 'Chemistry', 'PERIWINKLE', 'SOLID', 'RULED', 'PORTRAIT', 0, 0, 100, 200, NULL)
                """.trimIndent(),
            )
            database.execSQL(
                """INSERT INTO pages
                    (id, notebookId, pageIndex, paper, widthPoints, heightPoints, chapterId, title,
                     pageMode, bookmarked, createdAt, updatedAt, pdfSourceId, pdfPageIndex)
                    VALUES ('page', 'notebook', 0, 'RULED', 595, 842, NULL, NULL,
                        'PAPER', 0, 100, 200, NULL, NULL)
                """.trimIndent(),
            )
            database.execSQL(
                """INSERT INTO elements
                    (id, pageId, zIndex, kind, x, y, width, height, rotation, text, assetId,
                     shapeKind, expression, resultText)
                    VALUES ('image', 'page', 0, 'IMAGE', 10, 20, 200, 100, 0,
                        'Organic chemistry', 'image.png', NULL, NULL, NULL)
                """.trimIndent(),
            )
        }

        helper
            .runMigrationsAndValidate(DATABASE_NAME, 4, true, SeliaDocsDatabase.MIGRATION_3_4)
            .use { database ->
                database.query("SELECT text, ocrRegions FROM elements WHERE id = 'image'").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("Organic chemistry", cursor.getString(0))
                    assertNull(cursor.getString(1))
                }
            }
    }

    private companion object {
        const val DATABASE_NAME = "migration-3-4"
    }
}
