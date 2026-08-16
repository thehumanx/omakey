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

    /** "Pick accent color from system" — independent of [currentTheme] itself (you can want a
     * fixed Light theme but still have the spacebar pulled from the device's Material You
     * palette). See `resolveEffectiveTheme` in the app module for where this is actually applied;
     * this class only stores the flag. */
    private val _useSystemAccent = MutableStateFlow(prefs.getBoolean(KEY_USE_SYSTEM_ACCENT, false))
    val useSystemAccent: StateFlow<Boolean> = _useSystemAccent

    /** Normal vs. Grid — see [LayoutMode]'s own doc. Independent of [currentTheme] the same way
     * [useSystemAccent] is: any color theme can be paired with either layout mode, so this is its
     * own flag rather than a field on [OmakeyTheme] (which would otherwise need duplicating every
     * preset/custom theme to offer both). */
    private val _layoutMode = MutableStateFlow(loadPersistedLayoutMode())
    val layoutMode: StateFlow<LayoutMode> = _layoutMode

    // Held as a field, not an inline lambda — SharedPreferences only keeps a weak reference to
    // registered listeners, so an unreferenced lambda would get garbage collected almost
    // immediately and silently stop firing.
    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _currentTheme.value = loadPersistedTheme()
        _useSystemAccent.value = prefs.getBoolean(KEY_USE_SYSTEM_ACCENT, false)
        _layoutMode.value = loadPersistedLayoutMode()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun setTheme(theme: OmakeyTheme) {
        prefs.edit().putString(KEY_THEME_JSON, ThemeSerializer.toJson(theme)).apply()
        _currentTheme.value = theme
    }

    fun setUseSystemAccent(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_SYSTEM_ACCENT, enabled).apply()
        _useSystemAccent.value = enabled
    }

    fun setLayoutMode(mode: LayoutMode) {
        prefs.edit().putString(KEY_LAYOUT_MODE, mode.name).apply()
        _layoutMode.value = mode
    }

    private fun loadPersistedTheme(): OmakeyTheme {
        val json = prefs.getString(KEY_THEME_JSON, null) ?: return Presets.Dark
        return runCatching { ThemeSerializer.fromJson(json) }.getOrDefault(Presets.Dark)
    }

    private fun loadPersistedLayoutMode(): LayoutMode {
        val stored = prefs.getString(KEY_LAYOUT_MODE, null) ?: return LayoutMode.NORMAL
        return runCatching { LayoutMode.valueOf(stored) }.getOrDefault(LayoutMode.NORMAL)
    }

    private companion object {
        const val PREFS_NAME = "omakey_theme_prefs"
        const val KEY_THEME_JSON = "current_theme_json"
        const val KEY_USE_SYSTEM_ACCENT = "use_system_accent"
        const val KEY_LAYOUT_MODE = "layout_mode"
    }
}
