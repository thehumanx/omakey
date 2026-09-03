package dev.omakey.core.predict

import dev.omakey.core.db.WordDao
import dev.omakey.core.db.WordEntity
import dev.omakey.core.predict.lm.LanguageModel

/**
 * Prediction over the bundled [LanguageModel], with the user's own saved words layered on top.
 *
 * Every candidate — whatever tier it was *found* in — is scored by the same
 * [LanguageModel.logProbability] backoff chain, so a trigram hit and a prefix-scan hit are directly
 * comparable and one ranking decides the final order. The alternative (concatenating per-tier
 * result lists) is what the previous engine did, and it meant a weak trigram continuation always
 * beat a strong bigram one purely because of the order the lists were appended in.
 *
 * No I/O happens here. The previous implementation issued two Room queries per keystroke — one of
 * them a `LIKE 'prefix%'` scan — plus a point query per candidate when re-ranking by context, all
 * inside a budget where a keypress should produce visible feedback in about 20ms.
 */
class NgramPredictionEngine(
    private val model: LanguageModel,
    private val wordDao: WordDao,
    private val personal: PersonalLanguageModel = PersonalLanguageModel(),
) : PredictionEngine {

    override suspend fun suggestNext(
        beforePreviousWord: String?,
        previousWord: String?,
        currentPrefix: String,
        limit: Int,
    ): List<String> {
        if (limit <= 0) return emptyList()
        val prefix = currentPrefix.lowercase()
        val previousId = previousWord?.let { model.indexOf(it.lowercase()) } ?: LanguageModel.NO_WORD
        val beforePreviousId =
            beforePreviousWord?.let { model.indexOf(it.lowercase()) } ?: LanguageModel.NO_WORD

        val best = TopCandidates(limit)

        // Strongest context first. These rows are already probability-ordered, so the prefix filter
        // is the only work; scoring still goes through the common backoff chain below so that a
        // candidate found here is comparable with one found by the prefix scan.
        for (i in model.trigramRow(beforePreviousId, previousId)) {
            val id = model.trigramWordId(i)
            if (model.startsWith(id, prefix)) best.offer(id, model.logProbability(id, previousId, beforePreviousId))
        }
        for (i in model.bigramRow(previousId)) {
            val id = model.bigramWordId(i)
            if (model.startsWith(id, prefix)) best.offer(id, model.logProbability(id, previousId, beforePreviousId))
        }

        // Completion: every vocabulary word sharing the prefix is a candidate. Contiguous by
        // construction (the vocabulary is lexicographically ordered), so this is a bounded scan
        // over an id range, not a search.
        if (prefix.isNotEmpty()) {
            for (id in model.prefixRange(prefix)) {
                best.offer(
                    id,
                    // Interpolated against what this user actually types, so a name or piece of
                    // jargon they use often outranks corpus words of similar rarity.
                    personal.adjustById(id, model.logProbability(id, previousId, beforePreviousId)),
                )
            }
        } else if (best.isEmpty()) {
            // No prefix and no context the model recognises — the only honest answer left is
            // "words that are common in general".
            for (rank in 0 until minOf(model.topUnigramSize, TOP_UNIGRAM_FALLBACK)) {
                val id = model.topUnigramId(rank)
                best.offer(id, model.unigramLogProbability(id))
            }
        }

        val results = LinkedHashSet<String>()
        // User-saved words are offered ahead of the bundled vocabulary when they match: the user
        // went out of their way to teach the keyboard this word, which is a far stronger signal
        // than any corpus statistic about a word they have never typed.
        personal.matching(prefix, limit).forEach { results += it }
        best.words(model).forEach { if (results.size < limit) results += it }
        return results.take(limit).toList()
    }

    override suspend fun contextLogProbability(previousWord: String, word: String): Float? {
        val previousId = model.indexOf(previousWord.lowercase())
        val wordId = model.indexOf(word.lowercase())
        if (previousId == LanguageModel.NO_WORD || wordId == LanguageModel.NO_WORD) return null
        for (i in model.bigramRow(previousId)) {
            if (model.bigramWordId(i) == wordId) return model.bigramLogProbability(i)
        }
        return null
    }

    /** Ordinary typing. The personal model decides what is worth keeping — common corpus words are
     * declined, since re-counting "the" teaches nothing the corpus doesn't already say better and
     * would only consume the model's capacity. Callers gate this on the incognito/learning
     * preference before it gets here. */
    override suspend fun recordAcceptedWord(word: String, previousWord: String?) {
        persist(word, explicit = false)
    }

    override suspend fun saveWord(word: String) {
        persist(word, explicit = true)
    }

    override suspend fun deleteWord(word: String) {
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty()) return
        personal.forget(normalized)
        wordDao.delete(normalized)
    }

    private suspend fun persist(word: String, explicit: Boolean) {
        val normalized = word.trim().lowercase()
        if (normalized.isEmpty()) return
        val entry = personal.record(normalized, explicit) ?: return
        wordDao.upsert(
            WordEntity(
                word = entry.word,
                frequency = (entry.count * WordEntity.COUNT_SCALE).toInt(),
                isUserAdded = true,
                lastUsedTimestamp = entry.lastUsed,
                explicit = entry.explicit,
            ),
        )
    }

    /** Fixed-size "keep the best N" over a stream of scored ids. Insertion-ordered rather than a
     * heap because N is the suggestion-strip limit — six — where the constant factors of a heap
     * cost more than the linear shifts it saves. */
    private class TopCandidates(private val limit: Int) {
        private val ids = IntArray(limit)
        private val scores = FloatArray(limit)
        private var size = 0

        fun isEmpty() = size == 0

        fun offer(id: Int, score: Float) {
            for (i in 0 until size) if (ids[i] == id) return
            if (size == limit && score <= scores[size - 1]) return
            var position = size.coerceAtMost(limit - 1)
            while (position > 0 && scores[position - 1] < score) {
                ids[position] = ids[position - 1]
                scores[position] = scores[position - 1]
                position--
            }
            ids[position] = id
            scores[position] = score
            if (size < limit) size++
        }

        fun words(model: LanguageModel): List<String> = (0 until size).map { model.wordAt(ids[it]) }
    }

    private companion object {
        // How much an explicit save is worth now lives in PersonalLanguageModel, where it is
        // expressed in that model's own units rather than as a magic number added to a raw count.
        const val TOP_UNIGRAM_FALLBACK = 64
    }
}
