package dev.omakey.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OmakeyTheme.labelOn] — the readability floor that keeps a system accent from swallowing key
 * labels.
 *
 * The property that matters most is the negative one: this must be inert for themes whose colours
 * were chosen together, or it stops being a safety net and becomes a restyling nobody asked for.
 */
class LabelContrastTest {

    @Test
    fun `presets are unaffected on every surface they actually use`() {
        for (theme in Presets.all) {
            val surfaces = listOf(
                theme.keyBackground,
                theme.keySpecialBackground,
                theme.keyboardBackground,
                theme.keyBackgroundPressed,
                theme.spacebarAccentColor,
            )
            for (surface in surfaces) {
                assertEquals(
                    "${theme.name} should render labels in its own keyTextColor",
                    theme.keyTextColor,
                    theme.labelOn(surface),
                )
            }
        }
    }

    @Test
    fun `a light accent on a dark theme stops the label vanishing`() {
        // The concrete regression risk in reading the accent tone per theme brightness: a dark
        // theme now gets system_accent1_200, which is a pale tone, and Dark's keyTextColor is
        // near-white. Without this, a pressed key would be white text on a pale background.
        val paleAccent = ColorSpec(0xFFB8C8F0)
        val theme = Presets.Dark.copy(keyBackgroundPressed = paleAccent)

        val label = theme.labelOn(paleAccent)

        assertTrue("label must be darker than the pale accent", label.luminance < paleAccent.luminance)
    }

    @Test
    fun `the palette's own on-accent tone is preferred over an inferred one`() {
        val paleAccent = ColorSpec(0xFFB8C8F0)
        val onAccent = ColorSpec(0xFF1B2A4A)
        val theme = Presets.Dark.copy(keyBackgroundPressed = paleAccent, keyTextOnAccentColor = onAccent)

        assertEquals(onAccent, theme.labelOn(paleAccent))
    }

    @Test
    fun `an inferred label is picked off the background's own brightness`() {
        val noOnAccent = Presets.Dark.copy(keyTextOnAccentColor = null)

        assertEquals(ColorSpec(0xFF000000), noOnAccent.labelOn(ColorSpec(0xFFF0F0F0)))
        assertEquals(ColorSpec(0xFFFFFFFF), Presets.Light.copy(keyTextOnAccentColor = null).labelOn(ColorSpec(0xFF101010)))
    }

    @Test
    fun `luminance ranks black below grey below white`() {
        assertTrue(ColorSpec(0xFF000000).luminance < ColorSpec(0xFF808080).luminance)
        assertTrue(ColorSpec(0xFF808080).luminance < ColorSpec(0xFFFFFFFF).luminance)
        assertEquals(0f, ColorSpec(0xFF000000).luminance, 0.001f)
        assertEquals(1f, ColorSpec(0xFFFFFFFF).luminance, 0.001f)
    }

    @Test
    fun `a theme persisted before the on-accent field existed still deserializes`() {
        val json = ThemeSerializer.toJson(Presets.Dark)
        val withoutField = json.replace(Regex(",?\"keyTextOnAccentColor\":\\{[^}]*\\}"), "")

        val restored = ThemeSerializer.fromJson(withoutField)

        assertEquals(null, restored.keyTextOnAccentColor)
        assertEquals(Presets.Dark.keyTextColor, restored.labelOn(Presets.Dark.keyBackground))
    }
}
