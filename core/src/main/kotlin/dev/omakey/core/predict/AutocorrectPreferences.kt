package dev.omakey.core.predict

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.StateFlow

data class AutocorrectSettings(
    val autocorrectEnabled: Boolean = true,
    val autoCapitalizeEnabled: Boolean = false,
    /** Off by default, same convention as every other opt-in typing-behavior toggle here
     * (autoCapitalizeEnabled, GestureSettings.swipeRightForSpace) — tapping space twice quickly
     * (or, since swipe-right-for-space routes through the exact same commit path, swiping right
     * twice quickly when that gesture is enabled) replaces the two spaces with ". " instead.
     * See [KeyboardViewModel.onSpace]'s own doc for the actual detection logic. */
    val doubleTapSpaceForPeriod: Boolean = false,
)

/** Persists the autocorrect on/off preference. Same SharedPreferences + cross-instance-sync
 * pattern as the other *Preferences classes — see HapticSoundPreferences for why the change
 * listener is load bearing, not decorative: the Settings Activity and the IME service each
 * construct their own instance of this class, and only the listener keeps them in sync live. */
class AutocorrectPreferences(context: Context) {
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val settings: StateFlow<AutocorrectSettings> = store.settings

    fun setAutocorrectEnabled(enabled: Boolean) {
        store.edit { putBoolean(KEY_AUTOCORRECT_ENABLED, enabled) }
    }

    fun setAutoCapitalizeEnabled(enabled: Boolean) {
        store.edit { putBoolean(KEY_AUTO_CAPITALIZE_ENABLED, enabled) }
    }

    fun setDoubleTapSpaceForPeriod(enabled: Boolean) {
        store.edit { putBoolean(KEY_DOUBLE_TAP_SPACE_FOR_PERIOD, enabled) }
    }

    fun close() = store.close()

    private fun load(prefs: SharedPreferences) = AutocorrectSettings(
        autocorrectEnabled = prefs.getBoolean(KEY_AUTOCORRECT_ENABLED, true),
        autoCapitalizeEnabled = prefs.getBoolean(KEY_AUTO_CAPITALIZE_ENABLED, false),
        doubleTapSpaceForPeriod = prefs.getBoolean(KEY_DOUBLE_TAP_SPACE_FOR_PERIOD, false),
    )

    private companion object {
        const val PREFS_NAME = "omakey_autocorrect_prefs"
        const val KEY_AUTOCORRECT_ENABLED = "autocorrect_enabled"
        const val KEY_AUTO_CAPITALIZE_ENABLED = "auto_capitalize_enabled"
        const val KEY_DOUBLE_TAP_SPACE_FOR_PERIOD = "double_tap_space_for_period"
    }
}
