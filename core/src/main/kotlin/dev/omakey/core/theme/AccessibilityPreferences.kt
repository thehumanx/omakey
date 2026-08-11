package dev.omakey.core.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _forceAccessibleMode = MutableStateFlow(prefs.getBoolean(KEY_FORCE_ACCESSIBLE, false))
    val forceAccessibleMode: StateFlow<Boolean> = _forceAccessibleMode

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _forceAccessibleMode.value = prefs.getBoolean(KEY_FORCE_ACCESSIBLE, false)
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun setForceAccessibleMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_ACCESSIBLE, enabled).apply()
        _forceAccessibleMode.value = enabled
    }

    private companion object {
        const val PREFS_NAME = "omakey_accessibility_prefs"
        const val KEY_FORCE_ACCESSIBLE = "force_accessible_mode"
    }
}
