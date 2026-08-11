package dev.omakey.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [WordEntity::class, BigramEntity::class, ClipboardEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class OmakeyDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao
    abstract fun bigramDao(): BigramDao
    abstract fun clipboardDao(): ClipboardDao

    companion object {
        @Volatile private var instance: OmakeyDatabase? = null

        fun getInstance(context: Context): OmakeyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmakeyDatabase::class.java,
                    "omakey.db",
                ).build().also { instance = it }
            }
    }
}
