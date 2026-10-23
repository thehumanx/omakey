package dev.omakey.app.keyboard

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.StateFlow

/**
 * The suggestion strip's swipeable pages: which one was last active, and which ones the user has
 * turned off (GitHub issue #10: "hide the word suggestion strip and have the number row instead").
 *
 * The hidden set is stored rather than the shown one, so a page added later starts out shown. At
 * least one page always stays: the strip's height is part of the keyboard's, and an empty strip
 * would just be a blank bar.
 */
data class TopStripSettings(
    val tab: TopStripTab = TopStripTab.SUGGESTIONS,
    val hidden: Set<TopStripTab> = emptySet(),
) {
    /** The pages shown, in swipe order. Never empty. */
    val pages: List<TopStripTab>
        get() = PAGE_ORDER.filter { it !in hidden }.ifEmpty { PAGE_ORDER }

    companion object {
        val PAGE_ORDER = listOf(TopStripTab.SUGGESTIONS, TopStripTab.NUMBERS, TopStripTab.TOOLS)
    }
}

/** Same SharedPreferences+StateFlow+cross-instance-listener pattern as every other `*Preferences`
 * class (see `GesturePreferences` for why the listener is necessary, not optional: the IME and
 * Settings each hold their own instance, and a plain in-memory `StateFlow` alone wouldn't see a
 * write made by the other). */
class TopStripTabPreferences(context: Context) {
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val settings: StateFlow<TopStripSettings> = store.settings

    fun setTab(tab: TopStripTab) {
        store.edit { putString(KEY_TAB, tab.name) }
    }

    /** Turning off the last shown page is ignored; see [TopStripSettings]. */
    fun setPageShown(page: TopStripTab, shown: Boolean) {
        val hidden = settings.value.hidden.let { if (shown) it - page else it + page }
        if (TopStripSettings.PAGE_ORDER.all { it in hidden }) return
        store.edit { putStringSet(KEY_HIDDEN, hidden.map { it.name }.toSet()) }
    }

    fun close() = store.close()

    private fun load(prefs: SharedPreferences): TopStripSettings {
        val tab = prefs.getString(KEY_TAB, null)
            ?.let { name -> runCatching { TopStripTab.valueOf(name) }.getOrNull() }
            ?: TopStripTab.SUGGESTIONS
        // Unknown names (a page a newer build had) are skipped rather than failing the load.
        val hidden = prefs.getStringSet(KEY_HIDDEN, null).orEmpty()
            .mapNotNull { name -> runCatching { TopStripTab.valueOf(name) }.getOrNull() }
            .toSet()
        return TopStripSettings(tab, hidden)
    }

    private companion object {
        const val PREFS_NAME = "omakey_top_strip_tab_prefs"
        const val KEY_TAB = "top_strip_tab"
        const val KEY_HIDDEN = "hidden_pages"
    }
}
