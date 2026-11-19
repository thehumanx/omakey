package dev.omakey.core.input

import dev.omakey.core.input.AutoCapitalization.FieldMode
import dev.omakey.core.locale.LanguageProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedSpellingTest {

    private fun saved(word: String, before: String, autoCapitalize: Boolean = true) =
        SavedSpelling.of(word, before, LanguageProfile.English, FieldMode.SENTENCES, autoCapitalize)

    @Test
    fun `a capital the keyboard added at a sentence start is not saved`() {
        assertEquals("hello", saved("Hello", "Hello"))
        assertEquals("hello", saved("Hello", "I went home. Hello "))
    }

    @Test
    fun `a capital typed mid-sentence is kept`() {
        assertEquals("Bibek", saved("Bibek", "I met Bibek"))
    }

    @Test
    fun `inner capitals are always kept`() {
        assertEquals("iPhone", saved("iPhone", "iPhone"))
        assertEquals("McDonald", saved("McDonald", "McDonald"))
        assertEquals("BIBEK", saved("BIBEK", "BIBEK"))
    }

    @Test
    fun `with auto-capitalise off every capital is the user's`() {
        assertEquals("Bibek", saved("Bibek", "Bibek", autoCapitalize = false))
    }

    @Test
    fun `a word that can't be found in the text is kept as written`() {
        assertEquals("Bibek", saved("Bibek", ""))
        assertEquals("Bibek", saved("Bibek", "something else"))
    }
}
