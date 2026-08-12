package dev.omakey.core.predict

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AutocorrectSettings(
    val autocorrectEnabled: Boolean = true,
    val autoCapitalizeEnabled: Boolean = false,
)

/** Persists the autocorrect on/off preference. Same SharedPreferences + cross-instance-sync
 * pattern as the other *Preferences classes — see HapticSoundPreferences for why the change
 * listener is load bearing, not decorative: the Settings Activity and the IME service each
 * construct their own instance of this class, and only the listener keeps them in sync live. */
class AutocorrectPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AutocorrectSettings> = _settings

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settings.value = load()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun setAutocorrectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOCORRECT_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(autocorrectEnabled = enabled)
    }

    fun setAutoCapitalizeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CAPITALIZE_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(autoCapitalizeEnabled = enabled)
    }

    private fun load() = AutocorrectSettings(
        autocorrectEnabled = prefs.getBoolean(KEY_AUTOCORRECT_ENABLED, true),
        autoCapitalizeEnabled = prefs.getBoolean(KEY_AUTO_CAPITALIZE_ENABLED, false),
    )

    private companion object {
        const val PREFS_NAME = "omakey_autocorrect_prefs"
        const val KEY_AUTOCORRECT_ENABLED = "autocorrect_enabled"
        const val KEY_AUTO_CAPITALIZE_ENABLED = "auto_capitalize_enabled"
    }
}
