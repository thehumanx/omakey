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
    private val byWord = LinkedHashMap<Pair<String, String>, WordEntity>().apply { seed.forEach { put(it.locale to it.word, it) } }

    override suspend fun findExact(locale: String, word: String): WordEntity? = byWord[locale to word]

    override suspend fun upsert(word: WordEntity) { byWord[word.locale to word.word] = word }

    override suspend fun allUserAdded(locale: String): List<WordEntity> = byWord.values.filter { it.isUserAdded && it.locale == locale }

    override suspend fun findUserAdded(query: String, minFrequency: Int, limit: Int): List<WordEntity> =
        byWord.values
            .filter {
                it.isUserAdded && it.word.startsWith(query) &&
                    (it.explicit || it.frequency >= minFrequency)
            }
            .sortedByDescending { it.lastUsedTimestamp }
            .take(limit)

    override suspend fun delete(locale: String, word: String) { byWord.remove(locale to word) }

    override suspend fun deleteAllUserAdded() { byWord.values.removeIf { it.isUserAdded } }

    override suspend fun rename(locale: String, oldWord: String, newWord: String) {
        byWord.remove(locale to oldWord)?.let { byWord[locale to newWord] = it.copy(word = newWord) }
    }
}
