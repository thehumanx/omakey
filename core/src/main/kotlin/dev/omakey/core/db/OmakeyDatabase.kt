package dev.omakey.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WordEntity::class, ClipboardEntity::class, SnippetEntity::class],
    version = 7,
    exportSchema = true,
)
abstract class OmakeyDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao
    abstract fun clipboardDao(): ClipboardDao
    abstract fun snippetDao(): SnippetDao

    /** Folds the write-ahead log into the database file and empties it, so text deleted under
     * secure_delete is gone from disk too. See SnippetRepository's wipeFreedSpace. */
    fun checkpointAndTruncate() {
        // Stepped (moveToFirst), or the checkpoint never runs. Its first column is "busy": a reader
        // holding the log open stops the truncate; the next write tries again.
        openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
    }

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

        /**
         * Makes learned words per-language: adds `locale` and makes the primary key
         * `(locale, word)`, so each language keeps its own personal vocabulary (AGENTS.md §66
         * Phase 4). Every existing row was learned while English was the only language, so it is
         * tagged `en_US` — history, not a guess.
         *
         * SQLite cannot change a primary key in place, hence the rebuild: new table, copy, drop,
         * rename. The CREATE statement matches Room's own for [WordEntity] exactly (compare
         * `core/schemas/…/5.json`), because Room validates the schema on open and refuses a table
         * that differs even in column order.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `words_new` (`word` TEXT NOT NULL, `frequency` INTEGER NOT NULL, " +
                        "`isUserAdded` INTEGER NOT NULL, `lastUsedTimestamp` INTEGER NOT NULL, " +
                        "`explicit` INTEGER NOT NULL, `locale` TEXT NOT NULL, PRIMARY KEY(`locale`, `word`))",
                )
                db.execSQL(
                    "INSERT INTO words_new (word, frequency, isUserAdded, lastUsedTimestamp, explicit, locale) " +
                        "SELECT word, frequency, isUserAdded, lastUsedTimestamp, explicit, '${WordEntity.DEFAULT_LOCALE}' FROM words",
                )
                db.execSQL("DROP TABLE words")
                db.execSQL("ALTER TABLE words_new RENAME TO words")
            }
        }

        /**
         * Adds `words.display`: the spelling the user saved ("iPhone"), null for none. No DEFAULT,
         * so the column matches what Room expects for a nullable field without `defaultValue`.
         * Every existing word keeps all its fields and gets no fixed spelling.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE words ADD COLUMN display TEXT")
            }
        }

        /** Adds the `snippets` table (the Snippets page, Fleksy-style quick input). Words and
         * clipboard history are untouched. The statement is Room's own from `7.json`. */
        private const val SNIPPETS_CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS `snippets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `text` TEXT, `secretCipher` BLOB, `secretIv` BLOB, `shortcut` TEXT, " +
                "`position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(SNIPPETS_CREATE_SQL)
            }
        }

        /** Exposed so `OmakeyDatabaseMigrationTest` exercises the migrations that actually ship
         * rather than a copy of them, and so a new migration can't be added to the test's chain
         * while being forgotten here (or the reverse). */
        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

        fun getInstance(context: Context): OmakeyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OmakeyDatabase::class.java,
                    "omakey.db",
                ).addMigrations(*MIGRATIONS)
                    // Freed pages are overwritten with zeros, so deleted text (a snippet made a
                    // password, a removed snippet or clip, a forgotten word) doesn't linger in
                    // the file. A query, not execSQL: the PRAGMA returns a row, so it is stepped.
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            // Stepped, not just compiled: a cursor that is closed unread never runs
                            // its statement. Android's SQLite usually has this on already; this
                            // makes sure of it on this connection.
                            db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
                        }
                    })
                    .build().also { instance = it }
            }
    }
}
