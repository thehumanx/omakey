package dev.omakey.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WordEntity::class, BigramEntity::class, ClipboardEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class OmakeyDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao
    abstract fun bigramDao(): BigramDao
    abstract fun clipboardDao(): ClipboardDao

    companion object {
        @Volatile private var instance: OmakeyDatabase? = null

        /** Adds image-clipboard support (`contentType`/`imagePath`) to `clipboard_history` — a
         * real migration rather than `fallbackToDestructiveMigration()`, since that would wipe
         * the `words` table too (the bundled 60k dictionary plus everything the user has taught
         * it), not just clipboard history. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN contentType TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN imagePath TEXT")
            }
        }

        fun getInstance(context: Context): OmakeyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmakeyDatabase::class.java,
                    "omakey.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
