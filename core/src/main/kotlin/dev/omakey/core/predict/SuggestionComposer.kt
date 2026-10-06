package dev.omakey.core.predict

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decides *what* the suggestion strip should contain. Deliberately does not know what a strip is.
 *
 * Split out of `KeyboardViewModel`, which mixed three separate concerns in one function: choosing
 * which of the three suggestion situations applies, computing the candidates for it, and writing
 * the result into UI state. Only the middle one is interesting, and it was the one that could not
 * be tested — the view model needs a `Context` for every preference dependency, while this needs
 * an [AutocorrectIndex] and a [PredictionEngine], both of which a plain JVM test can build.
 *
 * The view model keeps the parts that genuinely belong to it: cancelling the previous keystroke's
 * in-flight query, deciding which situation applies, tracking the active correction, and applying
 * the result.
 *
 * ## The three situations
 *
 * They are mutually exclusive and ordered, which is why they are three functions rather than one
 * with flags — the caller has already decided which one it is in, and passing the wrong context is
 * the mistake worth making impossible.
 */
class SuggestionComposer(
    private val autocorrectIndex: AutocorrectIndex,
    private val predictionEngine: PredictionEngine,
    private val limit: Int = DEFAULT_LIMIT,
) {

    /**
     * @property words what to show, best first.
     * @property fromCorrection whether `words[0]` fixes an existing word rather than predicting a
     *   new one. The strip quotes corrections; more importantly, accepting one *replaces* text
     *   while accepting a prediction *appends* it, so getting this wrong edits the wrong thing.
     */
    data class Suggestions(val words: List<String>, val fromCorrection: Boolean) {
        val isEmpty: Boolean get() = words.isEmpty()

        companion object {
            val None = Suggestions(emptyList(), fromCorrection = false)
        }
    }

    /**
     * A word is being typed right now: corrections and completions for [prefix], merged.
     *
     * Alternatives come first and predictions fill the rest, minus anything the alternatives
     * already offered — case-insensitively, since the two sources disagree about capitalisation and
     * the same word appearing twice in a six-slot strip is a wasted slot.
     *
     * The Damerau-Levenshtein scan runs on [Dispatchers.Default] because it is the expensive part
     * of a keystroke. That decision lives here, with the work, rather than at the call site.
     */
    suspend fun forWordInProgress(
        prefix: String,
        previousWord: String?,
        beforePreviousWord: String?,
    ): Suggestions {
        val context = autocorrectIndex.contextOf(previousWord, beforePreviousWord)
        val alternatives = withContext(Dispatchers.Default) { alternatives(prefix, context) }
        val predicted = predictionEngine.suggestNext(
            beforePreviousWord = beforePreviousWord,
            previousWord = previousWord,
            currentPrefix = prefix,
            limit = limit,
        )
        val merged = (alternatives + predicted.filterNot { p -> alternatives.any { it.equals(p, ignoreCase = true) } })
            .take(limit)
        return Suggestions(merged, fromCorrection = alternatives.isNotEmpty())
    }

    /**
     * A word was just finished and could still be corrected in place.
     *
     * [beforePreviousWord] is the word before [word], and that is the whole subtlety: the word under
     * correction must not appear in its own context, or scoring is biased toward candidates that
     * plausibly follow themselves. Only one word of left context is available in this direction,
     * because nothing earlier is tracked.
     *
     * Returns [Suggestions.None] when there is nothing worth offering, which the caller should read
     * as "fall through to plain prediction" rather than "show an empty strip".
     */
    suspend fun forFinishedWord(word: String, beforePreviousWord: String?): Suggestions {
        val context = autocorrectIndex.contextOf(beforePreviousWord, null)
        val alternatives = withContext(Dispatchers.Default) { alternatives(word, context) }
        return if (alternatives.isEmpty()) Suggestions.None else Suggestions(alternatives, fromCorrection = true)
    }

    /**
     * A word autocorrect just replaced silently: [corrected] is on screen, [typed] is what the
     * user's fingers produced. Offers [corrected] first — it is what is applied — then the other
     * readings of [typed], and [typed] itself last, so the strip shows the word that was replaced
     * and a tap gets it back.
     *
     * Alternatives are of [typed], not [corrected]: the user's input is the evidence, and a
     * neighbour of the engine's guess is a guess about a guess. This is the Fleksy behaviour of
     * swiping through readings of what was typed after the keyboard has already picked one;
     * before it, the list was [forFinishedWord] of the correction, which never contained the
     * typed word, so no swipe could undo an autocorrect.
     */
    suspend fun forAutocorrectedWord(typed: String, corrected: String, beforePreviousWord: String?): Suggestions {
        val context = autocorrectIndex.contextOf(beforePreviousWord, null)
        val others = withContext(Dispatchers.Default) { alternatives(typed, context) }
            .filterNot { it.equals(corrected, ignoreCase = true) || it.equals(typed, ignoreCase = true) }
        val words = listOf(corrected) + others.take((limit - 2).coerceAtLeast(0)) + typed
        return Suggestions(words.take(limit.coerceAtLeast(2)), fromCorrection = true)
    }

    /** Nothing to correct: what word tends to come next. Never a correction, so accepting one of
     * these types a fresh word instead of replacing anything. */
    suspend fun nextWord(previousWord: String?, beforePreviousWord: String?): Suggestions {
        val predicted = predictionEngine.suggestNext(
            beforePreviousWord = beforePreviousWord,
            previousWord = previousWord,
            currentPrefix = "",
            limit = limit,
        )
        return Suggestions(predicted, fromCorrection = false)
    }

    /**
     * [AutocorrectIndex.alternatives] for [word], with the user's capitalisation restored.
     *
     * Two results are left exactly as the index produced them: a curated contraction ("I'm") is
     * already correctly cased, and a two-word split reads fine lowercase — neither wants
     * [matchCase]'s single-word rules applied on top.
     */
    fun alternatives(word: String, context: AutocorrectIndex.Context): List<String> {
        val contraction = autocorrectIndex.contractionFor(word)
        return autocorrectIndex.alternatives(word, limit, context).map { alt ->
            when {
                alt == contraction -> alt
                alt.contains(' ') -> alt
                else -> matchCase(word, alt)
            }
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 6
    }
}
