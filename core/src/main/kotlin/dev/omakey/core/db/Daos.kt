package dev.omakey.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WordDao {
    @Query("SELECT * FROM words WHERE word LIKE :prefix || '%' ORDER BY frequency DESC LIMIT :limit")
    suspend fun findByPrefix(prefix: String, limit: Int): List<WordEntity>

    @Query("SELECT * FROM words WHERE word = :word LIMIT 1")
    suspend fun findExact(word: String): WordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(word: WordEntity)

    @Query("SELECT COUNT(*) FROM words")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeedBatch(words: List<WordEntity>)

    /** Loaded once at startup into an in-memory AutocorrectIndex — autocorrect fires on every
     * space/punctuation keystroke, far too often to round-trip SQLite per word. */
    @Query("SELECT * FROM words")
    suspend fun all(): List<WordEntity>

    /** Backs the Settings "Learned words" screen — only words the user's own typing added (not
     * the bundled 60k seed list), newest-used first, optionally filtered by prefix. */
    @Query("SELECT * FROM words WHERE isUserAdded = 1 AND word LIKE :query || '%' ORDER BY lastUsedTimestamp DESC LIMIT :limit")
    suspend fun findUserAdded(query: String = "", limit: Int = 500): List<WordEntity>

    /** Settings "Learned words" screen's delete action. Only affects Room — an already-running
     * IME's in-memory `AutocorrectIndex` isn't live-notified (unlike the SharedPreferences-backed
     * settings, the dictionary is deliberately load-once-at-startup, see DictionarySeeder), so a
     * forgotten word stops being protected from autocorrect the next time the keyboard process
     * restarts, not necessarily instantly. */
    @Query("DELETE FROM words WHERE word = :word")
    suspend fun delete(word: String)

    /** "Delete all" action on the same screen — clears every user-learned word in one go, leaving
     * the bundled seed dictionary untouched (`isUserAdded = 0` rows aren't matched). */
    @Query("DELETE FROM words WHERE isUserAdded = 1")
    suspend fun deleteAllUserAdded()
}

@Dao
interface BigramDao {
    @Query(
        "SELECT * FROM bigrams WHERE previousWord = :previousWord " +
            "AND word LIKE :prefix || '%' ORDER BY count DESC LIMIT :limit",
    )
    suspend fun findByPreviousWord(previousWord: String, prefix: String, limit: Int): List<BigramEntity>

    @Query("SELECT * FROM bigrams WHERE previousWord = :previousWord AND word = :word LIMIT 1")
    suspend fun findExact(previousWord: String, word: String): BigramEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bigram: BigramEntity)

    @Query("SELECT COUNT(*) FROM bigrams")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeedBatch(bigrams: List<BigramEntity>)
}

@Dao
interface ClipboardDao {
    @Query("SELECT * FROM clipboard_history ORDER BY pinned DESC, timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<ClipboardEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ClipboardEntity)

    @Query("DELETE FROM clipboard_history WHERE pinned = 0 AND id NOT IN " +
        "(SELECT id FROM clipboard_history ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trimUnpinned(keep: Int = 50)
}
