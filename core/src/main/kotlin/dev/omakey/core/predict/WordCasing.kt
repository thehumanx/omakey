package dev.omakey.core.predict

import dev.omakey.core.input.AutoCapitalization
import dev.omakey.core.locale.LanguageProfile

/**
 * Which capitals a word is written with, given the spellings the user saved ("iPhone", "Bibek").
 *
 * Words are stored and looked up lowercase, so before learned words had a spelling of their own
 * the capitals came only from what was typed ([matchCase]) and from the language's own fixed words
 * ("I"). A saved spelling now wins over both, except that typing a word in all caps still gives
 * all caps. [userSpelling] is a lookup by word (any case), usually `PredictionEngine.spellingOf`.
 */
object WordCasing {

    /** The user's saved spelling of [word], or of its part after a clitic ("l'" + "Homme") with the
     * clitic left as typed. Null when none. */
    private fun userFixed(word: String, profile: LanguageProfile, userSpelling: (String) -> String?): String? {
        userSpelling(word)?.let { return it }
        val (clitic, rest) = profile.splitClitic(word) ?: return null
        return userSpelling(rest)?.let { clitic + it }
    }

    /** How [word] is always written: the user's saved spelling, else the language's own fixed word
     * ("i" → "I"), else null. */
    fun fixedSpelling(word: String, profile: LanguageProfile, userSpelling: (String) -> String?): String? =
        userFixed(word, profile, userSpelling) ?: profile.alwaysCapitalized[word.lowercase()]

    /** [chosen] (a correction or alternative for [typed], lowercase) as it should be written: all
     * caps when [typed] was two or more capitals, else the saved spelling, else [matchCase]. */
    fun forTyped(typed: String, chosen: String, profile: LanguageProfile, userSpelling: (String) -> String?): String {
        if (typed.length >= 2 && typed.all { !it.isLetter() || it.isUpperCase() } && typed.any { it.isLetter() }) {
            return chosen.uppercase()
        }
        return userFixed(chosen, profile, userSpelling) ?: matchCase(typed, chosen)
    }

    /** [word] as the suggestion strip shows it: the saved spelling, else the language's own
     * capitals ([AutoCapitalization.capitalizedSuggestion]). */
    fun forSuggestion(word: String, profile: LanguageProfile, userSpelling: (String) -> String?): String =
        userFixed(word, profile, userSpelling) ?: AutoCapitalization.capitalizedSuggestion(word, profile)
}
