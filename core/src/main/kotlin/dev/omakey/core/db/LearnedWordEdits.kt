package dev.omakey.core.db

import dev.omakey.core.locale.LanguageProfile
import dev.omakey.core.locale.toLookupForm

/**
 * The rules for editing a learned word in Settings, kept out of the screen so they can be tested.
 *
 * Closed-beta report: editing a learned word "doesn't work". Two causes: renaming onto a word that
 * was already learned hit the table's primary key (locale, word) and crashed the screen, and the
 * running keyboard never heard about the edit (see `LearnedWordsSignal`).
 */
object LearnedWordEdits {

    /** [input] as the keyboard stores and looks words up ([toLookupForm]), or null when it isn't
     * one word of [profile]'s letters — a space, a digit, punctuation. */
    fun normalise(input: String, profile: LanguageProfile): String? {
        val word = input.trim().toLookupForm()
        return word.takeIf { it.isNotEmpty() && profile.isWord(it) && it.any { c -> c.isLetter() } }
    }

    /** The row [old] becomes when renamed to [newWord]: merged into [existing] when that word was
     * already learned (counts added, saved if either was, most recent use kept), otherwise [old]
     * under its new spelling with its history intact. */
    fun merge(old: WordEntity, existing: WordEntity?, newWord: String): WordEntity =
        if (existing == null) {
            old.copy(word = newWord)
        } else {
            existing.copy(
                frequency = existing.frequency + old.frequency,
                explicit = existing.explicit || old.explicit,
                lastUsedTimestamp = maxOf(existing.lastUsedTimestamp, old.lastUsedTimestamp),
                isUserAdded = true,
            )
        }
}
