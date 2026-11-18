package dev.omakey.core.predict

import dev.omakey.core.predict.eval.TestLanguageModel
import org.junit.Assert.assertEquals
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
        assertEquals("whereas", index.correct("wheras"))
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
}
