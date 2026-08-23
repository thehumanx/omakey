package dev.omakey.core.language.nepali

import org.junit.Assert.assertEquals
import org.junit.Test

class NepaliTransliteratorTest {

    private fun tr(raw: String) = NepaliTransliterator.transliterate(raw)

    @Test
    fun `canonical greeting composes consonant clusters and matras correctly`() {
        // n(inherent) + m(inherent) + s+virama+t (adjacent consonants, no vowel between) + e-matra
        assertEquals("नमस्ते", tr("namaste"))
    }

    @Test
    fun `bare consonant keeps its inherent vowel at word end`() {
        assertEquals("कमल", tr("kamal"))
    }

    @Test
    fun `single vowel is short, doubled vowel is long`() {
        assertEquals("कन", tr("kan"))
        assertEquals("कान", tr("kaan"))
    }

    @Test
    fun `retroflex consonants are the uppercase forms of their dental counterparts`() {
        assertEquals("टिम", tr("Tim")) // retroflex ट
        assertEquals("तिम", tr("tim")) // dental त — different letter, same vowel pattern
    }

    @Test
    fun `consonant followed by a vowel takes the matra, not the standalone vowel form`() {
        assertEquals("मेरो", tr("mero"))
    }

    @Test
    fun `word-initial vowel renders standalone, not as a dangling matra`() {
        assertEquals("आज", tr("aaja"))
    }

    @Test
    fun `two-letter aspirated and curated conjunct shorthands resolve correctly`() {
        assertEquals("खाना", tr("khaanaa"))
        assertEquals("ज्ञान", tr("gyaan"))
    }

    @Test
    fun `unrecognized characters pass through literally instead of being dropped`() {
        assertEquals("नमस्ते-नमस्ते", tr("namaste-namaste"))
    }

    @Test
    fun `empty input produces empty output`() {
        assertEquals("", tr(""))
    }
}
