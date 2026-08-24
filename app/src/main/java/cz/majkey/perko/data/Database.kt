package cz.majkey.perko.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NotebookEntity::class, PageEntity::class, StrokeEntity::class, ElementEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class PerkoDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao

    abstract fun pageDao(): PageDao

    companion object {
        @Volatile
        private var instance: PerkoDatabase? = null

        fun get(context: Context): PerkoDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                PerkoDatabase::class.java,
                                "perko.db",
                            )
                            .build()
                            .also { instance = it }
                }
    }
}
