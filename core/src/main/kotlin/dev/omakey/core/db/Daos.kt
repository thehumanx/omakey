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
