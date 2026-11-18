package dev.omakey.core.predict.eval

import kotlin.random.Random

/**
 * Misspells a Russian word the way Russian speakers do when typing quickly, for measuring
 * autocorrect on mistakes that aren't neighbouring-key slips (which `typeWithNoise` covers).
 *
 * **Synthetic.** There is no licensed corpus of real Russian typing errors in this repo, so these
 * are generated from well-known orthographic confusions: й→и, a dropped ь or ъ, ь↔ъ, a doubled
 * consonant written once, an unstressed о→а or е→и, and -тся↔-ться. Without stress data the vowel
 * rule may pick a stressed vowel, which makes the set somewhat harder than real typing.
 *
 * е/ё is deliberately not a rule: the keyboard treats the two as the same letter
 * (`equivalentLetters`), so typing "е" for "ё" isn't an error to measure.
 */
object RussianOrthography {

    private const val CONSONANTS = "бвгджзйклмнпрстфхцчшщ"

    /** One misspelling of [word], chosen uniformly among the rules that apply, or null when none
     * does (or the word is under four letters). */
    fun misspell(word: String, random: Random): String? {
        if (word.length < 4) return null
        val options = mutableListOf<String>()
        for (i in word.indices) {
            val c = word[i]
            when {
                c == 'й' -> options += word.replaceAt(i, "и")
                c == 'ь' -> { options += word.removeAt(i); options += word.replaceAt(i, "ъ") }
                c == 'ъ' -> { options += word.removeAt(i); options += word.replaceAt(i, "ь") }
                c == 'о' && i > 0 -> options += word.replaceAt(i, "а")
                c == 'е' && i > 0 -> options += word.replaceAt(i, "и")
            }
            if (i + 1 < word.length && c == word[i + 1] && c in CONSONANTS) options += word.removeAt(i)
        }
        when {
            word.endsWith("ться") -> options += word.dropLast(4) + "тся"
            word.endsWith("тся") -> options += word.dropLast(3) + "ться"
        }
        val distinct = options.distinct().filter { it != word }
        return if (distinct.isEmpty()) null else distinct[random.nextInt(distinct.size)]
    }

    private fun String.replaceAt(i: Int, with: String) = substring(0, i) + with + substring(i + 1)
    private fun String.removeAt(i: Int) = substring(0, i) + substring(i + 1)
}
