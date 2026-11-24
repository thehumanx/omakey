package dev.omakey.core.predict

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.StateFlow

data class PredictionSettings(
    /** Bigram/frequency-based "what word comes next" suggestions — separate from autocorrect
     * (see [AutocorrectPreferences]), which fixes the word actually being typed. On for fresh
     * installs since the design pass: an empty bar after a space made the keyboard look idle.
     * InstallDefaults keeps updating installs on what they had. */
    val nextWordPredictionEnabled: Boolean = true,
)

/** Persists the next-word-prediction on/off preference. Same SharedPreferences + cross-instance-
 * sync pattern as the other *Preferences classes — see HapticSoundPreferences for why the change
 * listener is load bearing, not decorative: the Settings Activity and the IME service each
 * construct their own instance of this class, and only the listener keeps them in sync live. */
class PredictionPreferences(context: Context) {
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val settings: StateFlow<PredictionSettings> = store.settings

    fun close() = store.close()

    fun setNextWordPredictionEnabled(enabled: Boolean) {
        store.edit { putBoolean(KEY_NEXT_WORD_ENABLED, enabled) }
    }

    private fun load(prefs: SharedPreferences) = PredictionSettings(
        nextWordPredictionEnabled = prefs.getBoolean(KEY_NEXT_WORD_ENABLED, true),
    )

    internal companion object {
        const val PREFS_NAME = "omakey_prediction_prefs"
        const val KEY_NEXT_WORD_ENABLED = "next_word_prediction_enabled"
    }
}
