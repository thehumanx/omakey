package dev.omakey.core.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Known font choices. The actual FontFamily objects live in the app module (they reference
 * R.font resource ids, which core has no access to) — this just persists the chosen id. */
object FontChoices {
    const val SYSTEM_DEFAULT = "system_default"
    const val POPPINS_BOLD = "poppins_bold"
    const val FIGTREE_BOLD = "figtree_bold"
}

/** Persists the user's chosen keyboard font, same SharedPreferences + cross-instance-sync pattern
 * as [ThemeRepository]/[dev.omakey.core.layout.LayoutPreferences] — the Settings Activity and the
 * IME service each construct their own instance, so the registered listener is what keeps an
 * already-open keyboard in sync with a change made from Settings. */
class FontPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _fontId = MutableStateFlow(prefs.getString(KEY_FONT_ID, FontChoices.SYSTEM_DEFAULT) ?: FontChoices.SYSTEM_DEFAULT)
    val fontId: StateFlow<String> = _fontId

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _fontId.value = prefs.getString(KEY_FONT_ID, FontChoices.SYSTEM_DEFAULT) ?: FontChoices.SYSTEM_DEFAULT
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun setFont(fontId: String) {
        prefs.edit().putString(KEY_FONT_ID, fontId).apply()
        _fontId.value = fontId
    }

    private companion object {
        const val PREFS_NAME = "omakey_font_prefs"
        const val KEY_FONT_ID = "font_id"
    }
}
