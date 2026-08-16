package dev.omakey.core.emoji

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Persists the most-recently-typed emoji, most-recent-first, deduped. Same SharedPreferences
 * pattern as the other *Preferences classes (see GesturePreferences) — a single delimited string
 * is enough here since the value is just an ordered list of short glyphs. */
class EmojiRecentsPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _recents = MutableStateFlow(load())
    val recents: StateFlow<List<String>> = _recents

    fun recordUse(emoji: String) {
        val next = (listOf(emoji) + _recents.value.filterNot { it == emoji }).take(MAX_RECENTS)
        prefs.edit().putString(KEY_RECENTS, next.joinToString(DELIMITER)).apply()
        _recents.value = next
    }

    private fun load(): List<String> =
        prefs.getString(KEY_RECENTS, null)?.split(DELIMITER)?.filter { it.isNotEmpty() } ?: emptyList()

    private companion object {
        const val PREFS_NAME = "omakey_emoji_recents_prefs"
        const val KEY_RECENTS = "recents"
        const val DELIMITER = "␟" // unit separator control char, never part of an emoji glyph
        const val MAX_RECENTS = 30
    }
}
