package dev.omakey.core.predict

/**
 * A [PredictionEngine] handle that exists before the engine behind it does.
 *
 * The language model is memory-mapped on a background coroutine so `onCreateInputView` is never
 * blocked — the keyboard must be typeable the instant it appears, even on the very first launch.
 * But the view (and the `KeyboardViewModel` it constructs) can be created before that coroutine
 * finishes, so something has to be passed in *now*. This is that something: a stable reference
 * handed to the view model at construction, with [delegate] filled in once loading completes.
 *
 * Until then every call is a no-op returning no suggestions, which is the correct degraded
 * behaviour — an empty suggestion strip for a fraction of a second. The alternative, a `lateinit`
 * assigned from the coroutine, is an `UninitializedPropertyAccessException` on the first keystroke
 * of a cold start; and blocking the main thread on the load instead would trade a rare crash for a
 * guaranteed stutter every time the keyboard opens.
 *
 * Writes to [delegate] are `@Volatile` because it is set on a background coroutine and read on the
 * main thread on every keystroke.
 */
class DeferredPredictionEngine : PredictionEngine {

    @Volatile
    var delegate: PredictionEngine? = null

    val isReady: Boolean get() = delegate != null

    override suspend fun suggestNext(
        beforePreviousWord: String?,
        previousWord: String?,
        currentPrefix: String,
        limit: Int,
    ): List<String> =
        delegate?.suggestNext(beforePreviousWord, previousWord, currentPrefix, limit).orEmpty()

    override suspend fun recordAcceptedWord(word: String, previousWord: String?) {
        delegate?.recordAcceptedWord(word, previousWord)
    }

    override suspend fun saveWord(word: String) {
        delegate?.saveWord(word)
    }

    override suspend fun deleteWord(word: String) {
        delegate?.deleteWord(word)
    }

    override suspend fun contextLogProbability(previousWord: String, word: String): Float? =
        delegate?.contextLogProbability(previousWord, word)
}
