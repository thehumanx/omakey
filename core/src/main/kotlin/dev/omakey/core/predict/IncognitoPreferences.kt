package dev.omakey.core.predict

import android.content.Context
import android.content.SharedPreferences
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
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<LearningSettings> = _settings

    private val _incognito = MutableStateFlow(false)

    /** True while nothing typed should be remembered — either because the user asked, or because
     * the focused field is a password. */
    val incognito: StateFlow<Boolean> = _incognito

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settings.value = load()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun setImplicitLearningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IMPLICIT_LEARNING, enabled).apply()
        _settings.value = _settings.value.copy(implicitLearningEnabled = enabled)
    }

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
    fun shouldLearn(): Boolean = _settings.value.implicitLearningEnabled && !_incognito.value

    private fun load() = LearningSettings(
        implicitLearningEnabled = prefs.getBoolean(KEY_IMPLICIT_LEARNING, true),
    )

    private companion object {
        const val PREFS_NAME = "omakey_learning_prefs"
        const val KEY_IMPLICIT_LEARNING = "implicit_learning_enabled"
    }
}
