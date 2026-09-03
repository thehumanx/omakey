package dev.omakey.core.predict

/**
 * Seam for word/next-word suggestion. The shipping implementation is [NgramPredictionEngine], an
 * interpolated trigram model read straight out of a memory-mapped asset; a future on-device neural
 * model can implement this interface without any change to UI code that consumes it.
 */
interface PredictionEngine {
    /**
     * Best continuations of the context, best first.
     *
     * Takes **two** words of left context, not one: with only the immediately preceding word the
     * model can never reach its trigram tier, which is where most of its discriminating power
     * lives — "lot" is unremarkable after "whole" but decisive after "a whole".
     * [dev.omakey.app.keyboard.KeyboardViewModel] already tracks both.
     *
     * [currentPrefix] is what the user has typed of the word so far; empty asks for a pure
     * next-word prediction.
     */
    suspend fun suggestNext(
        beforePreviousWord: String?,
        previousWord: String?,
        currentPrefix: String,
        limit: Int = 3,
    ): List<String>

    suspend fun recordAcceptedWord(word: String, previousWord: String?)

    /** Explicitly adds/boosts a word the user chose to keep (e.g. via the swipe-up "save word"
     * gesture when no further suggestion exists to cycle to) — a stronger, one-shot signal than
     * the incremental bump from [recordAcceptedWord], so it ranks usefully right away. */
    suspend fun saveWord(word: String)

    /** Reverses [saveWord] — removes a word the user had previously saved via swipe-up, called
     * when they swipe up on it a second time ("unlearn"). Only ever called for words already
     * confirmed user-added (see [dev.omakey.core.predict.AutocorrectIndex.isUserAdded]); never
     * touches the bundled vocabulary. */
    suspend fun deleteWord(word: String)

    /**
     * `log P(word | previousWord)` from **observed** bigram evidence, or null when that pair was
     * never seen — which is deliberately distinct from "seen but unlikely". Callers use null to
     * mean "I have nothing to go on here, leave the existing order alone."
     *
     * Used for context-aware "did you mean" corrections of words that are themselves valid
     * dictionary entries — e.g. "thus" after a context where "this" is overwhelmingly more common.
     * Ordinary frequency-only typo correction cannot catch this class of real-word error at all;
     * only surrounding context can.
     *
     * Returns a log probability rather than a raw count. The count this replaced was, in the
     * shipping model, the n-gram's *alphabetical* position — so this ranking was ordering
     * candidates by spelling and presenting it as evidence.
     */
    suspend fun contextLogProbability(previousWord: String, word: String): Float?
}
