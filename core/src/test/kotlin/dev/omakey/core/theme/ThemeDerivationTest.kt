package dev.omakey.core.theme

import dev.omakey.core.theme.ThemeDerivation.MIN_TEXT_CONTRAST
import dev.omakey.core.theme.ThemeDerivation.MIN_UI_CONTRAST
import dev.omakey.core.theme.ThemeDerivation.contrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeDerivationTest {

    /** A spread of backgrounds: greys from black to white, and saturated colours at several
     * lightnesses — including the mid-tones where light-vs-dark is a close call. */
    private val backgrounds = buildList {
        for (v in 0..255 step 15) add(ColorSpec(0xFF000000L or (v.toLong() shl 16) or (v.toLong() shl 8) or v.toLong()))
        for (rgb in listOf(0xFF0000L, 0x00FF00L, 0x0000FFL, 0xFF8800L, 0x7A1FA2L, 0x12131AL, 0xFFE4E1L, 0x2E7D32L, 0x808000L, 0x4A90D9L)) {
            add(ColorSpec(0xFF000000L or rgb))
        }
    }

    @Test
    fun `labels on the derived keys are readable`() {
        for (bg in backgrounds) {
            val key = ThemeDerivation.fromBackground(bg).key
            val text = ThemeDerivation.textOn(key)
            assertTrue("bg ${bg.hex()} key ${key.hex()}: ${contrast(key, text)}", contrast(key, text) >= MIN_TEXT_CONTRAST)
        }
    }

    @Test
    fun `accent stands out from the keys and the border from the background`() {
        for (bg in backgrounds) {
            val p = ThemeDerivation.fromBackground(bg)
            assertTrue("accent on ${bg.hex()}", contrast(p.accent, p.key) >= MIN_UI_CONTRAST)
            assertTrue("border on ${bg.hex()}", contrast(p.gridBorder, bg) >= MIN_UI_CONTRAST)
        }
    }

    @Test
    fun `a key tap is visibly different from the key`() {
        for (bg in backgrounds) {
            val p = ThemeDerivation.fromBackground(bg)
            assertTrue("tap on ${bg.hex()}", contrast(p.keyTap, p.key) >= 1.15)
        }
    }

    @Test
    fun `light and dark follow the background`() {
        assertTrue(ThemeDerivation.fromBackground(ColorSpec(0xFF1E1E1E)).isDark)
        assertFalse(ThemeDerivation.fromBackground(ColorSpec(0xFFF2F2F2)).isDark)
    }

    @Test
    fun `spacebar stays neutral like the presets`() {
        val p = ThemeDerivation.fromBackground(ColorSpec(0xFF7A1FA2))
        assertEquals(p.key, p.spacebar)
    }

    private fun ColorSpec.hex() = "#%08X".format(argb)
}
