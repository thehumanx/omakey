package dev.omakey.core.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorrectionTextTest {

    @Test
    fun `a lowercase word is corrected without added capitals`() {
        assertEquals("hello", matchCase("hwllo", "hello"))
    }

    @Test
    fun `a leading capital is preserved`() {
        // The bug this exists for: candidate lists are lowercase, so without case restoration
        // correcting "Hwllo" offered "hello".
        assertEquals("Hello", matchCase("Hwllo", "hello"))
    }

    @Test
    fun `all caps stays all caps`() {
        assertEquals("HELLO", matchCase("HWLLO", "hello"))
    }

    @Test
    fun `a single uppercase letter counts as all caps, not as a leading capital`() {
        // Both branches would give "I" here; pinned because the order of the two checks is what
        // makes that true, and swapping them changes multi-letter behaviour silently.
        assertEquals("I", matchCase("I", "i"))
    }

    @Test
    fun `mixed case that is not leading-capital is left alone`() {
        assertEquals("mcdonald", matchCase("mcDonald", "mcdonald"))
    }

    @Test
    fun `an empty typed word returns the correction unchanged`() {
        assertEquals("hello", matchCase("", "hello"))
    }

    @Test
    fun `a two-word correction splits on its space`() {
        assertEquals("this" to "is", splitCorrection("this is"))
    }

    @Test
    fun `a plain single word is not a split`() {
        assertNull(splitCorrection("hello"))
    }

    @Test
    fun `a leading or trailing space is not a split`() {
        // Either would make one half empty, and committing an empty word as a word boundary
        // corrupts the typing-order bookkeeping downstream.
        assertNull(splitCorrection(" hello"))
        assertNull(splitCorrection("hello "))
    }

    @Test
    fun `an empty replacement is not a split`() {
        assertNull(splitCorrection(""))
    }

    @Test
    fun `only the first space is treated as the boundary`() {
        assertEquals("a" to "b c", splitCorrection("a b c"))
    }
}
