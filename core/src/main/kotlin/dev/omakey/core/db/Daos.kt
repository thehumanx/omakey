package dev.omakey.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The `words` table now holds **only** words the user taught the keyboard. The bundled 60k
 * dictionary and 120k bigram corpus used to be seeded into SQLite row by row on first run and then
 * queried on every keystroke; both now live in the memory-mapped `assets/lm_en_us.bin` read by
 * [dev.omakey.core.predict.lm.LanguageModel], which needs no import step and no query.
 *
 * What remains here is the part Room is actually good at: a small mutable set of user data that
 * has to survive a reinstall of the process, and which the Settings "Learned words" screen edits.
 */
@Dao
interface WordDao {
    @Query("SELECT * FROM words WHERE word = :word LIMIT 1")
    suspend fun findExact(word: String): WordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(word: WordEntity)

    /** Loaded once at startup into [dev.omakey.core.predict.PersonalLanguageModel]. */
    @Query("SELECT * FROM words WHERE isUserAdded = 1")
    suspend fun allUserAdded(): List<WordEntity>

    /** Backs the Settings "Learned words" screen — newest-used first, optionally filtered. */
    @Query("SELECT * FROM words WHERE isUserAdded = 1 AND word LIKE :query || '%' ORDER BY lastUsedTimestamp DESC LIMIT :limit")
    suspend fun findUserAdded(query: String = "", limit: Int = 500): List<WordEntity>

    /** Settings "Learned words" screen's delete action. Only affects Room — a running IME's
     * in-memory `PersonalLanguageModel` isn't live-notified, so a forgotten word stops being protected
     * from autocorrect the next time the keyboard process starts, not necessarily instantly. */
    @Query("DELETE FROM words WHERE word = :word")
    suspend fun delete(word: String)

    /** "Delete all" on the same screen. */
    @Query("DELETE FROM words WHERE isUserAdded = 1")
    suspend fun deleteAllUserAdded()

    /** Same screen's "Edit" action — a plain rename, preferred over delete+upsert since it
     * preserves the row's existing frequency/lastUsedTimestamp instead of resetting them. */
    @Query("UPDATE words SET word = :newWord WHERE word = :oldWord")
    suspend fun rename(oldWord: String, newWord: String)
}

@Dao
interface ClipboardDao {
    @Query("SELECT * FROM clipboard_history ORDER BY pinned DESC, timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<ClipboardEntity>

    @Query("SELECT * FROM clipboard_history WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ClipboardEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ClipboardEntity)

    @Query("DELETE FROM clipboard_history WHERE pinned = 0 AND id NOT IN " +
        "(SELECT id FROM clipboard_history ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trimUnpinned(keep: Int = 50)

    /** Long-press-to-delete on a single clipboard item. Deliberately doesn't also delete the
     * backing image file for [ClipboardEntity.imagePath] here — the caller (which already has to
     * look the entry up to know whether it's an image before showing the delete confirmation)
     * handles that, so this DAO method stays a plain row delete. */
    @Query("DELETE FROM clipboard_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM clipboard_history")
    suspend fun deleteAll()
}
