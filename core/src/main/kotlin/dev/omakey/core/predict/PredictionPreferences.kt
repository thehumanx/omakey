package dev.omakey.core.predict

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PredictionSettings(
    /** Bigram/frequency-based "what word comes next" suggestions — separate from autocorrect
     * (see [AutocorrectPreferences]), which fixes the word actually being typed. Off by default:
     * the suggestion strip should only ever show something when there's an actual correction to
     * offer, not a standing list of guesses at what's coming next. */
    val nextWordPredictionEnabled: Boolean = false,
)

/** Persists the next-word-prediction on/off preference. Same SharedPreferences + cross-instance-
 * sync pattern as the other *Preferences classes — see HapticSoundPreferences for why the change
 * listener is load bearing, not decorative: the Settings Activity and the IME service each
 * construct their own instance of this class, and only the listener keeps them in sync live. */
class PredictionPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<PredictionSettings> = _settings

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settings.value = load()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun setNextWordPredictionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NEXT_WORD_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(nextWordPredictionEnabled = enabled)
    }

    private fun load() = PredictionSettings(
        nextWordPredictionEnabled = prefs.getBoolean(KEY_NEXT_WORD_ENABLED, false),
    )

    private companion object {
        const val PREFS_NAME = "omakey_prediction_prefs"
        const val KEY_NEXT_WORD_ENABLED = "next_word_prediction_enabled"
    }
}
