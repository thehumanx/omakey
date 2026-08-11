package dev.omakey.core.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the user's selected theme via SharedPreferences (a single JSON blob — cheap, and
 * avoids a Room migration for what is, for v1, a single row of state). The settings Activity and
 * the IME service each construct their own ThemeRepository instance, so a plain StateFlow alone
 * is NOT enough to keep an already-open keyboard in sync — a write from one instance never
 * reaches the other's in-memory StateFlow on its own. The registered SharedPreferences listener
 * below is what actually closes that gap, since both instances share the same underlying prefs.
 */
class ThemeRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentTheme = MutableStateFlow(loadPersistedTheme())
    val currentTheme: StateFlow<OmakeyTheme> = _currentTheme

    // Held as a field, not an inline lambda — SharedPreferences only keeps a weak reference to
    // registered listeners, so an unreferenced lambda would get garbage collected almost
    // immediately and silently stop firing.
    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _currentTheme.value = loadPersistedTheme()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun setTheme(theme: OmakeyTheme) {
        prefs.edit().putString(KEY_THEME_JSON, ThemeSerializer.toJson(theme)).apply()
        _currentTheme.value = theme
    }

    private fun loadPersistedTheme(): OmakeyTheme {
        val json = prefs.getString(KEY_THEME_JSON, null) ?: return Presets.Dark
        return runCatching { ThemeSerializer.fromJson(json) }.getOrDefault(Presets.Dark)
    }

    private companion object {
        const val PREFS_NAME = "omakey_theme_prefs"
        const val KEY_THEME_JSON = "current_theme_json"
    }
}
