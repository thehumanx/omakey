package dev.omakey.core.db

import dev.omakey.core.locale.LanguageProfile
import dev.omakey.core.locale.toLookupForm

/**
 * The rules for editing a learned word in Settings, kept out of the screen so they can be tested.
 *
 * Closed-beta report: editing a learned word "doesn't work". Two causes: renaming onto a word that
 * was already learned hit the table's primary key (locale, word) and crashed the screen, and the
 * running keyboard never heard about the edit (see `LearnedWordsSignal`).
 *
 * 5.8.0 report: editing "bibek" to "Bibek" did nothing. The edit was lowercased to the lookup form,
 * matched the old word, and Save quietly skipped it. A word now keeps the spelling the user wrote
 * ([Edit.display]) next to its lowercase key.
 */
object LearnedWordEdits {

    /** An edit: [word] is the lowercase lookup key, [display] the spelling to keep when it has a
     * capital ("iPhone"), null when the user wrote it all lowercase. */
    data class Edit(val word: String, val display: String?)

    /** [input] as an [Edit], or null when it isn't one word of [profile]'s letters — a space, a
     * digit, punctuation. */
    fun normalise(input: String, profile: LanguageProfile): Edit? {
        val written = input.trim()
        val word = written.toLookupForm()
        if (word.isEmpty() || !profile.isWord(word) || word.none { it.isLetter() }) return null
        return Edit(word, written.takeIf { w -> w.any { it.isUpperCase() } })
    }

    /** The row [old] becomes when renamed to [newWord] spelled [display]: merged into [existing]
     * when that word was already learned (counts added, saved if either was, most recent use kept),
     * otherwise [old] under its new key with its history intact. The edited spelling wins either way. */
    fun merge(old: WordEntity, existing: WordEntity?, newWord: String, display: String?): WordEntity =
        if (existing == null) {
            old.copy(word = newWord, display = display)
        } else {
            existing.copy(
                frequency = existing.frequency + old.frequency,
                explicit = existing.explicit || old.explicit,
                lastUsedTimestamp = maxOf(existing.lastUsedTimestamp, old.lastUsedTimestamp),
                isUserAdded = true,
                display = display,
            )
        }
}
