package dev.omakey.app.keyboard

import dev.omakey.core.theme.Presets
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePresetChoicesTest {

    @Test
    fun `Android 12 and newer offer the wallpaper-coloured Accent preset`() =
        assertEquals(listOf(Presets.Light, Presets.Dark, Presets.Auto, Presets.Accent), themePresetChoices(sdk = 31, currentId = Presets.Dark.id))

    @Test
    fun `older Android has no wallpaper colours, so Accent isn't offered`() =
        assertEquals(listOf(Presets.Light, Presets.Dark, Presets.Auto), themePresetChoices(sdk = 30, currentId = Presets.Dark.id))

    @Test
    fun `someone already on Accent keeps seeing it, so their choice isn't orphaned`() =
        assertEquals(listOf(Presets.Light, Presets.Dark, Presets.Auto, Presets.Accent), themePresetChoices(sdk = 26, currentId = Presets.Accent.id))
}
