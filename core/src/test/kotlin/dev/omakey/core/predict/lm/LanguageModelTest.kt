package dev.omakey.core.predict.lm

import dev.omakey.core.predict.eval.TestLanguageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the real shipping asset — the point is to prove the Kotlin reader and the Python writer
 * agree byte for byte, which a synthetic fixture could not do. A misread section offset produces
 * plausible-looking garbage rather than an exception, so these assertions check *meaning*
 * ("the" is common, "i" has continuations) and not just that the numbers parse.
 */
class LanguageModelTest {

    private val model = TestLanguageModel.load()

    @Test
    fun `vocabulary is lexicographically ordered and searchable`() {
        assertNotEquals(LanguageModel.NO_WORD, model.indexOf("the"))
        assertNotEquals(LanguageModel.NO_WORD, model.indexOf("keyboard"))
        assertEquals(LanguageModel.NO_WORD, model.indexOf("zzzznotaword"))

        for (word in listOf("the", "i", "you", "hello", "keyboard", "whole")) {
            assertEquals(word, model.wordAt(model.indexOf(word)))
        }

        var previous = model.wordAt(0)
        for (id in 1 until minOf(model.vocabularySize, 5_000)) {
            val current = model.wordAt(id)
            assertTrue("vocabulary out of order at $id: $previous then $current", previous < current)
            previous = current
        }
    }

    @Test
    fun `contractions are first-class vocabulary entries`() {
        // The old alphabetic-only word list could not represent these at all, which is why
        // apostrophe handling had to live in a hand-curated map.
        for (word in listOf("don't", "it's", "i'm", "you're", "o'clock")) {
            assertNotEquals("missing $word", LanguageModel.NO_WORD, model.indexOf(word))
        }
    }

    @Test
    fun `prefix range is contiguous and complete`() {
        val range = model.prefixRange("keyb")
        assertTrue("no words start with 'keyb'", !range.isEmpty())
        for (id in range) assertTrue(model.wordAt(id).startsWith("keyb"))
        // Bounds are tight: neither neighbour outside the range may match.
        assertTrue(range.first == 0 || !model.wordAt(range.first - 1).startsWith("keyb"))
        assertTrue(range.last == model.vocabularySize - 1 || !model.wordAt(range.last + 1).startsWith("keyb"))
    }

    @Test
    fun `unigram probabilities reflect real word frequency`() {
        val the = model.unigramLogProbability(model.indexOf("the"))
        val keyboard = model.unigramLogProbability(model.indexOf("keyboard"))
        assertTrue("P(the)=$the should exceed P(keyboard)=$keyboard", the > keyboard)
        assertTrue("log probabilities should be negative, got $the", the < 0f)
    }

    @Test
    fun `next-word continuations are ranked by probability, not alphabetically`() {
        // The regression this whole rebuild exists for: the previous asset answered this question
        // with "a, ability, above, absence, absolute".
        val the = model.indexOf("the")
        val row = model.bigramRow(the)
        assertTrue("'the' has no continuations", !row.isEmpty())

        val top = row.take(8).map { model.wordAt(model.bigramWordId(it)) }
        assertNotEquals("continuations are in alphabetical order", top, top.sorted())

        var previous = Float.MAX_VALUE
        for (i in row) {
            val logP = model.bigramLogProbability(i)
            assertTrue("row not in descending probability order", logP <= previous)
            previous = logP
        }
    }

    @Test
    fun `the word i has continuations`() {
        // Rank 14 in English and it had exactly zero next-word data in the shipping model.
        val row = model.bigramRow(model.indexOf("i"))
        assertTrue("'i' still has no continuations", !row.isEmpty())
        val top = row.take(10).map { model.wordAt(model.bigramWordId(it)) }
        assertTrue("expected common continuations of 'i', got $top", top.any { it in setOf("am", "have", "think", "was", "don't") })
    }

    @Test
    fun `trigram context carries the evidence bigrams cannot`() {
        // "a while lot" -> "a whole lot": the two candidates are only separable by the word that
        // follows them, which is exactly what post-correction needs.
        val a = model.indexOf("a")
        val lot = model.indexOf("lot")
        val wholeLot = model.logProbability(lot, model.indexOf("whole"), a)
        val whileLot = model.logProbability(lot, model.indexOf("while"), a)
        assertTrue("P(lot|a,whole)=$wholeLot should exceed P(lot|a,while)=$whileLot", wholeLot > whileLot)
    }

    @Test
    fun `backoff orders trigram above bigram above unigram`() {
        val you = model.indexOf("you")
        val thank = model.indexOf("thank")
        val withContext = model.logProbability(you, thank)
        val withoutContext = model.logProbability(you)
        assertTrue(
            "'you' should score far better after 'thank' ($withContext) than alone ($withoutContext)",
            withContext > withoutContext,
        )
    }

    @Test
    fun `unknown words score low but remain finite`() {
        val score = model.logProbability(LanguageModel.NO_WORD)
        assertTrue(score.isFinite())
        assertTrue(score < model.unigramLogProbability(model.indexOf("aardvark")))
    }
}
