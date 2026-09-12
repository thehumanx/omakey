package dev.omakey.core.clipboard

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.StateFlow

data class ClipboardSettings(
    /**
     * Whether copying anything while the keyboard is on screen records it in clipboard history.
     *
     * On by default — the panel is one of the two built-in extensions and would be permanently
     * empty otherwise. But it is a real toggle rather than an always-on feature, because clipboard
     * history is the one part of omakey that stores what the user copied rather than what they
     * typed, and "a keyboard that keeps a copy of everything you paste" is a thing someone may
     * reasonably not want at all, independent of the sensitive-clip and incognito gates that apply
     * regardless.
     *
     * Turning it off stops new captures; it does not delete what is already stored. Clearing is a
     * separate, explicit action, because silently destroying data as a side effect of flipping a
     * switch is not something a settings toggle should do.
     */
    val historyEnabled: Boolean = true,
)

/** Same [PreferenceStore] pattern as every other preference class. */
class ClipboardPreferences(context: Context) {
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val settings: StateFlow<ClipboardSettings> = store.settings

    fun setHistoryEnabled(enabled: Boolean) = store.edit { putBoolean(KEY_HISTORY_ENABLED, enabled) }

    fun close() = store.close()

    private fun load(prefs: SharedPreferences) = ClipboardSettings(
        historyEnabled = prefs.getBoolean(KEY_HISTORY_ENABLED, true),
    )

    private companion object {
        const val PREFS_NAME = "omakey_clipboard_prefs"
        const val KEY_HISTORY_ENABLED = "history_enabled"
    }
}
