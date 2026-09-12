package dev.omakey.core.predict

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LearningSettings(
    /**
     * Whether ordinary typing teaches the keyboard new words. On by default — this is what makes
     * the keyboard adapt to names, slang and jargon the bundled corpus has never seen.
     *
     * Safe to have on only because learning a word and *trusting* it are separated: see
     * [PersonalLanguageModel]. An earlier version conflated them, and every uncaught typo became
     * permanently immune to correction.
     */
    val implicitLearningEnabled: Boolean = true,
)

/**
 * Learning policy, plus the transient "don't remember any of this" switch.
 *
 * Two distinct things, deliberately not merged:
 *
 *  - [settings] is the persisted preference — "should this keyboard learn from me at all".
 *  - [incognito] is **session state**, not a preference. It is never written to disk, and it resets
 *    when the keyboard is dismissed. Persisting it would be a trap: someone who enabled it to type
 *    one password would silently stop getting personalisation forever, with no obvious cause.
 *
 * Incognito is also engaged automatically for password and no-suggestion fields (see
 * `KeyboardViewModel.resetForNewField`), which is the case that matters most and the one users
 * would never think to toggle by hand.
 *
 * Same SharedPreferences + cross-instance-sync pattern as the other `*Preferences` classes — see
 * `HapticSoundPreferences` for why the change listener is load-bearing rather than decorative: the
 * Settings Activity and the IME service each construct their own instance, and only the listener
 * keeps them in sync live.
 */
class IncognitoPreferences(context: Context) {
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val settings: StateFlow<LearningSettings> = store.settings

    private val _incognito = MutableStateFlow(false)

    /** True while nothing typed should be remembered — either because the user asked, or because
     * the focused field is a password. */
    val incognito: StateFlow<Boolean> = _incognito

    fun setImplicitLearningEnabled(enabled: Boolean) =
        store.edit { putBoolean(KEY_IMPLICIT_LEARNING, enabled) }

    fun close() = store.close()

    /** Manual toggle, from the keyboard's own toolbar. */
    fun setIncognito(enabled: Boolean) {
        _incognito.value = enabled
    }

    /** Called on every field change. [sensitiveField] forces incognito on; otherwise the manual
     * choice is cleared, so it lasts for the field it was made in rather than indefinitely. */
    fun onFieldChanged(sensitiveField: Boolean) {
        _incognito.value = sensitiveField
    }

    /** Whether a word typed right now should be remembered at all. */
    fun shouldLearn(): Boolean = store.value.implicitLearningEnabled && !_incognito.value

    private fun load(prefs: SharedPreferences) = LearningSettings(
        implicitLearningEnabled = prefs.getBoolean(KEY_IMPLICIT_LEARNING, true),
    )

    private companion object {
        const val PREFS_NAME = "omakey_learning_prefs"
        const val KEY_IMPLICIT_LEARNING = "implicit_learning_enabled"
    }
}
