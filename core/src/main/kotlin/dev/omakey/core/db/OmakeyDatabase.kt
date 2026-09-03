package dev.omakey.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WordEntity::class, ClipboardEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class OmakeyDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao
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

        /**
         * Retires the seeded dictionary. The bundled vocabulary and bigram corpus now live in a
         * memory-mapped asset, so the ~60,000 seeded `words` rows and the whole `bigrams` table are
         * dead weight — several megabytes of SQLite carried by every existing install.
         *
         * Scoped by `isUserAdded = 0` rather than dropping and recreating `words`: rows the user
         * added by saving a word are the one thing in this database that cannot be regenerated, and
         * they must survive the upgrade. The seed-progress preferences that gated the old import
         * are cleared separately, in `OmakeyInputMethodService`, since a migration has no
         * `Context`.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS bigrams")
                db.execSQL("DELETE FROM words WHERE isUserAdded = 0")
            }
        }

        /**
         * Adds `explicit` and rescales `frequency` for the personal language model.
         *
         * Every pre-existing row came from the swipe-up save gesture, since after migration 2→3
         * that was the only path that wrote one — so `DEFAULT 1` isn't a guess, it's the correct
         * history, and those saves keep their immunity from autocorrect. `frequency` is multiplied
         * onto the new fixed-point scale so counts written before and after remain comparable;
         * without it older saves would read as a hundredth of their real weight and be evicted
         * first when the model hits its capacity limit.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE words ADD COLUMN explicit INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE words SET frequency = frequency * 100")
            }
        }

        fun getInstance(context: Context): OmakeyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmakeyDatabase::class.java,
                    "omakey.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
    }
}
