package dev.omakey.core.translit

import dev.omakey.core.predict.lm.LanguageModel

/**
 * Words for what was typed in Latin letters — Nepali (AGENTS.md §66 Phase 9) or Russian (§70); the
 * [scheme] holds everything language-specific.
 *
 * Candidates come from the [TransliterationIndex]: words whose romanization has the same
 * [TransliterationScheme.skeleton] as the typing (a complete word), then words whose skeleton starts with it
 * (a completion, since the typing is often a word still in progress). Each is scored like
 * autocorrect scores a correction —
 *
 *     score = languageModelWeight · logP(word | context) − distanceWeight · distance(typed, word)
 *
 * — with distance measured between the typing and the word's own romanization in
 * [TransliterationScheme.light] form, so "timi" prefers तिमी (romanized "timii" → light "timi") over tamaa
 * or tim-anything. The rule-based [TransliterationScheme.literal] form is always included, so a name or a
 * word the model doesn't know can still be typed.
 */
class Transliterator(
    private val model: LanguageModel,
    private val index: TransliterationIndex,
    private val scheme: TransliterationScheme,
    private val languageModelWeight: Float = LANGUAGE_MODEL_WEIGHT,
    private val distanceWeight: Float = DISTANCE_WEIGHT,
) {

    /** Previous words as model ids, most recent first. */
    data class Context(val previousId: Int = LanguageModel.NO_WORD, val beforePreviousId: Int = LanguageModel.NO_WORD)

    fun contextOf(previous: String?, beforePrevious: String?) = Context(
        previousId = previous?.let(model::indexOf) ?: LanguageModel.NO_WORD,
        beforePreviousId = beforePrevious?.let(model::indexOf) ?: LanguageModel.NO_WORD,
    )

    /** A ranked word: [distance] is how far the typing is from it, for deciding whether it may
     * stand in for the typing outright. */
    private class Ranked(val word: String, val score: Float, val distance: Float)

    /**
     * Up to [limit] words for [latin], best first; the literal transliteration is among them.
     * Empty for input with no letters.
     *
     * The first one is what the field shows while typing and what space commits, so it has to be
     * the typing itself, not a guess at a longer or different word: a vocabulary word goes first
     * only if it is a whole-word match within [CONFIDENT_DISTANCE] of what was typed. Otherwise
     * the literal form does — "eh" is एह, not the completion एहिमे the field used to fill in.
     */
    fun candidates(latin: String, context: Context = Context(), limit: Int = 6): List<String> {
        val typed = latin.lowercase().filter { it in 'a'..'z' }
        if (typed.isEmpty()) return emptyList()
        val key = scheme.skeleton(typed)

        val ranked = HashMap<Int, Ranked>()
        fun offer(entry: Int, completion: Boolean) {
            val id = index.wordId(entry)
            if (id in ranked) return
            val word = model.wordAt(id)
            val distance = scheme.distance(typed, scheme.romanize(word), prefix = completion) +
                if (completion) COMPLETION_PENALTY else 0f
            val score = languageModelWeight * model.logProbability(id, context.previousId, context.beforePreviousId) -
                distanceWeight * distance
            // A completion never counts as a close match: it is a different, longer word.
            ranked[id] = Ranked(word, score, if (completion) Float.MAX_VALUE else distance)
        }

        val keys = listOf(key) + scheme.alternativeKeys(typed).filter { it != key }
        val exactRanges = keys.map(index::exactRange)
        for (range in exactRanges) for (entry in range.take(MAX_EXACT)) offer(entry, completion = false)
        // Completions only once a few letters are in: after one or two, every word is a completion.
        if (key.length >= MIN_COMPLETION_KEY) {
            val prefix = index.prefixRange(key)
            var scanned = 0
            for (entry in prefix) {
                if (exactRanges.any { entry in it }) continue
                offer(entry, completion = true)
                if (++scanned >= MAX_COMPLETIONS) break
            }
        }

        val all = ranked.values.toMutableList()
        // Inflected forms the vocabulary is too small to list (Nepali's joined postpositions): a known
        // stem plus known suffixes is a candidate too, scored as its stem with a penalty per suffix.
        for (inflection in scheme.inflections(typed)) {
            val stemRange = index.exactRange(scheme.skeleton(inflection.stemLatin))
            if (stemRange.isEmpty()) continue
            var best: Ranked? = null
            for (entry in stemRange.take(MAX_STEM_CANDIDATES)) {
                val id = index.wordId(entry)
                val stemDistance = scheme.distance(inflection.stemLatin, scheme.romanize(model.wordAt(id)), prefix = false)
                val score = languageModelWeight * model.unigramLogProbability(id) -
                    distanceWeight * stemDistance - SUFFIX_PENALTY * inflection.parts
                if (best == null || score > best.score) best = Ranked(model.wordAt(id) + inflection.suffix, score, stemDistance)
            }
            best?.let(all::add)
        }
        all.sortByDescending { it.score }

        val literal = scheme.literal(typed)
        val output = ArrayList<String>(limit)
        scheme.shorthand(typed)?.let(output::add)
        val confident = all.firstOrNull()?.let { it.distance <= CONFIDENT_DISTANCE } == true
        if (!confident && literal !in output) output += literal
        for (candidate in all) {
            if (output.size >= limit) break
            if (candidate.word !in output) output += candidate.word
        }
        // The literal form is the fallback, not a rival to a word that matches: it takes a free slot,
        // or the last one when what is there is not itself a close match.
        if (literal !in output) {
            val last = all.firstOrNull { it.word == output.lastOrNull() }
            when {
                output.size < limit -> output += literal
                output.size >= 2 && (last == null || last.distance > CONFIDENT_DISTANCE) -> output[output.lastIndex] = literal
            }
        }
        return output
    }

    companion object {
        private const val MAX_STEM_CANDIDATES = 50
        const val SUFFIX_PENALTY = 1.0f

        /**
         * Swept on Aksharantar's Nepali test split (2026-09-25): flat within about a point across
         * λ ∈ {0.15, 0.35, 0.7} × distance ∈ {0.5, 1, 2}. Aksharantar scores isolated words, which
         * favours the distance term by construction; real typing has context, which is the language
         * model's job — so λ stays at autocorrect's 0.35 and distance takes the better of its flat
         * values.
         */
        const val LANGUAGE_MODEL_WEIGHT = 0.35f
        const val DISTANCE_WEIGHT = 2.0f
        const val COMPLETION_PENALTY = 1.5f
        private const val MAX_EXACT = 400
        private const val MAX_COMPLETIONS = 600
        private const val MIN_COMPLETION_KEY = 2

        /** How close (in [TransliterationScheme.distance] units) a vocabulary word must be to the
         * typing to be shown in its place: any number of the cheap differences (a dropped "h", a
         * short vowel, a missing nasal), but not a typo's worth or a typed "h" the word lacks.
         * At 1.5, "haha" became हाँ ("yes"); at 1.0 it stays हहा with हाँ offered second. */
        const val CONFIDENT_DISTANCE = 1.0f

        internal fun editDistance(a: String, b: String): Int {
            val previous = IntArray(b.length + 1) { it }
            val current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                }
                current.copyInto(previous)
            }
            return previous[b.length]
        }
    }
}
