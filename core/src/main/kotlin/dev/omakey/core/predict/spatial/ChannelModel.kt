package dev.omakey.core.predict.spatial

/**
 * `log P(typed | intended)` — how plausible it is that someone aiming for one word produced
 * another. The "SM" half of the noisy-channel decoding that mainstream keyboards use:
 * `ŵ = argmax SM(w | input) + LM(w | context)`.
 *
 * Returned as a **cost** (a non-negative penalty; higher means less plausible), which the caller
 * negates and adds to the language-model score.
 *
 * The costs below come from Gboard's spatial model, whose per-tap term is
 * `min((Δx² + Δy²) / 2σ², substitutionCost)` with Δ normalised by key size, plus fixed costs for
 * insertions, deletions and transpositions. The `min` matters: past a certain distance one wrong
 * key is no more informative than another, and without the cap a single far-away keypress would
 * dominate the whole score and veto an otherwise excellent candidate.
 *
 * What this replaces is plain Damerau-Levenshtein, where every edit costs exactly 1. Under that
 * model a substitution between neighbouring keys is indistinguishable from one between keys at
 * opposite corners, and candidates could only be ordered by `(edit distance, then raw frequency)`
 * — a lexicographic comparison in which a distance-1 match to a marginal word always beats a
 * distance-2 match to an overwhelmingly likely one.
 */
class ChannelModel(
    private val sigma: Float = SIGMA,
    private val substitutionCap: Float = SUBSTITUTION_CAP,
    private val insertionCost: Float = INSERTION_COST,
    private val deletionCost: Float = DELETION_COST,
    private val transpositionCost: Float = TRANSPOSITION_COST,
    /** Weight on the language-model term relative to the channel term. Both are in log space, so
     * this is the exchange rate between "this doesn't look like what they typed" and "this isn't a
     * word people write". A constructor parameter rather than a constant so the tuning sweep in
     * `EngineTuningTest` can search over it. */
    val languageModelWeight: Float = LANGUAGE_MODEL_WEIGHT,
) {

    private val twoSigmaSquared = 2f * sigma * sigma

    /** Cost of having typed [typed] while aiming for [intended], assuming the tap landed at the
     * centre of [typed]'s key. The degraded form of [substitutionAt], used when no touch data is
     * available — a hardware keyboard, a pasted word, an accent chosen from a long-press popup, or
     * a word being corrected retroactively long after it was typed. */
    fun substitution(typed: Char, intended: Char): Float {
        if (typed == intended) return 0f
        return minOf(KeyboardGeometry.squaredDistance(typed, intended) / twoSigmaSquared, substitutionCap)
    }

    /**
     * Cost of the tap at [index] having been meant for [intended], using where the finger actually
     * landed.
     *
     * This is the form the model is built for. Resolving a tap to a key first and then asking "how
     * far apart are those two keys" discards the evidence that matters: a tap sitting on the
     * boundary between `k` and `l` is nearly free to read as either, while one dead centre on `k`
     * is strong evidence against `l`. Both collapse to the same number once the tap has been
     * rounded to a key.
     *
     * Falls back to [substitution] when [taps] is null or doesn't cover [index].
     */
    fun substitutionAt(typed: Char, intended: Char, taps: TouchTrace.Taps?, index: Int): Float {
        if (typed == intended) return 0f
        if (taps == null || index < 0 || index >= taps.size) return substitution(typed, intended)
        val squaredDistance = KeyboardGeometry.squaredDistanceFromPoint(taps.x(index), taps.y(index), intended)
        return minOf(squaredDistance / twoSigmaSquared, substitutionCap)
    }

    /** A character present in the intended word that never got typed. */
    fun deletion(): Float = deletionCost

    /** A character typed that isn't in the intended word. */
    fun insertion(): Float = insertionCost

    /**
     * Two adjacent characters typed in the wrong order. Deliberately cheaper than the two
     * substitutions it would otherwise be scored as: transposition is one of the most common
     * typing errors there is (both letters were correct, only the timing was wrong), and pricing
     * "teh" as two independent substitution errors would put it level with genuinely unrelated
     * words.
     */
    fun transposition(): Float = transpositionCost

    companion object {
        /** Spread of the Gaussian over key distance, in key widths. At 0.85, landing on an
         * immediate neighbour (distance 1) costs ~1.0 while the cap is [SUBSTITUTION_CAP] — so a
         * near-miss is roughly three and a half times more forgivable than a wild one. */
        const val SIGMA = 0.7f

        /** Ceiling on one substitution. Beyond this the model stops distinguishing degrees of
         * wrongness, which keeps a single bad character from vetoing an otherwise strong word. */
        const val SUBSTITUTION_CAP = 3.5f

        const val INSERTION_COST = 3.0f
        const val DELETION_COST = 3.0f
        const val TRANSPOSITION_COST = 1.8f

        /**
         * Default exchange rate between the channel and language terms. Raising it makes the
         * engine trust "is this a likely word here" over "does this look like what was typed".
         *
         * The sweep in `EngineTuningTest` is nearly flat across 0.25–1.0 (net benefit varies by
         * under 0.01), so the aggregate does not choose a value here. The behavioural cases in
         * `AutocorrectIndexTest` do, and they are decisive: at 0.75 the language term is strong
         * enough for a much commoner word to beat the obviously intended one — "helko" corrected
         * to "help" rather than "hello", and "wierd" to "were" rather than "weird", purely on
         * frequency. Picking within a flat region using cases the aggregate is too coarse to see
         * is not overfitting; it is using the evidence that actually discriminates.
         */
        const val LANGUAGE_MODEL_WEIGHT = 0.35f
    }
}
