package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapPreviewStyleTest {

    @Test
    fun `a stored style is read back by name`() {
        for (style in TapPreviewStyle.entries) {
            assertEquals(style, TapPreviewStyle.fromStored(style.name, legacyShown = true))
            assertEquals(style, TapPreviewStyle.fromStored(style.name, legacyShown = false))
        }
    }

    @Test
    fun `phones from before the setting keep their on or off choice`() {
        assertEquals(TapPreviewStyle.CLASSIC, TapPreviewStyle.fromStored(null, legacyShown = true))
        assertEquals(TapPreviewStyle.OFF, TapPreviewStyle.fromStored(null, legacyShown = false))
    }

    @Test
    fun `an unknown style falls back instead of throwing`() {
        assertEquals(TapPreviewStyle.CLASSIC, TapPreviewStyle.fromStored("FUTURE_STYLE", legacyShown = true))
        assertEquals(TapPreviewStyle.OFF, TapPreviewStyle.fromStored("FUTURE_STYLE", legacyShown = false))
    }

    @Test
    fun `only the bubble styles draw a bubble`() {
        assertTrue(TapPreviewStyle.CLASSIC.showsBubble)
        assertTrue(TapPreviewStyle.SLIM.showsBubble)
        assertFalse(TapPreviewStyle.LIFT.showsBubble)
        assertFalse(TapPreviewStyle.OFF.showsBubble)
    }
}
