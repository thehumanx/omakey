package dev.omakey.app.keyboard

import dev.omakey.core.theme.ColorSpec
import dev.omakey.core.theme.CustomThemePreferences
import dev.omakey.core.theme.OmakeyTheme
import dev.omakey.core.theme.Presets
import dev.omakey.core.theme.SystemAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [resolveTheme] is what turns the stored choice into the colours actually drawn. Every case
 * here is a "light phone, dark keyboard" (or the reverse) that a user could hit.
 */
class ThemeResolutionTest {

    private val lightAccent = SystemAccent(accent = ColorSpec(0xFF3355AA), onAccent = ColorSpec(0xFFFFFFFF))
    private val darkAccent = SystemAccent(accent = ColorSpec(0xFFAACCFF), onAccent = ColorSpec(0xFF102040))

    private fun accentFor(dark: Boolean) = if (dark) darkAccent else lightAccent

    /** Stand-in for the device's Material You palette: light or dark as asked. */
    private fun dynamicFor(dark: Boolean): OmakeyTheme =
        (if (dark) Presets.Dark else Presets.Light).copy(id = Presets.Accent.id, name = Presets.Accent.name)

    private fun resolve(
        stored: OmakeyTheme,
        systemDark: Boolean,
        useSystemAccent: Boolean = false,
        hasDynamicPalette: Boolean = true,
    ) = resolveTheme(
        stored = stored,
        useSystemAccent = useSystemAccent,
        systemDark = systemDark,
        dynamicTheme = { dark -> if (hasDynamicPalette) dynamicFor(dark) else null },
        systemAccent = { dark -> if (hasDynamicPalette) accentFor(dark) else null },
    )

    @Test fun `auto follows a light system`() {
        assertFalse(resolve(Presets.Auto, systemDark = false).isDark)
        assertEquals(Presets.Light.keyboardBackground, resolve(Presets.Auto, systemDark = false).keyboardBackground)
    }

    @Test fun `auto follows a dark system`() {
        assertTrue(resolve(Presets.Auto, systemDark = true).isDark)
    }

    @Test fun `accent follows a light system`() {
        assertFalse(resolve(Presets.Accent, systemDark = false).isDark)
    }

    @Test fun `accent follows a dark system`() {
        assertTrue(resolve(Presets.Accent, systemDark = true).isDark)
    }

    @Test fun `accent without a device palette still follows light and dark`() {
        assertSame(Presets.Accent, resolve(Presets.Accent, systemDark = true, hasDynamicPalette = false))
        val light = resolve(Presets.Accent, systemDark = false, hasDynamicPalette = false)
        assertFalse(light.isDark)
        assertEquals(Presets.Accent.id, light.id)
    }

    @Test fun `fixed presets ignore the system setting`() {
        assertSame(Presets.Light, resolve(Presets.Light, systemDark = true))
        assertSame(Presets.Dark, resolve(Presets.Dark, systemDark = false))
    }

    @Test fun `system accent on auto in light mode uses the light-surface tone`() {
        val theme = resolve(Presets.Auto, systemDark = false, useSystemAccent = true)
        assertEquals(lightAccent.accent, theme.accentColor)
        assertEquals(lightAccent.onAccent, theme.keyTextOnAccentColor)
        assertEquals(lightAccent.accent, theme.spacebarAccentColor)
        assertEquals(lightAccent.accent, theme.keyBackgroundPressed)
    }

    @Test fun `system accent on a fixed dark preset uses the dark-surface tone even on a light system`() {
        val theme = resolve(Presets.Dark, systemDark = false, useSystemAccent = true)
        assertEquals(darkAccent.accent, theme.accentColor)
    }

    @Test fun `system accent never overrides a custom theme`() {
        val custom = Presets.Light.copy(id = CustomThemePreferences.ID_PREFIX + "mine", name = "Mine")
        assertSame(custom, resolve(custom, systemDark = false, useSystemAccent = true))
    }

    @Test fun `system accent off leaves the preset accent alone`() {
        assertNull(resolve(Presets.Light, systemDark = false).accentColor)
    }

    @Test fun `the resolved theme is never the stored sentinel`() {
        for (dark in listOf(false, true)) {
            assertFalse(resolve(Presets.Auto, systemDark = dark).id == Presets.Auto.id)
        }
    }
}
