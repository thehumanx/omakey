package dev.omakey.core.theme

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.StateFlow

/** Known font choices. The actual FontFamily objects live in the app module (they reference
 * R.font resource ids, which core has no access to) — this just persists the chosen id. */
object FontChoices {
    const val SYSTEM_DEFAULT = "system_default"
    const val POPPINS_BOLD = "poppins_bold"
    const val FIGTREE_BOLD = "figtree_bold"
    const val SOLWAY = "solway"
    const val ALEO = "aleo"
}

/** Persists the user's chosen keyboard font, same SharedPreferences + cross-instance-sync pattern
 * as [ThemeRepository]/[dev.omakey.core.layout.LayoutPreferences] — the Settings Activity and the
 * IME service each construct their own instance, so the registered listener is what keeps an
 * already-open keyboard in sync with a change made from Settings. */
class FontPreferences(context: Context) {
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val fontId: StateFlow<String> = store.settings

    fun setFont(fontId: String) = store.edit { putString(KEY_FONT_ID, fontId) }

    fun close() = store.close()

    /** The default-fallback expression used to be written out twice, once for the initial read and
     * once in the change listener — two copies of one rule, which is how they drift apart. */
    private fun load(prefs: SharedPreferences) =
        prefs.getString(KEY_FONT_ID, FontChoices.SYSTEM_DEFAULT) ?: FontChoices.SYSTEM_DEFAULT

    private companion object {
        const val PREFS_NAME = "omakey_font_prefs"
        const val KEY_FONT_ID = "font_id"
    }
}
