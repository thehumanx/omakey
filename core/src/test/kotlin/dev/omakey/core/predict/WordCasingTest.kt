package dev.omakey.core.predict

import dev.omakey.core.locale.LanguageProfile
import dev.omakey.core.locale.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordCasingTest {

    private val saved = mapOf("bibek" to "Bibek", "iphone" to "iPhone")
    private val spellingOf: (String) -> String? = { saved[it.lowercase()] }
    private val english = LanguageProfile.English
    private val withI = LanguageProfile(
        script = Script.LATIN, hasCase = true, capitalizeAfter = setOf('.'),
        doubleSpaceInserts = '.', punctuationCycle = listOf('.'), alwaysCapitalized = mapOf("i" to "I"),
    )
    private val russian = LanguageProfile(
        script = Script.CYRILLIC, hasCase = true, capitalizeAfter = setOf('.'),
        doubleSpaceInserts = '.', punctuationCycle = listOf('.'),
    )

    private fun typed(typed: String, chosen: String) = WordCasing.forTyped(typed, chosen, english, spellingOf)

    @Test
    fun `a saved spelling wins over how the word was typed`() {
        assertEquals("Bibek", typed("bibek", "bibek"))
        assertEquals("Bibek", typed("Bibek", "bibek"))
        assertEquals("iPhone", typed("iphone", "iphone"))
        // A sentence start doesn't turn "iPhone" into "IPhone".
        assertEquals("iPhone", typed("Iphone", "iphone"))
    }

    @Test
    fun `all caps typed stays all caps`() {
        assertEquals("BIBEK", typed("BIBEK", "bibek"))
        assertEquals("IPHONE", typed("IPHONE", "iphone"))
    }

    @Test
    fun `words without a saved spelling follow what was typed`() {
        assertEquals("hello", typed("hello", "hello"))
        assertEquals("Hello", typed("Hello", "hello"))
        assertEquals("HELLO", typed("HELLO", "hello"))
    }

    @Test
    fun `fixed spelling is the user's first, then the language's own`() {
        assertEquals("Bibek", WordCasing.fixedSpelling("bibek", english, spellingOf))
        assertEquals("I", WordCasing.fixedSpelling("i", withI, spellingOf))
        assertEquals(
            "the user's spelling beats the language's",
            "i", WordCasing.fixedSpelling("i", withI, { if (it == "i") "i" else null }),
        )
        assertNull(WordCasing.fixedSpelling("hello", english, spellingOf))
    }

    @Test
    fun `suggestions use the saved spelling in a language with no built-in capitals`() {
        val moscow: (String) -> String? = { if (it == "москва") "Москва" else null }
        assertEquals("Москва", WordCasing.forSuggestion("москва", russian, moscow))
        assertEquals("город", WordCasing.forSuggestion("город", russian, moscow))
    }
}
