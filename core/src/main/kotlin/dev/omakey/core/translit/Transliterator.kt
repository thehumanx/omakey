package dev.omakey.core.translit

import dev.omakey.core.predict.lm.LanguageModel

/**
 * Nepali words for what was typed in Latin letters (AGENTS.md §66 Phase 9).
 *
 * Candidates come from the [TransliterationIndex]: words whose romanization has the same
 * [Romanization.skeleton] as the typing (a complete word), then words whose skeleton starts with it
 * (a completion, since the typing is often a word still in progress). Each is scored like
 * autocorrect scores a correction —
 *
 *     score = languageModelWeight · logP(word | context) − distanceWeight · distance(typed, word)
 *
 * — with distance measured between the typing and the word's own romanization in
 * [Romanization.light] form, so "timi" prefers तिमी (romanized "timii" → light "timi") over tamaa
 * or tim-anything. The rule-based [LiteralTransliterator] form is always included, so a name or a
 * word the model doesn't know can still be typed.
 */
class Transliterator(
    private val model: LanguageModel,
    private val index: TransliterationIndex,
    private val languageModelWeight: Float = LANGUAGE_MODEL_WEIGHT,
    private val distanceWeight: Float = DISTANCE_WEIGHT,
) {

    /** Previous words as model ids, most recent first. */
    data class Context(val previousId: Int = LanguageModel.NO_WORD, val beforePreviousId: Int = LanguageModel.NO_WORD)

    fun contextOf(previous: String?, beforePrevious: String?) = Context(
        previousId = previous?.let(model::indexOf) ?: LanguageModel.NO_WORD,
        beforePreviousId = beforePrevious?.let(model::indexOf) ?: LanguageModel.NO_WORD,
    )

    /** Up to [limit] Devanagari words for [latin], best first; the literal transliteration is
     * among them. Empty for input with no letters. */
    fun candidates(latin: String, context: Context = Context(), limit: Int = 6): List<String> {
        val typed = latin.lowercase().filter { it in 'a'..'z' }
        if (typed.isEmpty()) return emptyList()
        val key = Romanization.skeleton(typed)
        val typedLight = Romanization.light(typed)

        val scores = HashMap<Int, Float>()
        fun offer(entry: Int, completion: Boolean) {
            val id = index.wordId(entry)
            if (id in scores) return
            val romanLight = Romanization.light(Romanization.romanize(model.wordAt(id)))
            val distance = if (completion) {
                // Compare against as much of the word as has been typed; the rest is the completion.
                editDistance(typedLight, romanLight.take(typedLight.length + 1)).toFloat() + COMPLETION_PENALTY
            } else {
                editDistance(typedLight, romanLight).toFloat()
            }
            scores[id] = languageModelWeight * model.logProbability(id, context.previousId, context.beforePreviousId) -
                distanceWeight * distance
        }

        val exact = index.exactRange(key)
        for (entry in exact.take(MAX_EXACT)) offer(entry, completion = false)
        // Completions only once a few letters are in: after one or two, every word is a completion.
        if (key.length >= MIN_COMPLETION_KEY) {
            val prefix = index.prefixRange(key)
            var scanned = 0
            for (entry in prefix) {
                if (entry in exact) continue
                offer(entry, completion = true)
                if (++scanned >= MAX_COMPLETIONS) break
            }
        }

        val ranked = scores.entries.sortedByDescending { it.value }.map { model.wordAt(it.key) to it.value }.toMutableList()
        // Inflected forms: Nepali builds them from a stem and regular postpositions (किताब + हरू +
        // लाई), and most inflections are too rare to be in the vocabulary — 44 % of Aksharantar's
        // test words are not. So a known stem plus known suffixes is a candidate too, scored as
        // its stem with a penalty per suffix.
        for ((stemLatin, suffix) in suffixSplits(typed)) {
            val stemKey = Romanization.skeleton(stemLatin)
            val stemRange = index.exactRange(stemKey)
            if (stemRange.isEmpty()) continue
            val stemLight = Romanization.light(stemLatin)
            var best: Pair<String, Float>? = null
            for (entry in stemRange.take(MAX_STEM_CANDIDATES)) {
                val id = index.wordId(entry)
                val score = languageModelWeight * model.unigramLogProbability(id) -
                    distanceWeight * editDistance(stemLight, Romanization.light(Romanization.romanize(model.wordAt(id)))) -
                    SUFFIX_PENALTY * suffix.parts
                if (best == null || score > best.second) best = model.wordAt(id) to score
            }
            best?.let { (stem, score) -> ranked += (stem + suffix.devanagari) to score }
        }
        ranked.sortByDescending { it.second }

        val literal = LiteralTransliterator.transliterate(typed)
        val results = LinkedHashSet<String>()
        for ((word, _) in ranked) {
            if (results.size >= limit) break
            results += word
        }
        val output = results.toMutableList()
        // The literal form is the fallback, not a rival to words the model knows: it takes a free
        // slot, or the last of three or more, and never the first.
        if (literal !in output) {
            when {
                output.size < limit -> output += literal
                limit >= 3 -> output[output.lastIndex] = literal
            }
        }
        return output
    }

    private class Suffix(val devanagari: String, val parts: Int)

    /** Every way [typed] ends in a postposition (optionally after the plural हरू), with the stem
     * that remains — longest suffix first. */
    private fun suffixSplits(typed: String): List<Pair<String, Suffix>> {
        val splits = ArrayList<Pair<String, Suffix>>()
        for ((caseLatin, caseDevanagari) in CASE_SUFFIXES + ("" to "")) {
            if (!typed.endsWith(caseLatin)) continue
            val beforeCase = typed.dropLast(caseLatin.length)
            for (plural in listOf(true, false)) {
                if (plural && !beforeCase.endsWith(PLURAL.first)) continue
                if (!plural && caseLatin.isEmpty()) continue
                val stem = if (plural) beforeCase.dropLast(PLURAL.first.length) else beforeCase
                if (stem.length < MIN_STEM) continue
                val devanagari = (if (plural) PLURAL.second else "") + caseDevanagari
                splits += stem to Suffix(devanagari, (if (plural) 1 else 0) + (if (caseLatin.isEmpty()) 0 else 1))
            }
        }
        return splits
    }

    companion object {
        /** The plural marker, written joined to its noun. */
        private val PLURAL = "haru" to "हरू"

        /** Postpositions written joined to the word before, longest first so "bata" isn't read as
         * "ta". Latin forms as people type them; Devanagari as the standard spelling. */
        private val CASE_SUFFIXES = listOf(
            "dekhi" to "देखि", "sanga" to "सँग", "samma" to "सम्म", "bata" to "बाट", "baata" to "बाट",
            "laai" to "लाई", "lai" to "लाई", "ko" to "को", "ka" to "का", "ki" to "की", "le" to "ले", "ma" to "मा",
        )
        private const val MIN_STEM = 2
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
