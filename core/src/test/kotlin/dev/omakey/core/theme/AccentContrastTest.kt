package dev.omakey.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accent is what marks "on" (caps lock, active shift, open panel buttons, selected tiles), so
 * it has to be visible against the keys and its label visible against it. Caps lock used to draw
 * its icon in the same colour as its own fill on the Light preset — this is that bug, pinned.
 */
class AccentContrastTest {

    private val presets = listOf(Presets.Light, Presets.Dark, Presets.Accent)

    @Test
    fun `the label on the accent is readable for every preset`() {
        presets.forEach { theme ->
            val ratio = ThemeDerivation.contrast(theme.accent, theme.onAccent)
            assertTrue("${theme.name}: on-accent $ratio", ratio >= ThemeDerivation.MIN_TEXT_CONTRAST)
        }
    }

    @Test
    fun `the accent stands out from the keys it sits among`() {
        presets.forEach { theme ->
            val ratio = ThemeDerivation.contrast(theme.accent, theme.keyBackground)
            assertTrue("${theme.name}: accent vs key $ratio", ratio >= ThemeDerivation.MIN_UI_CONTRAST)
        }
    }

    @Test
    fun `a theme saved before the accent existed falls back to its text colour`() {
        val old = Presets.Dark.copy(accentColor = null)
        assertEquals(old.keyTextColor, old.accent)
        val json = ThemeSerializer.toJson(Presets.Dark).replace(Regex(",\"accentColor\":\\{[^}]*\\}"), "")
        assertEquals(null, ThemeSerializer.fromJson(json).accentColor)
    }

    @Test
    fun `a system on-accent tone wins when the palette supplies one`() {
        val theme = Presets.Light.copy(accentColor = ColorSpec(0xFF6750A4), keyTextOnAccentColor = ColorSpec(0xFFFFFFFF))
        assertEquals(ColorSpec(0xFFFFFFFF), theme.onAccent)
    }
}
