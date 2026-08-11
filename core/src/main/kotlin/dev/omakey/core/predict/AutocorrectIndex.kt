package dev.omakey.core.predict

import dev.omakey.core.db.WordEntity

/**
 * In-memory typo correction, checked on every word-boundary keystroke (space/punctuation/enter)
 * — far too hot a path to round-trip SQLite per word, so the whole dictionary is loaded into a
 * plain map once at startup instead. Candidate generation follows the classic Norvig spelling-
 * corrector approach: generate every string one edit (delete/transpose/replace/insert) away from
 * the typed word, and check which of those are real dictionary words. That's a few hundred
 * cheap HashMap lookups per keystroke, not a database query — fast enough for the typing hot path.
 *
 * Deliberately edit-distance-1 only, not 2 — distance-2 candidate generation is quadratic in the
 * distance-1 candidate count (tens of thousands of strings for a 6-letter word) and would add
 * real latency to every space press. Distance-1 already covers the large majority of real-world
 * single-fat-finger typos (one missed/extra/wrong/swapped letter).
 */
class AutocorrectIndex {
    @Volatile private var frequencyByWord: Map<String, Int> = emptyMap()
    @Volatile private var correctionFloor: Int = 0

    fun load(words: List<WordEntity>) {
        val map = words.associate { it.word to it.frequency }
        frequencyByWord = map
        // Only correct into reasonably common words — otherwise a typed non-word that happens to
        // be one edit away from an obscure dictionary entry gets "corrected" into something the
        // user has never heard of, which is worse than not correcting at all. Top ~20% by
        // frequency rank is generous enough to cover everyday vocabulary without reaching for
        // rare entries.
        val sorted = map.values.sortedDescending()
        correctionFloor = sorted.getOrElse((sorted.size * 0.2).toInt()) { 0 }
    }

    /** Marks a word as known (e.g. just typed-and-accepted by the user) so it's never "corrected"
     * away in the future, even if it's a name/slang/word absent from the seeded dictionary. The
     * exact frequency value doesn't matter here — only presence in the map is checked. */
    fun learn(word: String) {
        val lower = word.lowercase()
        if (lower !in frequencyByWord) {
            frequencyByWord = frequencyByWord + (lower to 1)
        }
    }

    /** Returns a corrected (lowercase) word if [typed] is confidently a typo of a much more
     * common dictionary word, or null if it should be left alone — already a known word, too
     * short, contains non-letters, or no sufficiently common one-edit neighbor exists. */
    fun correct(typed: String): String? {
        val lower = typed.lowercase()
        if (lower.length < MIN_LENGTH || lower.length > MAX_LENGTH) return null
        if (!lower.all { it.isLetter() }) return null
        if (lower in frequencyByWord) return null // never "correct" an already-real word

        var best: String? = null
        var bestFrequency = -1
        for (candidate in edits1(lower)) {
            val freq = frequencyByWord[candidate] ?: continue
            if (freq > bestFrequency) {
                bestFrequency = freq
                best = candidate
            }
        }
        if (best == null || bestFrequency < correctionFloor) return null
        return best
    }

    private fun edits1(word: String): Set<String> {
        val splits = (0..word.length).map { word.substring(0, it) to word.substring(it) }
        val deletes = splits.filter { it.second.isNotEmpty() }.map { (l, r) -> l + r.substring(1) }
        val transposes = splits.filter { it.second.length > 1 }.map { (l, r) -> l + r[1] + r[0] + r.substring(2) }
        val replaces = splits.filter { it.second.isNotEmpty() }
            .flatMap { (l, r) -> ('a'..'z').map { c -> l + c + r.substring(1) } }
        val inserts = splits.flatMap { (l, r) -> ('a'..'z').map { c -> l + c + r } }
        return (deletes + transposes + replaces + inserts).toHashSet()
    }

    private companion object {
        const val MIN_LENGTH = 3
        const val MAX_LENGTH = 20
    }
}
