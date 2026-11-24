package dev.omakey.core.settings

import android.content.Context
import dev.omakey.core.feedback.HapticSoundPreferences
import dev.omakey.core.gesture.GesturePreferences
import dev.omakey.core.feedback.SoundChoices
import dev.omakey.core.theme.FontChoices
import dev.omakey.core.theme.FontPreferences
import dev.omakey.core.predict.AutocorrectPreferences
import dev.omakey.core.predict.PredictionPreferences
import dev.omakey.core.theme.Presets
import dev.omakey.core.theme.ThemeRepository
import dev.omakey.core.theme.ThemeSerializer

/**
 * Fresh installs get the reworked defaults (design pass, 2026-11-24: theme Auto, key backgrounds
 * theme from the wallpaper, Figtree, key sound, autocorrect off…); a phone that already had omakey keeps exactly what it
 * had. Every preference loader falls back to its default when nothing is stored, and most people
 * never touch these, so changing the defaults alone would change the keyboard under everyone who
 * updates. Instead, the first start of this version on an existing install writes the previous
 * defaults into any key that isn't set. A stored choice is never overwritten.
 *
 * Runs before any preference class is created (Settings and the keyboard service both call
 * [apply] first thing), and only once: [KEY_ERA] records that it ran.
 */
object InstallDefaults {
    const val PREFS_NAME = "omakey_install"
    const val KEY_ERA = "defaults_era"
    private const val DATABASE_NAME = "omakey.db"

    /** 1: the defaults up to 5.8. 2: the design pass. */
    const val CURRENT_ERA = 2

    const val KEY_THEME_JSON = ThemeRepository.KEY_THEME_JSON

    enum class Action { PIN_PREVIOUS, MARK_ONLY, NOTHING }

    data class Pin(val prefs: String, val key: String, val value: Any)

    fun actionFor(storedEra: Int, ranBefore: Boolean): Action = when {
        storedEra >= CURRENT_ERA -> Action.NOTHING
        ranBefore -> Action.PIN_PREVIOUS
        else -> Action.MARK_ONLY
    }

    /**
     * Whether an older omakey ran here: its database exists, or one of its settings files does.
     * Not the install timestamps — a fresh install that updates before it is ever opened would look
     * like an update and be pinned to defaults it never had.
     */
    fun ranBefore(prefFiles: List<String>, databaseExists: Boolean): Boolean =
        databaseExists || prefFiles.any { it.startsWith("omakey_") && it.removeSuffix(".xml") != PREFS_NAME }

    /** The era-1 defaults: what an existing install was getting when nothing was stored. */
    private val previous: List<Pin> by lazy {
        listOf(
            Pin(PredictionPreferences.PREFS_NAME, PredictionPreferences.KEY_NEXT_WORD_ENABLED, false),
            Pin(AutocorrectPreferences.PREFS_NAME, AutocorrectPreferences.KEY_AUTOCORRECT_ENABLED, true),
            Pin(AutocorrectPreferences.PREFS_NAME, AutocorrectPreferences.KEY_AUTO_CAPITALIZE_ENABLED, false),
            Pin(AutocorrectPreferences.PREFS_NAME, AutocorrectPreferences.KEY_DOUBLE_TAP_SPACE_FOR_PERIOD, false),
            Pin(GesturePreferences.PREFS_NAME, GesturePreferences.KEY_SWIPE_RIGHT_FOR_SPACE, false),
            Pin(HapticSoundPreferences.PREFS_NAME, HapticSoundPreferences.KEY_HAPTIC_STRENGTH, 0.5f),
            Pin(HapticSoundPreferences.PREFS_NAME, HapticSoundPreferences.KEY_SOUND_ENABLED, false),
            Pin(HapticSoundPreferences.PREFS_NAME, HapticSoundPreferences.KEY_SOUND_CHOICE, SoundChoices.CRISP),
            Pin(HapticSoundPreferences.PREFS_NAME, HapticSoundPreferences.KEY_SOUND_VOLUME, 1.0f),
            Pin(FontPreferences.PREFS_NAME, FontPreferences.KEY_FONT_ID, FontChoices.SYSTEM_DEFAULT),
            Pin(ThemeRepository.PREFS_NAME, KEY_THEME_JSON, ThemeSerializer.toJson(Presets.Dark)),
        )
    }

    /** The previous defaults for keys that aren't set yet. */
    fun pinsToWrite(isSet: (prefs: String, key: String) -> Boolean): List<Pin> =
        previous.filterNot { isSet(it.prefs, it.key) }

    fun apply(context: Context) {
        val marker = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
        val ran = ranBefore(
            prefFiles = prefsDir.list()?.toList().orEmpty(),
            databaseExists = context.getDatabasePath(DATABASE_NAME).exists(),
        )
        when (actionFor(marker.getInt(KEY_ERA, 0), ran)) {
            Action.NOTHING -> return
            Action.MARK_ONLY -> Unit
            Action.PIN_PREVIOUS -> pinsToWrite { prefs, key ->
                context.getSharedPreferences(prefs, Context.MODE_PRIVATE).contains(key)
            }.groupBy { it.prefs }.forEach { (prefs, pins) ->
                val editor = context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit()
                pins.forEach { pin ->
                    when (val v = pin.value) {
                        is Boolean -> editor.putBoolean(pin.key, v)
                        is Float -> editor.putFloat(pin.key, v)
                        is String -> editor.putString(pin.key, v)
                    }
                }
                editor.commit()
            }
        }
        marker.edit().putInt(KEY_ERA, CURRENT_ERA).commit()
    }
}
