package dev.omakey.app.keyboard

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.StateFlow

/** Persists which of the suggestion strip's swipeable pages (Suggestions/Numbers/Tools) was last
 * active, across IME restarts and app launches — same SharedPreferences+StateFlow+cross-instance-
 * listener pattern as every other `*Preferences` class (see `GesturePreferences` for why the
 * listener is necessary, not optional: `KeyboardViewModel` and any other holder of this class are
 * separate objects, and a plain in-memory `StateFlow` alone wouldn't see a write made elsewhere). */
class TopStripTabPreferences(context: Context) {
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val tab: StateFlow<TopStripTab> = store.settings

    fun setTab(tab: TopStripTab) {
        store.edit { putString(KEY_TAB, tab.name) }
    }

    fun close() = store.close()

    private fun load(prefs: SharedPreferences): TopStripTab {
        val name = prefs.getString(KEY_TAB, null) ?: return TopStripTab.SUGGESTIONS
        return runCatching { TopStripTab.valueOf(name) }.getOrDefault(TopStripTab.SUGGESTIONS)
    }

    private companion object {
        const val PREFS_NAME = "omakey_top_strip_tab_prefs"
        const val KEY_TAB = "top_strip_tab"
    }
}
