package dev.omakey.core.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtypeSpecTest {

    private val russian = KeyboardLocale.EnUs.copy(
        id = "ru_RU", displayName = "Russian", nativeName = "Русский",
        profile = LanguageProfile(script = Script.CYRILLIC, hasCase = true, capitalizeAfter = setOf('.'), doubleSpaceInserts = '.', punctuationCycle = listOf('.')),
    )

    @Test
    fun `a language becomes a subtype the system spellchecker understands`() {
        val spec = russian.subtypeSpec()
        assertEquals("ru_RU", spec.localeId)
        assertEquals("ru-RU", spec.languageTag)
        assertFalse(spec.asciiCapable)
        assertEquals("Русский", spec.label)
        assertTrue(KeyboardLocale.EnUs.subtypeSpec().asciiCapable)
    }

    /** Frozen forever: the system remembers enabled subtypes by these ids, so a change would quietly
     * disable every user's languages in the system switcher. */
    @Test
    fun `subtype ids never change`() {
        assertEquals(SubtypeSpec.idFor("en_US"), KeyboardLocale.EnUs.subtypeSpec().subtypeId)
        assertEquals(-859137895, SubtypeSpec.idFor("en_US"))
        assertEquals(1463482978, SubtypeSpec.idFor("ru_RU"))
    }

    @Test
    fun `a system subtype maps back to an enabled language`() {
        val enabled = listOf(KeyboardLocale.EnUs, russian)
        assertEquals("ru_RU", localeIdForLanguageTag("ru-RU", enabled))
        assertEquals("ru_RU", localeIdForLanguageTag("ru", enabled))
        assertEquals("en_US", localeIdForLanguageTag("en-US", enabled))
        assertNull(localeIdForLanguageTag("fr-FR", enabled))
        assertNull(localeIdForLanguageTag("", enabled))
    }
}
