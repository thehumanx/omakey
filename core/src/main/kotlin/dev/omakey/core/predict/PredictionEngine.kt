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
}
