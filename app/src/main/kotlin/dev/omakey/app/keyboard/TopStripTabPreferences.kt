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
    /** A password manager's login suggestions on the Suggestions page (Android 11+ inline
     * autofill). On by default; off, omakey doesn't ask for them and the manager falls back to
     * its own popup. */
    val autofillSuggestions: Boolean = true,
    /** The user's order for every page after Suggestions, which always comes first (it's where
     * typing happens). As stored; see [movableOrder] for the cleaned-up version. */
    val order: List<TopStripTab> = MOVABLE_DEFAULT,
) {
    /** [order] with Suggestions, repeats and unknown pages removed, and any page it is missing
     * (one added by a later version) appended in the default order. */
    val movableOrder: List<TopStripTab>
        get() = order.filter { it != TopStripTab.SUGGESTIONS }.distinct().let { kept ->
            kept + MOVABLE_DEFAULT.filter { it !in kept }
        }

    /** The pages shown, in swipe order. Never empty. */
    val pages: List<TopStripTab>
        get() = (listOf(TopStripTab.SUGGESTIONS) + movableOrder).filter { it !in hidden }.ifEmpty { PAGE_ORDER }

    /** The order after moving [page] [by] places (−1 earlier, +1 later), stopping at either end.
     * Suggestions doesn't move. */
    fun moved(page: TopStripTab, by: Int): List<TopStripTab> {
        val list = movableOrder.toMutableList()
        val from = list.indexOf(page)
        if (from < 0) return list
        list.add((from + by).coerceIn(0, list.lastIndex), list.removeAt(from))
        return list
    }

    companion object {
        private val MOVABLE_DEFAULT = listOf(TopStripTab.SNIPPETS, TopStripTab.NUMBERS, TopStripTab.TOOLS)

        // Snippets sits next to Suggestions: one swipe from typing to your saved text. A new page
        // starts shown on existing installs, since only the hidden set is stored.
        val PAGE_ORDER = listOf(TopStripTab.SUGGESTIONS, TopStripTab.SNIPPETS, TopStripTab.NUMBERS, TopStripTab.TOOLS)
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

    /** Moves a page earlier (−1) or later (+1) in the bar; Suggestions stays first. */
    fun movePage(page: TopStripTab, by: Int) {
        store.edit { putString(KEY_ORDER, settings.value.moved(page, by).joinToString(",") { it.name }) }
    }

    fun setAutofillSuggestions(enabled: Boolean) {
        store.edit { putBoolean(KEY_AUTOFILL, enabled) }
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
        val order = prefs.getString(KEY_ORDER, null)?.split(',')
            ?.mapNotNull { name -> runCatching { TopStripTab.valueOf(name) }.getOrNull() }
        return TopStripSettings(
            tab, hidden,
            autofillSuggestions = prefs.getBoolean(KEY_AUTOFILL, true),
            order = order ?: TopStripSettings().order,
        )
    }

    private companion object {
        const val PREFS_NAME = "omakey_top_strip_tab_prefs"
        const val KEY_TAB = "top_strip_tab"
        const val KEY_HIDDEN = "hidden_pages"
        const val KEY_AUTOFILL = "autofill_suggestions"
        const val KEY_ORDER = "page_order"
    }
}
