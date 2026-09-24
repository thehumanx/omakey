package dev.omakey.core.locale

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.StateFlow

data class LocaleSettings(
    /** Languages the user switches between, in switching order. Never empty. */
    val enabledIds: List<String> = listOf(KeyboardLocale.Default.id),
    /** The language chosen last; the keyboard opens in it. */
    val activeId: String = KeyboardLocale.Default.id,
    /** Chosen letter layout per language id, for languages that offer a choice. */
    val layoutChoices: Map<String, String> = emptyMap(),
)

/** Which languages are enabled, and which one is active. */
class LocalePreferences(context: Context) {
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val settings: StateFlow<LocaleSettings> = store.settings

    fun close() = store.close()

    fun setEnabled(ids: List<String>) {
        val cleaned = ids.distinct().ifEmpty { listOf(KeyboardLocale.Default.id) }
        store.edit { putString(KEY_ENABLED, cleaned.joinToString(SEPARATOR)) }
    }

    fun setLayoutChoice(localeId: String, layoutId: String) {
        val choices = settings.value.layoutChoices + (localeId to layoutId)
        store.edit { putString(KEY_LAYOUTS, choices.entries.joinToString(SEPARATOR) { "${it.key}=${it.value}" }) }
    }

    fun setActive(id: String) {
        store.edit { putString(KEY_ACTIVE, id) }
    }

    private fun load(prefs: SharedPreferences): LocaleSettings {
        val enabled = prefs.getString(KEY_ENABLED, null)
            ?.split(SEPARATOR)?.filter { it.isNotBlank() }
            ?.ifEmpty { null }
            ?: listOf(KeyboardLocale.Default.id)
        return LocaleSettings(
            enabledIds = enabled,
            activeId = prefs.getString(KEY_ACTIVE, null) ?: enabled.first(),
            layoutChoices = prefs.getString(KEY_LAYOUTS, null).orEmpty().split(SEPARATOR)
                .mapNotNull { entry -> entry.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap(),
        )
    }

    private companion object {
        const val PREFS_NAME = "omakey_locale_prefs"
        const val KEY_ENABLED = "enabled_locales"
        const val KEY_ACTIVE = "active_locale"
        const val KEY_LAYOUTS = "layout_choices"
        const val SEPARATOR = ","
    }
}
