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
        )
    }

    private companion object {
        const val PREFS_NAME = "omakey_locale_prefs"
        const val KEY_ENABLED = "enabled_locales"
        const val KEY_ACTIVE = "active_locale"
        const val SEPARATOR = ","
    }
}
