package dev.omakey.core.predict

import dev.omakey.core.predict.eval.TestLanguageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The silent fix at a word boundary and English contractions. Closed-beta report: "dont" became
 * "font", "youre" became "your", "ive" became "ice", and "im" was left alone — the silent path
 * never consulted the contraction table, only the suggestion strip did.
 */
class ContractionAutocorrectTest {

    private val index = AutocorrectIndex().apply { load(TestLanguageModel.load(), PersonalLanguageModel()) }

    @Test
    fun `apostrophe-less forms that are not words become the contraction`() {
        val expected = mapOf(
            "dont" to "don't", "im" to "I'm", "ive" to "I've", "youre" to "you're",
            "theyre" to "they're", "thats" to "that's", "didnt" to "didn't",
        )
        for ((typed, contraction) in expected) assertEquals(typed, contraction, index.correct(typed))
    }

    @Test
    fun `real words almost never meant as themselves become the contraction`() {
        val expected = mapOf("cant" to "can't", "wont" to "won't", "hes" to "he's", "whats" to "what's")
        for ((typed, contraction) in expected) assertEquals(typed, contraction, index.correct(typed))
    }

    @Test
    fun `real words typed on purpose are never turned into a contraction`() {
        for (word in listOf("well", "its", "were", "ill", "hell", "shed", "wed", "lets", "id")) {
            assertNull(word, index.correct(word))
        }
    }

    @Test
    fun `a typo near a contraction key is still corrected to the word it was meant to be`() {
        // Fuzzy contraction matching is for the strip only: "wheras" is one edit from "wheres".
        // Since the confidence gates, "whereas" is ranked first but not applied silently (another
        // reading is close behind); what this pins is that "where's" never wins.
        assertNotEquals("where's", index.correct("wheras"))
        assertEquals("whereas", index.rankedCorrections("wheras").first().text)
    }

    @Test
    fun `chat shorthand is left as typed`() {
        for (word in listOf("pls", "thx", "idk", "btw", "lol", "u", "ur")) {
            assertNull(word, index.correct(word))
        }
    }

    @Test
    fun `chat shorthand still gets strip alternatives`() {
        assertTrue(index.alternatives("pls", 3).isNotEmpty())
    }

    @Test
    fun `capitalised input keeps its capital`() {
        assertEquals("Don't", matchCase("Dont", index.correct("Dont")!!))
        assertEquals("I'm", matchCase("Im", index.correct("Im")!!))
        assertEquals("I'm", matchCase("im", index.correct("im")!!))
    }

    /** "IM" in capitals is "instant message", not a shouted "I'm". A capitalised two-letter word
     * reads as an abbreviation; longer all-caps words ("DONT") are still shouting. */
    @Test
    fun `an all-caps two-letter word is left as an abbreviation`() {
        for (word in listOf("IM", "ID")) assertNull(word, index.correct(word))
        assertEquals("DON'T", matchCase("DONT", index.correct("DONT")!!))
    }

    /** "yall" was in both the shorthand list and the contraction table; shorthand won, so it never
     * became "y'all". */
    @Test
    fun `yall becomes y'all`() {
        assertEquals("y'all", index.correct("yall"))
    }

    @Test
    fun `no shorthand word hides a contraction`() {
        val english = dev.omakey.core.locale.LanguageProfile.English
        assertEquals(emptySet<String>(), english.keepAsTyped intersect english.contractions.keys)
    }
}
