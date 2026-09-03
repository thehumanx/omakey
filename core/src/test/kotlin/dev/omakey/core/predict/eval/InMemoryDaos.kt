package dev.omakey.core.predict.eval

import dev.omakey.core.db.WordDao
import dev.omakey.core.db.WordEntity

/**
 * Pure-JVM stand-in for [WordDao], so the engine can be exercised without an Android device or
 * Robolectric. Query semantics mirror the `@Query` annotations in [dev.omakey.core.db.Daos] — a
 * fake that ranked or filtered differently from production would make every number the evaluation
 * harness prints a measurement of the fake.
 */
class InMemoryWordDao(seed: List<WordEntity> = emptyList()) : WordDao {
    private val byWord = LinkedHashMap<String, WordEntity>().apply { seed.forEach { put(it.word, it) } }

    override suspend fun findExact(word: String): WordEntity? = byWord[word]

    override suspend fun upsert(word: WordEntity) { byWord[word.word] = word }

    override suspend fun allUserAdded(): List<WordEntity> = byWord.values.filter { it.isUserAdded }

    override suspend fun findUserAdded(query: String, limit: Int): List<WordEntity> =
        byWord.values.filter { it.isUserAdded && it.word.startsWith(query) }
            .sortedByDescending { it.lastUsedTimestamp }
            .take(limit)

    override suspend fun delete(word: String) { byWord.remove(word) }

    override suspend fun deleteAllUserAdded() { byWord.values.removeIf { it.isUserAdded } }

    override suspend fun rename(oldWord: String, newWord: String) {
        byWord.remove(oldWord)?.let { byWord[newWord] = it.copy(word = newWord) }
    }
}
