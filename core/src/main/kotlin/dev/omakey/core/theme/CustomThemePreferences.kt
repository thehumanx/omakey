package dev.omakey.core.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persists the user's own "build your own theme" creations — a plain list of [OmakeyTheme]s (a
 * custom theme isn't a distinct data model, just another [OmakeyTheme] with a generated
 * `custom_<uuid>` id), one SharedPreferences JSON blob for the whole list, same lightweight
 * pattern as [ThemeRepository] itself. A dedicated Room table would only pay off at a scale users
 * of a single keyboard's custom-theme list are never going to reach. */
class CustomThemePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val listSerializer = ListSerializer(OmakeyTheme.serializer())

    private val _themes = MutableStateFlow(load())
    val themes: StateFlow<List<OmakeyTheme>> = _themes

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _themes.value = load()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    /** Inserts [theme], or replaces the existing entry with the same [OmakeyTheme.id] if one
     * already exists (editing a previously-saved custom theme keeps its id and its position). */
    fun save(theme: OmakeyTheme) {
        val current = _themes.value
        val next = if (current.any { it.id == theme.id }) {
            current.map { if (it.id == theme.id) theme else it }
        } else {
            current + theme
        }
        persist(next)
    }

    fun delete(id: String) {
        persist(_themes.value.filterNot { it.id == id })
    }

    private fun persist(themes: List<OmakeyTheme>) {
        prefs.edit().putString(KEY_THEMES_JSON, json.encodeToString(listSerializer, themes)).apply()
        _themes.value = themes
    }

    private fun load(): List<OmakeyTheme> {
        val raw = prefs.getString(KEY_THEMES_JSON, null) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    companion object {
        /** Custom theme ids are generated with this prefix so the rest of the app (e.g. deciding
         * whether a theme row should show edit/delete affordances) can tell a custom theme apart
         * from a bundled preset without needing a separate `isCustom` flag on [OmakeyTheme]
         * itself. */
        const val ID_PREFIX = "custom_"
        private const val PREFS_NAME = "omakey_custom_theme_prefs"
        private const val KEY_THEMES_JSON = "custom_themes_json"
    }
}
