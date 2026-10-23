package dev.omakey.core.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctuationSpacingTest {

    @Test
    fun `sentence and clause marks get a space at the end of a line`() {
        for (mark in listOf(".", ",", "!", "?", ";", ":", "…")) {
            assertTrue(mark, PunctuationSpacing.addsSpace(mark, textAfter = ""))
            assertTrue(mark, PunctuationSpacing.addsSpace(mark, textAfter = "\nnext line"))
        }
    }

    @Test
    fun `other symbols get no space`() {
        for (symbol in listOf("@", "#", "-", "'", "\"", "(", ")", "/", "a", "1", "..")) {
            assertFalse(symbol, PunctuationSpacing.addsSpace(symbol, textAfter = ""))
        }
    }

    @Test
    fun `no space when typing into the middle of text`() {
        assertFalse(PunctuationSpacing.addsSpace(",", textAfter = " home"))
        assertFalse(PunctuationSpacing.addsSpace(".", textAfter = "com"))
    }

    @Test
    fun `more punctuation and closers go before the space`() {
        assertTrue(PunctuationSpacing.replacesSpace("!", "What? "))
        assertTrue(PunctuationSpacing.replacesSpace(".", "Wait. "))
        assertTrue(PunctuationSpacing.replacesSpace(")", "(see above. "))
        assertTrue(PunctuationSpacing.replacesSpace("”", "“Done. "))
        assertTrue(PunctuationSpacing.respacesAfter("!"))
        assertTrue(PunctuationSpacing.respacesAfter(")"))
    }

    @Test
    fun `letters, opening quotes and symbols keep the space`() {
        for (next in listOf("a", "A", "\"", "'", "(", "@", "-")) {
            assertFalse(next, PunctuationSpacing.replacesSpace(next, "Hello. "))
        }
    }

    @Test
    fun `a number keeps its decimal point, thousands comma and time colon`() {
        assertTrue(PunctuationSpacing.replacesSpace("1", "3. "))
        assertTrue(PunctuationSpacing.replacesSpace("0", "1, "))
        assertTrue(PunctuationSpacing.replacesSpace("3", "10: "))
        assertFalse(PunctuationSpacing.respacesAfter("1"))
    }

    @Test
    fun `a digit after a word's punctuation keeps the space`() {
        assertFalse(PunctuationSpacing.replacesSpace("3", "items: "))
        assertFalse(PunctuationSpacing.replacesSpace("2", "Wow! "))
        assertFalse(PunctuationSpacing.replacesSpace("2", "8! "))
    }
}
