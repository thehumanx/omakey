package dev.omakey.core.predict

/**
 * Restores [typed]'s capitalisation onto a lowercase correction.
 *
 * The candidate lists are lowercase throughout, because that is what the language model keys on —
 * so without this, correcting "Hwllo" would offer "hello", and correcting "HWLLO" would offer
 * "hello" too. Three cases, in order: all-caps stays all-caps, a leading capital stays capitalised,
 * anything else is left alone.
 *
 * Total by construction: an empty [typed] returns the correction unchanged rather than throwing on
 * `first()`. Nothing currently calls it that way — the candidate list for an empty word is empty,
 * so the map never runs — but that is a property of the caller, and a function that is only safe
 * because of where it happens to be called from is one refactor away from not being.
 */
fun matchCase(typed: String, correctedLower: String): String = when {
    typed.isEmpty() -> correctedLower
    typed.all { it.isUpperCase() } -> correctedLower.uppercase()
    typed.first().isUpperCase() -> correctedLower.replaceFirstChar { it.uppercase() }
    else -> correctedLower
}

/**
 * Splits a correction that fixes a missing space, e.g. "thisbis" → "this is", into its two words.
 *
 * Null for an ordinary single-word replacement, which is every other case. A space at either end is
 * deliberately *not* a split: one half would be empty, and committing an empty word as a word
 * boundary corrupts the typing-order bookkeeping downstream.
 *
 * Only the first space is considered. `AutocorrectIndex.alternatives` never produces a three-word
 * result, and treating one as a two-way split would be a guess about which boundary mattered.
 */
fun splitCorrection(replacement: String): Pair<String, String>? {
    val spaceIndex = replacement.indexOf(' ')
    if (spaceIndex <= 0 || spaceIndex == replacement.length - 1) return null
    return replacement.substring(0, spaceIndex) to replacement.substring(spaceIndex + 1)
}
