package dev.omakey.core.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants for a 376-line hand-written table that grows by hand.
 *
 * The table cannot be generated: CLDR's emoji annotations map emoji to *keywords*, which is the
 * opposite of what this needs and far noisier — "grinning face" carries keywords like "face" and
 * "grin" that would fire on unrelated words. This table is deliberately curated and exact-match, as
 * its own doc says, so generating it would undermine the property that makes it safe. What can be
 * automated is checking that the hand-editing stayed consistent, which is what this does.
 */
class WordEmojiSuggestionsTest {

    @Test
    fun `no word is listed twice`() {
        // The failure this exists for: the lookup map keeps the last write, so a word listed under
        // two entries silently loses its first pairing with nothing to notice it. Building from a
        // list instead of a map is what makes the collision visible here.
        val duplicates = WordEmojiSuggestions.pairs
            .groupBy { it.first }
            .filterValues { it.size > 1 }
            .keys

        assertEquals("words mapped more than once: $duplicates", emptySet<String>(), duplicates)
    }

    @Test
    fun `every word is lowercase`() {
        // suggest() lowercases before lookup, so an uppercase key is simply unreachable.
        val notLowercase = WordEmojiSuggestions.pairs.map { it.first }.filter { it != it.lowercase() }

        assertEquals(emptyList<String>(), notLowercase)
    }

    @Test
    fun `every word is a plausible single word`() {
        // A key with a space or punctuation can never match: the caller passes one typed word.
        val malformed = WordEmojiSuggestions.pairs
            .map { it.first }
            .filter { word -> word.isEmpty() || !word.all { it.isLetter() || it == '\'' } }

        assertEquals(emptyList<String>(), malformed)
    }

    @Test
    fun `every entry offers at least one emoji`() {
        val empty = WordEmojiSuggestions.pairs.filter { it.second.isEmpty() }.map { it.first }

        assertEquals(emptyList<String>(), empty)
    }

    @Test
    fun `no emoji entry is blank or plain ascii`() {
        // Catches a stray "" or an accidentally-typed letter where a glyph was meant.
        val suspect = WordEmojiSuggestions.pairs
            .filter { (_, emoji) -> emoji.any { it.isBlank() || it.all { ch -> ch.code < 128 } } }
            .map { it.first }

        assertEquals(emptyList<String>(), suspect)
    }

    @Test
    fun `lookup is case-insensitive`() {
        val word = WordEmojiSuggestions.pairs.first().first

        assertEquals(WordEmojiSuggestions.suggest(word), WordEmojiSuggestions.suggest(word.uppercase()))
        assertTrue(WordEmojiSuggestions.suggest(word).isNotEmpty())
    }

    @Test
    fun `an unknown word suggests nothing`() {
        assertEquals(emptyList<String>(), WordEmojiSuggestions.suggest("zzzznotaword"))
        assertEquals(emptyList<String>(), WordEmojiSuggestions.suggest(""))
    }

    @Test
    fun `no lookup ever returns more than the strip can show`() {
        // Several entries deliberately list three for future re-ranking; the cap is applied at the
        // single call site the strip reads, and the strip has room for two.
        val tooMany = WordEmojiSuggestions.pairs
            .map { it.first }
            .filter { WordEmojiSuggestions.suggest(it).size > 2 }

        assertEquals(emptyList<String>(), tooMany)
    }
}
