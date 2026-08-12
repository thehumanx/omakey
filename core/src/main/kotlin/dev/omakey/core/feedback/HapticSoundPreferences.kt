package dev.omakey.core.feedback

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Known keypress-sound choices. The actual audio resources live in the app module (they
 * reference R.raw ids, which core has no access to) — this just persists the chosen id, same
 * split as [dev.omakey.core.theme.FontChoices]/`FontCatalog`. */
object SoundChoices {
    const val CRISP = "click_crisp"
    const val DEEP = "click_deep"
    const val SOFT = "click_soft"
    const val CLASSIC = "click_classic"

    const val DEFAULT = CRISP
}

data class HapticSoundSettings(
    val hapticEnabled: Boolean = true,
    /** 0..1 — maps to vibration amplitude (and, modestly, duration) at the point of use, since
     * "how strong the tick feels" is what users actually want to tune, not a raw millisecond
     * value they have no intuition for. */
    val hapticStrength: Float = DEFAULT_HAPTIC_STRENGTH,
    val soundEnabled: Boolean = false,
    val soundChoice: String = SoundChoices.DEFAULT,
) {
    companion object {
        const val DEFAULT_HAPTIC_STRENGTH = 0.5f
        const val MIN_HAPTIC_STRENGTH = 0.1f
        const val MAX_HAPTIC_STRENGTH = 1.0f
    }
}

/** Persists haptic/sound feedback settings. Same SharedPreferences + cross-instance-sync pattern
 * as the other *Preferences classes — see ThemeRepository for why the change listener is load
 * bearing, not decorative: the Settings Activity and the IME service each construct their own
 * instance of this class, and only the listener keeps them in sync live. */
class HapticSoundPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<HapticSoundSettings> = _settings

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settings.value = load()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun setHapticEnabled(enabled: Boolean) = update { it.copy(hapticEnabled = enabled) }

    fun setHapticStrength(value: Float) {
        update {
            it.copy(
                hapticStrength = value.coerceIn(
                    HapticSoundSettings.MIN_HAPTIC_STRENGTH,
                    HapticSoundSettings.MAX_HAPTIC_STRENGTH,
                ),
            )
        }
    }

    fun setSoundEnabled(enabled: Boolean) = update { it.copy(soundEnabled = enabled) }

    fun setSoundChoice(choice: String) = update { it.copy(soundChoice = choice) }

    private fun update(transform: (HapticSoundSettings) -> HapticSoundSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putBoolean(KEY_HAPTIC_ENABLED, next.hapticEnabled)
            .putFloat(KEY_HAPTIC_STRENGTH, next.hapticStrength)
            .putBoolean(KEY_SOUND_ENABLED, next.soundEnabled)
            .putString(KEY_SOUND_CHOICE, next.soundChoice)
            .apply()
        _settings.value = next
    }

    private fun load() = HapticSoundSettings(
        hapticEnabled = prefs.getBoolean(KEY_HAPTIC_ENABLED, true),
        hapticStrength = prefs.getFloat(KEY_HAPTIC_STRENGTH, HapticSoundSettings.DEFAULT_HAPTIC_STRENGTH),
        soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, false),
        soundChoice = prefs.getString(KEY_SOUND_CHOICE, SoundChoices.DEFAULT) ?: SoundChoices.DEFAULT,
    )

    private companion object {
        const val PREFS_NAME = "omakey_haptic_sound_prefs"
        const val KEY_HAPTIC_ENABLED = "haptic_enabled"
        const val KEY_HAPTIC_STRENGTH = "haptic_strength"
        const val KEY_SOUND_ENABLED = "sound_enabled"
        const val KEY_SOUND_CHOICE = "sound_choice"
    }
}
