package dev.omakey.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WordEntity::class, BigramEntity::class, ClipboardEntity::class],
    version = 3,
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

        /** Adds a `language` column to `words`/`bigrams` and widens their primary keys to include
         * it (`word` alone -> `(word, language)`; `(previousWord, word)` ->
         * `(previousWord, word, language)`) — see [WordEntity]/[BigramEntity]'s own doc for why:
         * multi-language support means the same spelling can legitimately exist once per
         * language. SQLite can't `ALTER TABLE` a primary key in place, so this is a real
         * create-new-table + copy + drop + rename, same "never destructive" standard as
         * MIGRATION_1_2 — every existing row is preserved, tagged `language = 'en_us'` (the only
         * language that existed before this migration). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE words_new (" +
                        "word TEXT NOT NULL, language TEXT NOT NULL, frequency INTEGER NOT NULL, " +
                        "isUserAdded INTEGER NOT NULL, lastUsedTimestamp INTEGER NOT NULL, " +
                        "PRIMARY KEY(word, language))",
                )
                db.execSQL(
                    "INSERT INTO words_new (word, language, frequency, isUserAdded, lastUsedTimestamp) " +
                        "SELECT word, 'en_us', frequency, isUserAdded, lastUsedTimestamp FROM words",
                )
                db.execSQL("DROP TABLE words")
                db.execSQL("ALTER TABLE words_new RENAME TO words")

                db.execSQL(
                    "CREATE TABLE bigrams_new (" +
                        "previousWord TEXT NOT NULL, word TEXT NOT NULL, language TEXT NOT NULL, " +
                        "count INTEGER NOT NULL, PRIMARY KEY(previousWord, word, language))",
                )
                db.execSQL(
                    "INSERT INTO bigrams_new (previousWord, word, language, count) " +
                        "SELECT previousWord, word, 'en_us', count FROM bigrams",
                )
                db.execSQL("DROP TABLE bigrams")
                db.execSQL("ALTER TABLE bigrams_new RENAME TO bigrams")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bigrams_previousWord ON bigrams(previousWord)")
            }
        }

        fun getInstance(context: Context): OmakeyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmakeyDatabase::class.java,
                    "omakey.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
