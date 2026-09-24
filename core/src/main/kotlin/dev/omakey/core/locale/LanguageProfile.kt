package dev.omakey.core.locale

import dev.omakey.core.emoji.WordEmojiSuggestions

/** Writing system of a language, where it changes behaviour rather than just which glyphs appear. */
enum class Script { LATIN, DEVANAGARI }

/**
 * The text rules that differ between languages: what counts as part of a word, how sentences end,
 * and the language's own data tables.
 *
 * Before this existed, every one of these was an English assumption written inline — most damagingly
 * `Char.isLetter()` as the definition of "part of a word". Devanagari vowel signs (ि ा ु), the halant
 * (्) and anusvara (ं) are Unicode *marks*, not letters, so under that rule typing कि ended the word
 * halfway through its only syllable: autocorrect, suggestion refresh and learning all fired
 * mid-syllable. That is what an earlier Nepali attempt ran into (AGENTS.md §66.1).
 *
 * Deliberately **not** in here: casing. Kotlin's `uppercase()`/`isUpperCase()` are already identity
 * and false for caseless scripts, so `matchCase` and keycap uppercasing are correct for Devanagari
 * without a flag. [hasCase] exists only for what *does* differ — whether Shift and auto-capitalise
 * mean anything.
 */
class LanguageProfile(
    val script: Script,
    /** Whether letters have upper/lower forms. When false, auto-capitalise is a no-op, and (from
     * AGENTS.md §66 Phase 2) Shift switches to a second layer instead of changing case. */
    val hasCase: Boolean,
    /** Characters that are part of a word although they are neither letters nor combining marks —
     * the apostrophe for French elision, ZWJ/ZWNJ for Devanagari conjunct control. Empty for
     * English, whose contractions are handled by [contractions] rather than by the word buffer. */
    val extraWordChars: Set<Char> = emptySet(),
    /** Characters after which auto-capitalise capitalises the next word — sentence-ending
     * punctuation. */
    val capitalizeAfter: Set<Char>,
    /** Punctuation that opens a sentence *before* its first letter — Spanish "¿" and "¡". Typing
     * one keeps a pending one-shot capital for the letter after it, instead of spending it on the
     * punctuation mark, so "¿Cómo" comes out capitalised where the sentence starts. */
    val openingPunctuation: Set<Char> = emptySet(),
    /** What double-tap-space inserts before the space: '.' for English, '।' (danda) for Nepali. */
    val doubleSpaceInserts: Char,
    /** Punctuation that swipe up/down rotates through when it sits just left of the cursor, in
     * rotation order. Also the set that counts as "finishing a word" for retroactive correction. */
    val punctuationCycle: List<Char>,
    /** Apostrophe-less spellings mapped to their contraction ("im" → "I'm"), offered but never
     * auto-applied. See `AutocorrectIndex.contractionFor`. */
    val contractions: Map<String, String> = emptyMap(),
    /** Exact-word emoji suggestions for this language. Empty by default — an English table applied
     * to another language is not "fewer suggestions", it is wrong ones. */
    val emojiFor: (String) -> List<String> = { emptyList() },
    /** Letters that stand in for each other nearly for free in correction, one group per string —
     * "eéèêë" lets "cancion" find "canción". See `ChannelModel.equivalentGroups`. */
    val equivalentLetters: List<String> = emptyList(),
) {

    /** Whether [c] belongs to a word: a letter, a combining mark (so Devanagari vowel signs and the
     * halant stay inside the word they modify), or one of [extraWordChars]. */
    fun isWordChar(c: Char): Boolean = c.isLetter() || isCombiningMark(c) || c in extraWordChars

    fun isWord(text: CharSequence): Boolean = text.isNotEmpty() && text.all(::isWordChar)

    companion object {
        /** Non-spacing (Mn) and spacing-combining (Mc) marks. Enclosing marks (Me) are left out:
         * they draw a circle or keycap around what precedes them and are emoji machinery, not
         * spelling. */
        fun isCombiningMark(c: Char): Boolean = when (Character.getType(c).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }

        val English = LanguageProfile(
            script = Script.LATIN,
            hasCase = true,
            capitalizeAfter = setOf('.', '!', '?'),
            doubleSpaceInserts = '.',
            punctuationCycle = listOf('.', ',', '!', '?', ';', ':', '\'', '"'),
            contractions = EnglishContractions.map,
            emojiFor = WordEmojiSuggestions::suggest,
        )
    }
}
