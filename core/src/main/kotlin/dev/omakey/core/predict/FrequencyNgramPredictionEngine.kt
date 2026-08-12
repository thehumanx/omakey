package dev.omakey.core.predict

import dev.omakey.core.db.BigramDao
import dev.omakey.core.db.BigramEntity
import dev.omakey.core.db.WordDao
import dev.omakey.core.db.WordEntity

/**
 * v1 prediction engine: pure count-based ranking, no smoothing/backoff. Combines bigram
 * (previous-word -> next-word) suggestions with plain word-frequency prefix completion.
 */
class FrequencyNgramPredictionEngine(
    private val wordDao: WordDao,
    private val bigramDao: BigramDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : PredictionEngine {

    override suspend fun suggestNext(previousWord: String?, currentPrefix: String, limit: Int): List<String> {
        val results = LinkedHashSet<String>()

        if (previousWord != null) {
            val bigramMatches = bigramDao.findByPreviousWord(previousWord.lowercase(), currentPrefix, limit)
            results.addAll(bigramMatches.map { it.word })
        }

        if (results.size < limit) {
            val remaining = limit - results.size
            val wordMatches = wordDao.findByPrefix(currentPrefix, remaining + results.size)
                .map { it.word }
                .filterNot { it in results }
            results.addAll(wordMatches.take(remaining))
        }

        return results.take(limit).toList()
    }

    override suspend fun recordAcceptedWord(word: String, previousWord: String?) {
        val normalized = word.lowercase()
        val now = clock()
        val existing = wordDao.findExact(normalized)
        wordDao.upsert(
            WordEntity(
                word = normalized,
                frequency = (existing?.frequency ?: 0) + 1,
                isUserAdded = existing?.isUserAdded ?: true,
                lastUsedTimestamp = now,
            ),
        )

        if (previousWord != null) {
            val prevNormalized = previousWord.lowercase()
            val existingBigram = bigramDao.findExact(prevNormalized, normalized)
            bigramDao.upsert(
                BigramEntity(
                    previousWord = prevNormalized,
                    word = normalized,
                    count = (existingBigram?.count ?: 0) + 1,
                ),
            )
        }
    }

    override suspend fun bigramRank(previousWord: String, word: String): Int =
        bigramDao.findExact(previousWord.lowercase(), word.lowercase())?.count ?: 0

    override suspend fun saveWord(word: String) {
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty()) return
        val existing = wordDao.findExact(normalized)
        wordDao.upsert(
            WordEntity(
                word = normalized,
                frequency = (existing?.frequency ?: 0) + SAVE_WORD_BOOST,
                isUserAdded = true,
                lastUsedTimestamp = clock(),
            ),
        )
    }

    private companion object {
        const val SAVE_WORD_BOOST = 50
    }
}
