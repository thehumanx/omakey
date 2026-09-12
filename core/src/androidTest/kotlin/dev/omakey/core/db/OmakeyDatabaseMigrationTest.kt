package dev.omakey.core.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real 1 → 2 → 3 → 4 migration chain against a real SQLite database on a real device.
 *
 * This exists because the `words` table is the only thing in this app a user cannot get back. The
 * bundled dictionary is regenerable and clipboard history is disposable; words the user taught the
 * keyboard are not. Three hand-written migrations had been shipped with nothing executing them
 * outside of production upgrades.
 *
 * **Why the old database is built by hand instead of with `MigrationTestHelper`.** `exportSchema`
 * was off until v4, so no JSON schema exists for v1–v3 and none can be recovered — the helper's
 * `createDatabase(name, version)` has nothing to build from. The v1 schema below is taken from the
 * entity definitions at commit 6499c08, the last release before migration 1→2. Opening the result
 * through `Room.databaseBuilder` is what validates it: Room checks the post-migration schema
 * against the current entities on every open and throws if they disagree, which is the same
 * assertion `runMigrationsAndValidate` would make.
 *
 * From v4 onward real schema JSON is committed, so a 4→5 test can use `MigrationTestHelper`
 * normally.
 */
@RunWith(AndroidJUnit4::class)
class OmakeyDatabaseMigrationTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun clean() = deleteDatabase()

    @After fun cleanUp() = deleteDatabase()

    private fun deleteDatabase() {
        context.deleteDatabase(DB_NAME)
    }

    /**
     * The whole chain at once, because that is the upgrade a real user on an old install performs —
     * testing each migration in isolation would miss any interaction between them, and 2→3's
     * `DELETE FROM words` followed by 3→4's `UPDATE words` is exactly such an interaction.
     */
    @Test
    fun migrates_v1_to_v4_preserving_user_words() {
        createV1Database {
            // Two rows that migration 2→3 has to tell apart: one the user saved by hand, one from
            // the 60,000-row bundled seed that 2→3 exists to delete.
            it.execSQL(
                "INSERT INTO words (word, frequency, isUserAdded, lastUsedTimestamp) VALUES " +
                    "('bishistha', 7, 1, 1000), ('the', 55000, 0, 2000)",
            )
            it.execSQL("INSERT INTO bigrams (previousWord, word, count) VALUES ('the', 'cat', 3)")
            it.execSQL(
                "INSERT INTO clipboard_history (content, timestamp, pinned) VALUES ('hello', 5000, 0)",
            )
        }

        withMigratedDatabase { db ->
            val words = runBlocking { db.wordDao().allUserAdded() }

            assertEquals("the seeded row should have been deleted by 2->3", 1, words.size)
            val saved = words.single()
            assertEquals("bishistha", saved.word)
            assertTrue("rows predating 3->4 came from the save gesture, so must stay explicit", saved.explicit)
            assertEquals(
                "3->4 must rescale frequency onto the fixed-point scale, or old saves read as " +
                    "a hundredth of their real weight and get evicted first",
                7 * WordEntity.COUNT_SCALE.toInt(),
                saved.frequency,
            )
            assertEquals("user words must survive with their timestamp intact", 1000L, saved.lastUsedTimestamp)

            val clips = runBlocking { db.clipboardDao().recent() }
            assertEquals(1, clips.size)
            assertEquals("hello", clips.single().content)
            assertEquals(
                "1->2 must default pre-existing clips to text, not leave them unclassified",
                ClipboardEntity.TYPE_TEXT,
                clips.single().contentType,
            )
            assertNull(clips.single().imagePath)
        }
    }

    /** 2→3 drops `bigrams` outright; if it survived, every install would keep carrying it. */
    @Test
    fun migration_2_to_3_drops_the_bigrams_table() {
        createV1Database {
            it.execSQL("INSERT INTO bigrams (previousWord, word, count) VALUES ('a', 'b', 1)")
        }
        withMigratedDatabase { db ->
            val cursor = db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'bigrams'",
            )
            cursor.use { assertEquals("bigrams should not exist after 2->3", 0, it.count) }
        }
    }

    /**
     * The destructive-migration trap migration 2→3's doc calls out: a fresh install must still end
     * up at v4 with a working schema, so the "just wipe it" shortcut is never tempting.
     */
    @Test
    fun fresh_install_opens_at_current_version() {
        withMigratedDatabase { db ->
            assertEquals(4, db.openHelper.readableDatabase.version)
            assertNotNull(runBlocking { db.wordDao().allUserAdded() })
        }
    }

    private fun createV1Database(populate: (SQLiteDatabase) -> Unit) {
        val path = context.getDatabasePath(DB_NAME)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.use {
            // Verbatim from the entity definitions at 6499c08. Room stores Boolean as INTEGER and
            // a non-null String PK as TEXT NOT NULL.
            it.execSQL(
                "CREATE TABLE words (word TEXT NOT NULL, frequency INTEGER NOT NULL, " +
                    "isUserAdded INTEGER NOT NULL, lastUsedTimestamp INTEGER NOT NULL, " +
                    "PRIMARY KEY(word))",
            )
            it.execSQL(
                "CREATE TABLE bigrams (previousWord TEXT NOT NULL, word TEXT NOT NULL, " +
                    "count INTEGER NOT NULL, PRIMARY KEY(previousWord, word))",
            )
            it.execSQL("CREATE INDEX index_bigrams_previousWord ON bigrams (previousWord)")
            it.execSQL(
                "CREATE TABLE clipboard_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "content TEXT NOT NULL, timestamp INTEGER NOT NULL, pinned INTEGER NOT NULL)",
            )
            populate(it)
            it.version = 1
        }
    }

    /** Opens through the production builder, so the migrations under test are the ones actually
     * shipped rather than a copy, and Room's own schema validation runs against the result. */
    private fun withMigratedDatabase(block: (OmakeyDatabase) -> Unit) {
        val db = Room.databaseBuilder(context, OmakeyDatabase::class.java, DB_NAME)
            .addMigrations(*OmakeyDatabase.MIGRATIONS)
            .build()
        db.use(block)
    }

    private fun <T : androidx.room.RoomDatabase> T.use(block: (T) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private companion object {
        /** Deliberately not "omakey.db": this test destroys the database it names, and a developer
         * running it on their own daily-driver device should not lose their learned words. */
        const val DB_NAME = "omakey_migration_test.db"
    }
}
