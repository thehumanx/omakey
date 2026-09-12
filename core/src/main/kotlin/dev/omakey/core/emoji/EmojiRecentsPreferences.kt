package dev.omakey.core.emoji

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.StateFlow

/** Persists the most-recently-typed emoji, most-recent-first, deduped. Same SharedPreferences
 * pattern as the other *Preferences classes (see GesturePreferences) — a single delimited string
 * is enough here since the value is just an ordered list of short glyphs. */
class EmojiRecentsPreferences(context: Context) {
    // This class alone registered no change listener, so recents were the one preference in the
    // app that did not sync between the Settings instance and the IME's. Going through
    // PreferenceStore fixes that by construction rather than by remembering to.
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val recents: StateFlow<List<String>> = store.settings

    fun recordUse(emoji: String) {
        val next = (listOf(emoji) + store.value.filterNot { it == emoji }).take(MAX_RECENTS)
        store.edit { putString(KEY_RECENTS, next.joinToString(DELIMITER)) }
    }

    fun close() = store.close()

    private fun load(prefs: SharedPreferences): List<String> =
        prefs.getString(KEY_RECENTS, null)?.split(DELIMITER)?.filter { it.isNotEmpty() } ?: emptyList()

    private companion object {
        const val PREFS_NAME = "omakey_emoji_recents_prefs"
        const val KEY_RECENTS = "recents"
        const val DELIMITER = "␟" // unit separator control char, never part of an emoji glyph
        const val MAX_RECENTS = 30
    }
}
