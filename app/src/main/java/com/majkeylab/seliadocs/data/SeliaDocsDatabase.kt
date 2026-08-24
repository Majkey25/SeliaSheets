package com.majkeylab.seliadocs.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NotebookEntity::class, PageEntity::class, StrokeEntity::class, ElementEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class SeliaDocsDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao

    abstract fun pageDao(): PageDao

    companion object {
        const val FILE_NAME = "seliadocs.db"

        @Volatile
        private var instance: SeliaDocsDatabase? = null

        fun get(context: Context): SeliaDocsDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                SeliaDocsDatabase::class.java,
                                FILE_NAME,
                            )
                            .build()
                            .also { instance = it }
                }
    }
}
