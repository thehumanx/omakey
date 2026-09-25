package dev.omakey.core.translit

/**
 * Rule-based Latin → Devanagari for Nepali: the candidate that is always there, so names and words
 * the model has never seen can still be typed (AGENTS.md §66 Phase 9).
 *
 * Greedy longest match over a small phonetic table. A consonant followed by a vowel takes that
 * vowel's sign (and "a" none — it is inherent); two consonants in a row are joined with a virama; a
 * word-final consonant keeps its inherent vowel unwritten, which is how Nepali spells it (नेपाल).
 * Deliberately simple — the lexicon handles every word it knows, including the ones this gets
 * wrong (it writes तिमि for "timi"; the lexicon offers तिमी).
 */
object LiteralTransliterator {

    private val consonants = listOf(
        "ksh" to "क्ष", "chh" to "छ", "gy" to "ज्ञ",
        "kh" to "ख", "gh" to "घ", "ch" to "च", "jh" to "झ", "th" to "थ", "dh" to "ध",
        "ph" to "फ", "bh" to "भ", "sh" to "श",
        "k" to "क", "g" to "ग", "c" to "च", "x" to "छ", "j" to "ज", "t" to "त", "d" to "द",
        "n" to "न", "p" to "प", "f" to "फ", "b" to "ब", "m" to "म", "y" to "य", "r" to "र",
        "l" to "ल", "w" to "व", "v" to "व", "s" to "स", "h" to "ह", "z" to "ज", "q" to "क",
    )

    /** Latin vowel → (independent letter, vowel sign). "a" has no sign: consonants carry it. */
    private val vowels = listOf(
        "aa" to ("आ" to "ा"), "ai" to ("ऐ" to "ै"), "au" to ("औ" to "ौ"),
        "ee" to ("ई" to "ी"), "ii" to ("ई" to "ी"), "oo" to ("ऊ" to "ू"), "uu" to ("ऊ" to "ू"),
        "a" to ("अ" to ""), "i" to ("इ" to "ि"), "u" to ("उ" to "ु"), "e" to ("ए" to "े"), "o" to ("ओ" to "ो"),
    )

    fun transliterate(latin: String): String {
        val input = latin.lowercase()
        val out = StringBuilder()
        var afterConsonant = false
        var syllables = 0
        var i = 0
        outer@ while (i < input.length) {
            for ((roman, devanagari) in consonants) {
                if (input.startsWith(roman, i)) {
                    if (afterConsonant) out.append('्')
                    out.append(devanagari)
                    afterConsonant = true
                    i += roman.length
                    continue@outer
                }
            }
            for ((roman, forms) in vowels) {
                if (input.startsWith(roman, i)) {
                    // A word-final "a" after a consonant is usually long in a word of two or more
                    // syllables ("aama" आमा, "khana" खाना) and the inherent vowel in one ("ra" र,
                    // "chha" छ). A heuristic; the lexicon has the last word on words it knows.
                    val finalLongA = roman == "a" && afterConsonant && i + 1 == input.length && syllables >= 1
                    out.append(
                        when {
                            finalLongA -> "ा"
                            afterConsonant -> forms.second
                            else -> forms.first
                        },
                    )
                    syllables++
                    afterConsonant = false
                    i += roman.length
                    continue@outer
                }
            }
            out.append(input[i]) // digits, apostrophes: passed through
            afterConsonant = false
            i++
        }
        return out.toString()
    }
}
