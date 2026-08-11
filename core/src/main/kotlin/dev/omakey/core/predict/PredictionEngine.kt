package dev.omakey.core.predict

/**
 * Seam for word/next-word suggestion. v1 implementation is frequency/bigram based and deliberately
 * simple; a future stronger (possibly on-device neural) model can implement this interface without
 * any change to UI code that consumes it.
 */
interface PredictionEngine {
    suspend fun suggestNext(previousWord: String?, currentPrefix: String, limit: Int = 3): List<String>
    suspend fun recordAcceptedWord(word: String, previousWord: String?)
}
