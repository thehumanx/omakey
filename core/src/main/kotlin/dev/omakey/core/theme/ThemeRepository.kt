package dev.omakey.core.theme

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
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
    // Three independent values in one preferences file, so three stores over that same file rather
    // than one store holding a combined object: these are exposed as three separate StateFlows the
    // rest of the app collects individually, and deriving three from one would need a
    // CoroutineScope this class has no reason to own. getSharedPreferences returns the same
    // instance for a given name, so this is three listeners on one file — which is exactly what
    // the single hand-written listener did, since it reloaded all three values on any change.
    private val themeStore = PreferenceStore(context, PREFS_NAME, ::loadPersistedTheme)
    val currentTheme: StateFlow<OmakeyTheme> = themeStore.settings

    /** "Pick accent color from system" — independent of [currentTheme] itself (you can want a
     * fixed Light theme but still have the spacebar pulled from the device's Material You
     * palette). See `resolveEffectiveTheme` in the app module for where this is actually applied;
     * this class only stores the flag. */
    private val accentStore = PreferenceStore(context, PREFS_NAME) { it.getBoolean(KEY_USE_SYSTEM_ACCENT, false) }
    val useSystemAccent: StateFlow<Boolean> = accentStore.settings

    /** Normal vs. Grid — see [LayoutMode]'s own doc. Independent of [currentTheme] the same way
     * [useSystemAccent] is: any color theme can be paired with either layout mode, so this is its
     * own flag rather than a field on [OmakeyTheme] (which would otherwise need duplicating every
     * preset/custom theme to offer both). */
    private val layoutModeStore = PreferenceStore(context, PREFS_NAME, ::loadPersistedLayoutMode)
    val layoutMode: StateFlow<LayoutMode> = layoutModeStore.settings

    fun setTheme(theme: OmakeyTheme) =
        themeStore.edit { putString(KEY_THEME_JSON, ThemeSerializer.toJson(theme)) }

    fun setUseSystemAccent(enabled: Boolean) =
        accentStore.edit { putBoolean(KEY_USE_SYSTEM_ACCENT, enabled) }

    fun setLayoutMode(mode: LayoutMode) =
        layoutModeStore.edit { putString(KEY_LAYOUT_MODE, mode.name) }

    fun close() {
        themeStore.close()
        accentStore.close()
        layoutModeStore.close()
    }


    private fun loadPersistedTheme(prefs: SharedPreferences): OmakeyTheme {
        val json = prefs.getString(KEY_THEME_JSON, null) ?: return Presets.Dark
        return runCatching { ThemeSerializer.fromJson(json) }.getOrDefault(Presets.Dark)
    }

    private fun loadPersistedLayoutMode(prefs: SharedPreferences): LayoutMode {
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
