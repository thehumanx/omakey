package dev.omakey.core.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageFlagTest {
    @Test
    fun `region becomes a flag`() {
        assertEquals("🇺🇸", flagEmoji("en_US"))
        assertEquals("🇳🇵", flagEmoji("ne_NP"))
        assertEquals("🇧🇷", flagEmoji("pt_BR"))
        assertEquals("🇷🇺", flagEmoji("ru_RU"))
    }

    @Test
    fun `no region, no flag`() {
        assertNull(flagEmoji("en"))
        assertNull(flagEmoji("sr_Latn"))
        assertNull(flagEmoji(""))
    }
}
