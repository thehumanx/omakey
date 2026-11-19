package dev.omakey.core.input

import dev.omakey.core.locale.LanguageProfile

/**
 * What a swipe-up save stores: the word exactly as the user wrote it ("iPhone" stays "iPhone"),
 * minus a capital the keyboard added by itself at the start of a sentence — saving "Hello" there
 * must not give "hello" a capital forever.
 *
 * Judged by position, not by tracking Shift per keystroke (swipe typing and corrections make that
 * unreliable): the capital is dropped only when it is the word's only one, auto-capitalise is on,
 * and the word sits where auto-capitalise would have put a capital. Inner capitals ("iPhone",
 * "McDonald") are always the user's. The cost, accepted by the developer: a name deliberately
 * capitalised at a sentence start saves lowercase and needs a Settings edit.
 */
object SavedSpelling {

    fun of(
        word: String,
        textBeforeCursor: String,
        profile: LanguageProfile,
        mode: AutoCapitalization.FieldMode,
        autoCapitalizeEnabled: Boolean,
    ): String {
        if (!autoCapitalizeEnabled || word.isEmpty() || !word.first().isUpperCase()) return word
        if (word.drop(1).any { it.isUpperCase() }) return word
        val at = textBeforeCursor.lastIndexOf(word)
        if (at < 0) return word
        if (!AutoCapitalization.shouldCapitalize(textBeforeCursor.substring(0, at), profile, mode)) return word
        return word.replaceFirstChar { it.lowercase() }
    }
}
