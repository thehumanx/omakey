package dev.omakey.core.theme

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.StateFlow

/**
 * User-facing override for accessible mode (disables surface-swipe gesture capture in favor of
 * ordinary per-key taps). Kept separate from the automatic TalkBack detection in the keyboard UI
 * layer — that check lives where AccessibilityManager is reachable (Compose LocalContext) — so
 * this class only needs to persist the user's explicit choice, same SharedPreferences pattern as
 * [ThemeRepository], including the registered change listener that keeps a separately-constructed
 * instance (e.g. the IME's, while Settings' instance is the one being written to) in sync.
 */
class AccessibilityPreferences(context: Context) {
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val forceAccessibleMode: StateFlow<Boolean> = store.settings

    fun setForceAccessibleMode(enabled: Boolean) = store.edit { putBoolean(KEY_FORCE_ACCESSIBLE, enabled) }

    fun close() = store.close()

    private fun load(prefs: SharedPreferences) = prefs.getBoolean(KEY_FORCE_ACCESSIBLE, false)

    private companion object {
        const val PREFS_NAME = "omakey_accessibility_prefs"
        const val KEY_FORCE_ACCESSIBLE = "force_accessible_mode"
    }
}
