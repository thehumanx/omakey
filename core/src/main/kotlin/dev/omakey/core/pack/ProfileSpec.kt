package dev.omakey.core.pack

import dev.omakey.core.locale.LanguageProfile
import dev.omakey.core.locale.Script
import kotlinx.serialization.Serializable

/**
 * [LanguageProfile] as data, for a pack's `profile.json`. Character sets are plain strings ("¿¡")
 * because that is how a person writing the file thinks of them.
 */
@Serializable
data class ProfileSpec(
    val script: Script,
    val hasCase: Boolean,
    val extraWordChars: String = "",
    val capitalizeAfter: String = ".!?",
    val openingPunctuation: String = "",
    val doubleSpaceInserts: String = ".",
    val punctuationCycle: String = ".,!?;:'\"",
    val contractions: Map<String, String> = emptyMap(),
    val equivalentLetters: List<String> = emptyList(),
    /** Exact-word emoji suggestions, lowercase word → emoji. */
    val emojiWords: Map<String, List<String>> = emptyMap(),
    val clitics: List<String> = emptyList(),
) {
    fun toProfile(): LanguageProfile {
        require(doubleSpaceInserts.length == 1) { "doubleSpaceInserts must be one character" }
        require(punctuationCycle.isNotEmpty()) { "punctuationCycle must not be empty" }
        val emoji = emojiWords.mapKeys { it.key.lowercase() }
        return LanguageProfile(
            script = script,
            hasCase = hasCase,
            extraWordChars = extraWordChars.toSet(),
            capitalizeAfter = capitalizeAfter.toSet(),
            openingPunctuation = openingPunctuation.toSet(),
            doubleSpaceInserts = doubleSpaceInserts.single(),
            punctuationCycle = punctuationCycle.toList(),
            contractions = contractions.mapKeys { it.key.lowercase() },
            emojiFor = { word -> emoji[word.lowercase()].orEmpty().take(MAX_EMOJI) },
            equivalentLetters = equivalentLetters,
            clitics = clitics,
        )
    }
}

/** Same cap as the English table's (`WordEmojiSuggestions`). Not in a companion object: the
 * serialization plugin puts `serializer()` there, and a private companion hides it. */
private const val MAX_EMOJI = 2
