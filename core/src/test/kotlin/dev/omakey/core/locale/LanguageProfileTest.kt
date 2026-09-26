package dev.omakey.core.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as part of a word. The case this exists for: Devanagari vowel signs and the halant
 * are Unicode marks, not letters, and treating them as word boundaries is what broke an earlier
 * Nepali attempt (AGENTS.md §66.1).
 */
class LanguageProfileTest {

    private val english = LanguageProfile.English

    @Test
    fun `Devanagari words are whole words, marks included`() {
        // नमस्ते has a halant (्, Mn) and a vowel sign (े, Mn); किताब has ि (Mc) and ा (Mc);
        // ज्ञान a conjunct via halant; मैले ै (Mn) and े.
        for (word in listOf("नमस्ते", "किताब", "ज्ञान", "मैले", "छ", "गर्नु")) {
            assertTrue("$word should be one word", english.isWord(word))
        }
    }

    @Test
    fun `vowel signs and the halant are combining marks`() {
        assertTrue(LanguageProfile.isCombiningMark('ि')) // ि  VOWEL SIGN I (Mc)
        assertTrue(LanguageProfile.isCombiningMark('े')) // े  VOWEL SIGN E (Mn)
        assertTrue(LanguageProfile.isCombiningMark('्')) // ्  VIRAMA (Mn)
        assertTrue(LanguageProfile.isCombiningMark('ं')) // ं  ANUSVARA (Mn)
        assertTrue(LanguageProfile.isCombiningMark('́')) // combining acute, decomposed é
        assertFalse(LanguageProfile.isCombiningMark('⃝')) // enclosing circle (Me): emoji machinery
    }

    @Test
    fun `Latin letters and precomposed accents are word characters`() {
        for (c in "azAZéñçœ") assertTrue("$c", english.isWordChar(c))
    }

    @Test
    fun `punctuation, digits, whitespace and emoji are not`() {
        val notWord = listOf('\'', ' ', '.', ',', '1', '।' /* danda */, '०' /* ० */, '\uD83D', '\uDE02')
        for (c in notWord) assertFalse("U+%04X".format(c.code), english.isWordChar(c))
        assertFalse(english.isWord(""))
        assertFalse(english.isWord("don't"))
    }

    @Test
    fun `extra word characters are per language`() {
        val zwnj = '‌'
        assertFalse(english.isWordChar(zwnj))
        val withZwnj = LanguageProfile(
            script = Script.DEVANAGARI,
            hasCase = false,
            extraWordChars = setOf('‍', zwnj),
            capitalizeAfter = setOf('।', '?', '!'),
            doubleSpaceInserts = '।',
            punctuationCycle = listOf('।', ',', '?', '!'),
        )
        assertTrue(withZwnj.isWord("क्‌ष"))
        assertEquals(emptyList<String>(), withZwnj.emojiFor("happy"))
    }

    @Test
    fun `English keeps exactly the rules it had before profiles existed`() {
        // Moved verbatim out of KeyboardViewModel/AutocorrectIndex; GoldenBehaviourTest covers the
        // engine side, this covers the constants that aren't on the engine path.
        assertEquals(listOf('.', ',', '!', '?', ';', ':', '\'', '"'), english.punctuationCycle)
        assertEquals('.', english.doubleSpaceInserts)
        assertEquals(setOf('.', '!', '?'), english.capitalizeAfter)
        assertTrue(english.hasCase)
        assertEquals("I'm", english.contractions["im"])
        assertTrue(english.emojiFor("happy").isNotEmpty())
    }
}
