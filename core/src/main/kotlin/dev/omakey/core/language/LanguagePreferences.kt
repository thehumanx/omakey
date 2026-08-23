package dev.omakey.core.language

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LanguageSettings(
    val enabledLanguageIds: Set<String> = setOf(Languages.EnglishUS.id),
    val activeLanguageId: String = Languages.EnglishUS.id,
    /** Which input method (e.g. Nepali: Romanized vs Traditional) each language currently uses —
     * always populated for every entry in [Languages.all], not just enabled ones, so switching a
     * language on remembers whatever it was last set to. */
    val inputMethodByLanguage: Map<String, String> = mapOf(Languages.EnglishUS.id to Languages.EnglishUS.defaultInputMethod.id),
)

/** Persists which languages are enabled, which one is currently active for typing, and which
 * input method each language is using — same SharedPreferences + cross-instance-sync pattern as
 * [dev.omakey.core.gesture.GesturePreferences]/[dev.omakey.core.layout.LayoutPreferences] (see
 * either's doc for why the change listener is necessary, not optional: Settings and the IME each
 * construct their own instance).
 *
 * English can never be disabled — it's the only language guaranteed to always have a working
 * layout, dictionary, and prediction data, and is the fallback every other language's removal
 * lands on (see [setEnabled]). */
class LanguagePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<LanguageSettings> = _settings

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settings.value = load()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun setEnabled(languageId: String, enabled: Boolean) {
        if (languageId == Languages.EnglishUS.id && !enabled) return
        if (Languages.byId(languageId) == null) return
        update { current ->
            val nextEnabled = if (enabled) current.enabledLanguageIds + languageId else current.enabledLanguageIds - languageId
            // Disabling the currently active language falls back to English rather than leaving
            // the keyboard on a language it can no longer type in.
            val nextActive = if (!enabled && current.activeLanguageId == languageId) Languages.EnglishUS.id else current.activeLanguageId
            current.copy(enabledLanguageIds = nextEnabled, activeLanguageId = nextActive)
        }
    }

    fun setActiveLanguage(languageId: String) {
        if (Languages.byId(languageId) == null) return
        update { current ->
            // Switching to a language implicitly enables it too — there's no useful state where
            // the active language isn't also an enabled one.
            current.copy(activeLanguageId = languageId, enabledLanguageIds = current.enabledLanguageIds + languageId)
        }
    }

    fun setInputMethodForLanguage(languageId: String, inputMethodId: String) {
        val language = Languages.byId(languageId) ?: return
        if (language.inputMethods.none { it.id == inputMethodId }) return
        update { it.copy(inputMethodByLanguage = it.inputMethodByLanguage + (languageId to inputMethodId)) }
    }

    private fun update(transform: (LanguageSettings) -> LanguageSettings) {
        val next = transform(_settings.value)
        val editor = prefs.edit()
            .putStringSet(KEY_ENABLED, next.enabledLanguageIds)
            .putString(KEY_ACTIVE, next.activeLanguageId)
        // SharedPreferences has no native map type — each language's chosen input method is
        // stored under its own flat key instead, bounded by the static Languages.all list (no
        // unbounded key growth to worry about).
        for (language in Languages.all) {
            val methodId = next.inputMethodByLanguage[language.id] ?: continue
            editor.putString(inputMethodKey(language.id), methodId)
        }
        editor.apply()
        _settings.value = next
    }

    private fun load(): LanguageSettings {
        val storedEnabled = prefs.getStringSet(KEY_ENABLED, null)
            ?.filter { Languages.byId(it) != null }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
        val enabled = storedEnabled ?: setOf(Languages.EnglishUS.id)
        val active = prefs.getString(KEY_ACTIVE, null)?.takeIf { it in enabled } ?: Languages.EnglishUS.id
        val inputMethods = Languages.all.associate { language ->
            val stored = prefs.getString(inputMethodKey(language.id), null)
            val resolved = language.inputMethods.find { it.id == stored } ?: language.defaultInputMethod
            language.id to resolved.id
        }
        return LanguageSettings(
            enabledLanguageIds = enabled,
            activeLanguageId = active,
            inputMethodByLanguage = inputMethods,
        )
    }

    private fun inputMethodKey(languageId: String) = "$KEY_INPUT_METHOD_PREFIX$languageId"

    private companion object {
        const val PREFS_NAME = "omakey_language_prefs"
        const val KEY_ENABLED = "enabled_language_ids"
        const val KEY_ACTIVE = "active_language_id"
        const val KEY_INPUT_METHOD_PREFIX = "input_method_"
    }
}
