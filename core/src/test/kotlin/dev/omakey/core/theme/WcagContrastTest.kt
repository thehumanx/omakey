package dev.omakey.core.theme

import dev.omakey.core.theme.ThemeDerivation.MIN_TEXT_CONTRAST
import dev.omakey.core.theme.ThemeDerivation.MIN_UI_CONTRAST
import dev.omakey.core.theme.ThemeDerivation.contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every colour decision the theme engine makes, held to WCAG 2.x: 4.5:1 for text, 3:1 for non-text
 * UI (the accent as an icon tint, grid borders). Swept across the presets and across a grid of
 * backgrounds, keys and accents a user could pick in the editor, because the bugs this guards
 * against lived in exactly the colours nobody had tried.
 */
class WcagContrastTest {

    /** A spread of hues at several lightnesses, plus greys and the extremes. */
    private val sampleColors: List<ColorSpec> = buildList {
        val channels = listOf(0x00L, 0x33L, 0x66L, 0x80L, 0x99L, 0xCCL, 0xFFL)
        for (r in channels) for (g in channels) for (b in channels) {
            add(ColorSpec(0xFF000000L or (r shl 16) or (g shl 8) or b))
        }
    }

    /** Custom themes the editor can produce: every sample background with derived colours, and a
     * key override and accent override from the samples on top. */
    private val customThemes: List<OmakeyTheme> by lazy {
        val overrides = listOf(null) + sampleColors.filterIndexed { i, _ -> i % 23 == 0 }
        buildList {
            for (background in sampleColors.filterIndexed { i, _ -> i % 5 == 0 }) {
                for (key in overrides) {
                    val palette = ThemeDerivation.fromBackground(background, key)
                    add(ThemeDerivation.buildTheme(id = "custom_x", name = "x", background = background, palette = palette))
                }
                // A user-picked accent, which derivation never vetted.
                for (accent in overrides.filterNotNull()) {
                    val palette = ThemeDerivation.fromBackground(background)
                    add(ThemeDerivation.buildTheme(id = "custom_x", name = "x", background = background, palette = palette.copy(accent = accent)))
                }
            }
        }
    }

    private val allThemes: List<OmakeyTheme> by lazy {
        Presets.all + Presets.accentFallback(dark = false) + Presets.accentFallback(dark = true) + customThemes
    }

    private fun OmakeyTheme.textSurfaces() = listOf(
        keyBackground, keySpecialBackground, keyboardBackground, keyBackgroundPressed,
        spacebarAccentColor, suggestionBarBackground,
    )

    @Test fun `labels reach 4,5 to 1 on every surface of every theme`() {
        for (theme in allThemes) for (surface in theme.textSurfaces()) {
            val ratio = contrast(theme.labelOn(surface), surface)
            assertTrue("${theme.name} ${hex(theme.keyTextColor)} on ${hex(surface)}: $ratio", ratio >= MIN_TEXT_CONTRAST)
        }
    }

    @Test fun `labels reach 4,5 to 1 on any colour at all, whatever the text colour`() {
        for (text in sampleColors.filterIndexed { i, _ -> i % 7 == 0 }) for (surface in sampleColors) {
            val theme = Presets.Dark.copy(keyTextColor = text)
            assertTrue(contrast(theme.labelOn(surface), surface) >= MIN_TEXT_CONTRAST)
        }
    }

    @Test fun `the label on the accent reaches 4,5 to 1`() {
        for (theme in allThemes) {
            val ratio = contrast(theme.onAccent, theme.accent)
            assertTrue("${theme.name} accent ${hex(theme.accent)}: $ratio", ratio >= MIN_TEXT_CONTRAST)
        }
    }

    @Test fun `a supplied on-accent tone that fails is not used`() {
        val theme = Presets.Light.copy(accentColor = ColorSpec(0xFF808080), keyTextOnAccentColor = ColorSpec(0xFF8A8A8A))
        assertTrue(contrast(theme.onAccent, theme.accent) >= MIN_TEXT_CONTRAST)
        assertTrue(contrast(theme.labelOn(ColorSpec(0xFF808080)), ColorSpec(0xFF808080)) >= MIN_TEXT_CONTRAST)
    }

    @Test fun `the accent as an icon tint reaches 3 to 1 on the surface under it`() {
        for (theme in allThemes) for (surface in listOf(theme.keyBackground, theme.keyboardBackground, theme.suggestionBarBackground)) {
            val ratio = contrast(theme.accentOn(surface), surface)
            assertTrue("${theme.name} accent ${hex(theme.accent)} on ${hex(surface)}: $ratio", ratio >= MIN_UI_CONTRAST)
        }
    }

    @Test fun `an accent that already stands out is left alone`() {
        for (theme in Presets.all) {
            assertEquals(theme.name, theme.accent, theme.accentOn(theme.keyBackground))
        }
    }

    @Test fun `faded labels stay readable and are still faded`() {
        for (theme in allThemes) for (surface in theme.textSurfaces()) {
            val faded = theme.fadedLabelOn(surface, alpha = 0.45f)
            val full = theme.labelOn(surface)
            assertTrue("${theme.name} faded on ${hex(surface)}", contrast(faded, surface) >= MIN_TEXT_CONTRAST)
            assertTrue(contrast(faded, surface) <= contrast(full, surface) + 1e-9)
        }
        // Dark has room to fade: the result really is dimmer than the label.
        val dark = Presets.Dark
        assertTrue(contrast(dark.fadedLabelOn(dark.keyboardBackground, 0.6f), dark.keyboardBackground) < contrast(dark.keyTextColor, dark.keyboardBackground))
    }

    @Test fun `a faded decoration may go down to 3 to 1`() {
        for (theme in allThemes) for (surface in theme.textSurfaces()) {
            val faded = theme.fadedLabelOn(surface, alpha = 0.3f, floor = MIN_UI_CONTRAST)
            assertTrue(contrast(faded, surface) >= MIN_UI_CONTRAST)
        }
    }

    @Test fun `derived grid borders reach 3 to 1 on the background`() {
        for (theme in customThemes) {
            assertTrue(contrast(theme.gridBorderColor, theme.keyboardBackground) >= MIN_UI_CONTRAST)
        }
    }

    @Test fun `presets keep their own label colour`() {
        // The floor is a safety net, not a restyling: hand-made palettes already pass.
        for (theme in Presets.all) for (surface in theme.textSurfaces()) {
            assertEquals("${theme.name} on ${hex(surface)}", theme.keyTextColor, theme.labelOn(surface))
        }
    }

    @Test fun `custom theme darkness follows the background, which is the surface in grid mode`() {
        val light = ColorSpec(0xFFF5F5F5)
        val palette = ThemeDerivation.fromBackground(light, keyOverride = ColorSpec(0xFF202020))
        assertEquals(false, ThemeDerivation.buildTheme("custom_x", "x", light, palette).isDark)
    }

    @Test fun `the accent fallback follows light and dark`() {
        assertEquals(false, Presets.accentFallback(dark = false).isDark)
        assertEquals(true, Presets.accentFallback(dark = true).isDark)
        assertEquals(Presets.Accent.id, Presets.accentFallback(dark = false).id)
    }

    @Test fun `the home-row stripe tints toward the label, on light and dark`() {
        assertTrue(ThemeDerivation.homeRowStripe(isDark = true).argb and 0xFFFFFF == 0xFFFFFFL)
        assertTrue(ThemeDerivation.homeRowStripe(isDark = false).argb and 0xFFFFFF == 0L)
    }

    private fun hex(c: ColorSpec) = "#%08X".format(c.argb)
}
