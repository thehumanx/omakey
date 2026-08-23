package dev.omakey.core.predict

import dev.omakey.core.db.BigramDao
import dev.omakey.core.db.BigramEntity
import dev.omakey.core.db.WordDao
import dev.omakey.core.db.WordEntity

/**
 * v1 prediction engine: pure count-based ranking, no smoothing/backoff. Combines bigram
 * (previous-word -> next-word) suggestions with plain word-frequency prefix completion.
 *
 * Scoped to a single [language] (a [dev.omakey.core.language.LanguageDefinition.id]) for its
 * whole lifetime — every DAO call is filtered to it, so mixing this with, say, an
 * [dev.omakey.core.language.AutocorrectIndex] loaded for a different language would silently
 * predict against the wrong dictionary. The keyboard holds one instance per *enabled* language
 * (see KeyboardViewModel), not one global instance.
 */
class FrequencyNgramPredictionEngine(
    private val wordDao: WordDao,
    private val bigramDao: BigramDao,
    private val language: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : PredictionEngine {

    override suspend fun suggestNext(previousWord: String?, currentPrefix: String, limit: Int): List<String> {
        val results = LinkedHashSet<String>()

        if (previousWord != null) {
            val bigramMatches = bigramDao.findByPreviousWord(language, previousWord.lowercase(), currentPrefix, limit)
            results.addAll(bigramMatches.map { it.word })
        }

        if (results.size < limit) {
            val remaining = limit - results.size
            val wordMatches = wordDao.findByPrefix(language, currentPrefix, remaining + results.size)
                .map { it.word }
                .filterNot { it in results }
            results.addAll(wordMatches.take(remaining))
        }

        return results.take(limit).toList()
    }

    override suspend fun recordAcceptedWord(word: String, previousWord: String?) {
        val normalized = word.lowercase()
        val now = clock()
        val existing = wordDao.findExact(language, normalized)
        wordDao.upsert(
            WordEntity(
                word = normalized,
                language = language,
                frequency = (existing?.frequency ?: 0) + 1,
                isUserAdded = existing?.isUserAdded ?: true,
                lastUsedTimestamp = now,
            ),
        )

        if (previousWord != null) {
            val prevNormalized = previousWord.lowercase()
            val existingBigram = bigramDao.findExact(language, prevNormalized, normalized)
            bigramDao.upsert(
                BigramEntity(
                    previousWord = prevNormalized,
                    word = normalized,
                    language = language,
                    count = (existingBigram?.count ?: 0) + 1,
                ),
            )
        }
    }

    override suspend fun bigramRank(previousWord: String, word: String): Int =
        bigramDao.findExact(language, previousWord.lowercase(), word.lowercase())?.count ?: 0

    override suspend fun saveWord(word: String) {
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty()) return
        val existing = wordDao.findExact(language, normalized)
        wordDao.upsert(
            WordEntity(
                word = normalized,
                language = language,
                frequency = (existing?.frequency ?: 0) + SAVE_WORD_BOOST,
                isUserAdded = true,
                lastUsedTimestamp = clock(),
            ),
        )
    }

    override suspend fun deleteWord(word: String) {
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty()) return
        wordDao.delete(language, normalized)
    }

    private companion object {
        const val SAVE_WORD_BOOST = 50
    }
}
