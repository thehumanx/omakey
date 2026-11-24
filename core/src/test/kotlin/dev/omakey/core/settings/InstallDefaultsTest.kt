package dev.omakey.core.settings

import dev.omakey.core.feedback.HapticSoundPreferences
import dev.omakey.core.gesture.GesturePreferences
import dev.omakey.core.predict.AutocorrectPreferences
import dev.omakey.core.predict.PredictionPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallDefaultsTest {

    @Test fun `a fresh install keeps the new defaults and is marked`() =
        assertEquals(InstallDefaults.Action.MARK_ONLY, InstallDefaults.actionFor(storedEra = 0, ranBefore = false))

    @Test fun `an update from an older version pins what it had`() =
        assertEquals(InstallDefaults.Action.PIN_PREVIOUS, InstallDefaults.actionFor(storedEra = 0, ranBefore = true))

    @Test fun `once marked nothing happens again`() {
        assertEquals(InstallDefaults.Action.NOTHING, InstallDefaults.actionFor(InstallDefaults.CURRENT_ERA, ranBefore = true))
        assertEquals(InstallDefaults.Action.NOTHING, InstallDefaults.actionFor(InstallDefaults.CURRENT_ERA, ranBefore = false))
    }

    // Install timestamps can't tell: a fresh install that updates before it's ever opened looks
    // like an update. What counts is evidence omakey ran: its database or a settings file.
    @Test fun `nothing on disk means it never ran, even after an update`() =
        assertEquals(false, InstallDefaults.ranBefore(prefFiles = emptyList(), databaseExists = false))

    @Test fun `a database means it ran`() =
        assertEquals(true, InstallDefaults.ranBefore(prefFiles = emptyList(), databaseExists = true))

    @Test fun `an omakey settings file means it ran`() =
        assertEquals(true, InstallDefaults.ranBefore(prefFiles = listOf("omakey_layout_prefs.xml"), databaseExists = false))

    @Test fun `the era marker itself and other files don't count`() =
        assertEquals(false, InstallDefaults.ranBefore(prefFiles = listOf("omakey_install.xml", "WebViewChromiumPrefs.xml"), databaseExists = false))

    @Test fun `a stored choice is never overwritten`() {
        val stored = setOf(AutocorrectPreferences.PREFS_NAME to AutocorrectPreferences.KEY_AUTOCORRECT_ENABLED)
        val pins = InstallDefaults.pinsToWrite { prefs, key -> (prefs to key) in stored }
        assertTrue(pins.none { it.key == AutocorrectPreferences.KEY_AUTOCORRECT_ENABLED })
    }

    @Test fun `an updating user keeps every old default`() {
        val pins = InstallDefaults.pinsToWrite { _, _ -> false }.associate { it.key to it.value }
        assertEquals(false, pins[PredictionPreferences.KEY_NEXT_WORD_ENABLED])
        assertEquals(false, pins[AutocorrectPreferences.KEY_AUTO_CAPITALIZE_ENABLED])
        assertEquals(false, pins[AutocorrectPreferences.KEY_DOUBLE_TAP_SPACE_FOR_PERIOD])
        assertEquals(true, pins[AutocorrectPreferences.KEY_AUTOCORRECT_ENABLED])
        assertEquals(false, pins[GesturePreferences.KEY_SWIPE_RIGHT_FOR_SPACE])
        assertEquals(0.5f, pins[HapticSoundPreferences.KEY_HAPTIC_STRENGTH])
        assertEquals(false, pins[HapticSoundPreferences.KEY_SOUND_ENABLED])
        assertEquals(dev.omakey.core.feedback.SoundChoices.CRISP, pins[HapticSoundPreferences.KEY_SOUND_CHOICE])
        assertEquals(1.0f, pins[HapticSoundPreferences.KEY_SOUND_VOLUME])
        assertEquals(dev.omakey.core.theme.FontChoices.SYSTEM_DEFAULT, pins[dev.omakey.core.theme.FontPreferences.KEY_FONT_ID])
        assertTrue(pins.containsKey(InstallDefaults.KEY_THEME_JSON))
    }

    @Test fun `the new defaults are what the developer chose`() {
        val layout = dev.omakey.core.layout.LayoutSettings()
        assertEquals(false, layout.showKeyBackgrounds)
        assertEquals(true, layout.showMiddleRowStripe)
        assertEquals(true, layout.alwaysShowUppercaseLetters)
        assertEquals(false, layout.showSecondarySymbols)
        assertEquals(true, dev.omakey.core.predict.PredictionSettings().nextWordPredictionEnabled)
        val ac = dev.omakey.core.predict.AutocorrectSettings()
        assertEquals(false, ac.autocorrectEnabled)
        assertEquals(true, ac.autoCapitalizeEnabled)
        assertEquals(true, ac.doubleTapSpaceForPeriod)
        assertEquals(false, ac.autoSpaceAfterPunctuation)
        val sound = dev.omakey.core.feedback.HapticSoundSettings()
        assertEquals(true, sound.soundEnabled)
        assertEquals(0.5f, sound.soundVolume)
        assertEquals(dev.omakey.core.feedback.SoundChoices.DEEP, sound.soundChoice)
        assertEquals(dev.omakey.core.theme.FontChoices.FIGTREE_BOLD, dev.omakey.core.theme.FontPreferences.DEFAULT_FONT)
    }

    @Test fun `the theme follows the wallpaper where the phone has one, else the phone's mode`() {
        assertEquals(dev.omakey.core.theme.Presets.Accent.id, dev.omakey.core.theme.ThemeRepository.defaultThemeFor(31).id)
        assertEquals(dev.omakey.core.theme.Presets.Auto.id, dev.omakey.core.theme.ThemeRepository.defaultThemeFor(30).id)
    }
}
