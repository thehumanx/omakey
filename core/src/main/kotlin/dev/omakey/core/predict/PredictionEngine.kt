package dev.omakey.core.predict

/**
 * Seam for word/next-word suggestion. v1 implementation is frequency/bigram based and deliberately
 * simple; a future stronger (possibly on-device neural) model can implement this interface without
 * any change to UI code that consumes it.
 */
interface PredictionEngine {
    suspend fun suggestNext(previousWord: String?, currentPrefix: String, limit: Int = 3): List<String>
    suspend fun recordAcceptedWord(word: String, previousWord: String?)

    /** Explicitly adds/boosts a word the user chose to keep (e.g. via the swipe-up "save word"
     * gesture when no further suggestion exists to cycle to) — a stronger, one-shot signal than
     * the incremental +1 from [recordAcceptedWord], so it ranks usefully right away. */
    suspend fun saveWord(word: String)

    /** Bigram rank for a specific (previousWord, word) pair — higher means more commonly seen as
     * a continuation of [previousWord]; 0 if the pair has never been seen. Used for context-aware
     * "did you mean" corrections of words that are themselves valid dictionary entries (so
     * [dev.omakey.core.predict.AutocorrectIndex.correct] won't touch them — that guardrail exists
     * for good reason, most of the time) but statistically implausible given what precedes them —
     * e.g. "thus" after a context where "this" is overwhelmingly more common. Ordinary frequency-
     * only typo correction can't catch this class of "real-word error" at all; only surrounding
     * context can. */
    suspend fun bigramRank(previousWord: String, word: String): Int
}
