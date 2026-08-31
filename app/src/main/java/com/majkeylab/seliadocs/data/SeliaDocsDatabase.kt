package com.majkeylab.seliadocs.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NotebookEntity::class,
        PageEntity::class,
        StrokeEntity::class,
        ElementEntity::class,
        ChapterEntity::class,
        BlockEntity::class,
        TextMarkEntity::class,
        PdfSourceEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
internal abstract class SeliaDocsDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao

    abstract fun pageDao(): PageDao

    companion object {
        const val FILE_NAME = "seliadocs.db"

        @Volatile
        private var instance: SeliaDocsDatabase? = null

        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE pages ADD COLUMN chapterId TEXT")
                    db.execSQL("ALTER TABLE pages ADD COLUMN title TEXT")
                    db.execSQL("ALTER TABLE pages ADD COLUMN pageMode TEXT NOT NULL DEFAULT 'PAPER'")
                    db.execSQL("ALTER TABLE pages ADD COLUMN bookmarked INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE pages ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE pages ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL(
                        """UPDATE pages SET
                            createdAt = COALESCE((SELECT createdAt FROM notebooks WHERE notebooks.id = pages.notebookId), 0),
                            updatedAt = COALESCE((SELECT updatedAt FROM notebooks WHERE notebooks.id = pages.notebookId), 0)
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_pages_chapterId ON pages(chapterId)")
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS chapters (
                            id TEXT NOT NULL,
                            notebookId TEXT NOT NULL,
                            title TEXT NOT NULL,
                            colorArgb INTEGER NOT NULL,
                            orderIndex INTEGER NOT NULL,
                            PRIMARY KEY(id),
                            FOREIGN KEY(notebookId) REFERENCES notebooks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_notebookId ON chapters(notebookId)")
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_chapters_notebookId_orderIndex ON chapters(notebookId, orderIndex)",
                    )
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS blocks (
                            id TEXT NOT NULL,
                            pageId TEXT NOT NULL,
                            orderIndex INTEGER NOT NULL,
                            kind TEXT NOT NULL,
                            text TEXT,
                            checked INTEGER NOT NULL,
                            indent INTEGER NOT NULL,
                            alignment TEXT NOT NULL,
                            payloadId TEXT,
                            PRIMARY KEY(id),
                            FOREIGN KEY(pageId) REFERENCES pages(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_blocks_pageId ON blocks(pageId)")
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_blocks_pageId_orderIndex ON blocks(pageId, orderIndex)",
                    )
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS text_marks (
                            id TEXT NOT NULL,
                            blockId TEXT NOT NULL,
                            start INTEGER NOT NULL,
                            `end` INTEGER NOT NULL,
                            kind TEXT NOT NULL,
                            value TEXT,
                            PRIMARY KEY(id),
                            FOREIGN KEY(blockId) REFERENCES blocks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_text_marks_blockId ON text_marks(blockId)")
                }
            }

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS pdf_sources (
                            id TEXT NOT NULL,
                            notebookId TEXT NOT NULL,
                            assetId TEXT NOT NULL,
                            displayName TEXT NOT NULL,
                            pageCount INTEGER NOT NULL,
                            byteSize INTEGER NOT NULL,
                            sha256 TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            PRIMARY KEY(id),
                            FOREIGN KEY(notebookId) REFERENCES notebooks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_pdf_sources_notebookId ON pdf_sources(notebookId)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pdf_sources_assetId ON pdf_sources(assetId)")
                    db.execSQL("ALTER TABLE pages ADD COLUMN pdfSourceId TEXT")
                    db.execSQL("ALTER TABLE pages ADD COLUMN pdfPageIndex INTEGER")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_pages_pdfSourceId ON pages(pdfSourceId)")
                }
            }

        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE elements ADD COLUMN ocrRegions TEXT")
                }
            }

        fun get(context: Context): SeliaDocsDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                            SeliaDocsDatabase::class.java,
                            FILE_NAME,
                        )
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                            .build()
                            .also { instance = it }
                }
    }
}
