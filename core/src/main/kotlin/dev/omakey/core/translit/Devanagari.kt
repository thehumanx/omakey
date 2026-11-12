package dev.omakey.core.translit

/**
 * Whether a vocabulary entry is a word someone could mean, written the way Devanagari is written.
 *
 * The Nepali model's vocabulary came from Wikipedia text split on spaces, and its builder only
 * checked that every character was Devanagari. So it admitted fragments that are not words at all:
 * a lone visarga ः, a bare vowel sign ा, a chandrabindu ँ, a virama after a vowel sign (हो्) — the
 * debris of editing and of broken ligatures. 26 of them, rare, but ः romanizes to "ha" and was more
 * frequent than the real words sharing its key, which is how "haha" (skeleton "h") came out as ः.
 *
 * The rule is Devanagari's own: a dependent sign follows what it belongs to.
 *  - a word starts with a consonant or an independent vowel;
 *  - a vowel sign, virama or nukta follows a consonant (a nukta may sit between them);
 *  - a nasal sign (ँ ं) or visarga follows a consonant, a vowel or a vowel sign;
 *  - ZWJ/ZWNJ only follow a virama or a consonant (they shape a conjunct), never start or end a word.
 */
object Devanagari {

    private fun isConsonant(c: Char) = c in 'क'..'ह' || c in 'क़'..'य़' || c in 'ॸ'..'ॿ'
    private fun isIndependentVowel(c: Char) = c in 'ऄ'..'औ' || c in 'ॠ'..'ॡ' || c in 'ॲ'..'ॷ'
    private fun isVowelSign(c: Char) = c in 'ऺ'..'ऻ' || c in 'ा'..'ौ' || c in 'ॎ'..'ॏ' ||
        c in 'ॕ'..'ॗ' || c in 'ॢ'..'ॣ'
    private const val VIRAMA = '्'
    private const val NUKTA = '़'
    private fun isNasalOrVisarga(c: Char) = c in 'ऀ'..'ः'
    private fun isJoiner(c: Char) = c == '‌' || c == '‍'

    fun isWellFormed(word: String): Boolean {
        if (word.isEmpty()) return false
        if (!isConsonant(word[0]) && !isIndependentVowel(word[0])) return false
        if (isJoiner(word.last())) return false
        for (i in 1 until word.length) {
            val c = word[i]
            val before = word[i - 1]
            val ok = when {
                isConsonant(c) || isIndependentVowel(c) -> true
                c == NUKTA -> isConsonant(before)
                isVowelSign(c) || c == VIRAMA -> isConsonant(before) || before == NUKTA
                isNasalOrVisarga(c) -> isConsonant(before) || isIndependentVowel(before) || isVowelSign(before) || before == NUKTA
                isJoiner(c) -> before == VIRAMA || isConsonant(before)
                // Anything else (digits, Latin, danda) is not part of a word in this script.
                else -> false
            }
            if (!ok) return false
        }
        return true
    }
}
