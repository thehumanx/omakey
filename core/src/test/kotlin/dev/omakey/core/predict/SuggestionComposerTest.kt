package dev.omakey.core.predict

import dev.omakey.core.predict.eval.TestLanguageModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Candidate selection, against the **real shipping language model** — same reasoning as
 * [AutocorrectIndexTest]: a synthetic vocabulary lets each case assert an exact string, at the cost
 * of the suite passing while the bundled data is wrong.
 *
 * The prediction side is a stub, because the merge rules are what is under test here, not what the
 * n-gram model happens to predict.
 */
class SuggestionComposerTest {

    /** Returns whatever it is told to, so the merge behaviour is the only variable. */
    private class StubPredictionEngine(var predictions: List<String> = emptyList()) : PredictionEngine {
        override suspend fun suggestNext(
            beforePreviousWord: String?,
            previousWord: String?,
            currentPrefix: String,
            limit: Int,
        ): List<String> = predictions.take(limit)

        override suspend fun recordAcceptedWord(word: String, previousWord: String?) = Unit
        override suspend fun saveWord(word: String) = Unit
        override suspend fun deleteWord(word: String) = Unit
        override suspend fun contextLogProbability(previousWord: String, word: String): Float? = null
    }

    private lateinit var index: AutocorrectIndex
    private lateinit var predictions: StubPredictionEngine
    private lateinit var composer: SuggestionComposer

    @Before
    fun setUp() {
        index = AutocorrectIndex().apply { load(TestLanguageModel.load(), PersonalLanguageModel()) }
        predictions = StubPredictionEngine()
        composer = SuggestionComposer(index, predictions)
    }

    @Test
    fun `a typo being typed offers its correction, marked as a correction`() {
        val result = runBlocking { composer.forWordInProgress("teh", previousWord = null, beforePreviousWord = null) }

        assertTrue("expected 'the' among ${result.words}", result.words.contains("the"))
        // Drives both the quoting in the strip and, more importantly, whether accepting replaces
        // text or appends it.
        assertTrue(result.fromCorrection)
    }

    @Test
    fun `predictions fill the remaining slots after corrections`() {
        // "xyzzyq" has exactly one alternative, so there is room left for a prediction. With a
        // common typo the six correction slots fill up and predictions are legitimately squeezed
        // out — which is the intended priority, not a bug.
        predictions.predictions = listOf("zzzpredicted")

        val result = runBlocking { composer.forWordInProgress("xyzzyq", previousWord = null, beforePreviousWord = null) }

        assertTrue("got ${result.words}", result.words.contains("zzzpredicted"))
        assertTrue("corrections must come first", result.words.indexOf("xyz") < result.words.indexOf("zzzpredicted"))
    }

    @Test
    fun `a word offered by both sources is not shown twice`() {
        // The two sources disagree about capitalisation, so the de-duplication has to ignore case
        // or a six-slot strip wastes one on a repeat.
        predictions.predictions = listOf("The")

        val result = runBlocking { composer.forWordInProgress("teh", previousWord = null, beforePreviousWord = null) }

        assertEquals(1, result.words.count { it.equals("the", ignoreCase = true) })
    }

    @Test
    fun `the limit is respected across both sources`() {
        val small = SuggestionComposer(index, predictions, limit = 3)
        predictions.predictions = listOf("aaa", "bbb", "ccc", "ddd", "eee")

        val result = runBlocking { small.forWordInProgress("teh", previousWord = null, beforePreviousWord = null) }

        assertTrue("got ${result.words}", result.words.size <= 3)
    }

    @Test
    fun `predictions alone are not reported as corrections`() {
        predictions.predictions = listOf("zzzpredicted")

        val result = runBlocking { composer.nextWord(previousWord = "the", beforePreviousWord = null) }

        assertEquals(listOf("zzzpredicted"), result.words)
        // Accepting one of these types a fresh word; claiming otherwise would make it replace one.
        assertFalse(result.fromCorrection)
    }

    @Test
    fun `a finished typo can still be corrected in place`() {
        val result = runBlocking { composer.forFinishedWord("teh", beforePreviousWord = null) }

        assertTrue("expected 'the' among ${result.words}", result.words.contains("the"))
        assertTrue(result.fromCorrection)
    }

    @Test
    fun `a word with nothing near it offers nothing, so the caller can fall through`() {
        val result = runBlocking { composer.forFinishedWord("qqqqqq", beforePreviousWord = null) }

        // None rather than an empty strip: the caller reads this as "show plain prediction instead".
        assertTrue("got ${result.words}", result.isEmpty)
        assertFalse(result.fromCorrection)
    }

    @Test
    fun `a correctly spelled finished word still offers variants`() {
        // Worth pinning because it is surprising: alternatives() returns near neighbours, not only
        // fixes, so "keyboard" yields "keyboards"/"keyword". The retroactive path is therefore
        // offered far more often than "the last word was a typo" would suggest, and swipe-to-cycle
        // stays available on a correctly typed word. Changing this changes the feel of the strip.
        val result = runBlocking { composer.forFinishedWord("keyboard", beforePreviousWord = null) }

        assertFalse("got ${result.words}", result.isEmpty)
        assertTrue(result.fromCorrection)
    }

    @Test
    fun `capitalisation of the typed word is restored on corrections`() {
        val result = runBlocking { composer.forWordInProgress("Teh", previousWord = null, beforePreviousWord = null) }

        assertTrue("expected 'The' among ${result.words}", result.words.contains("The"))
    }

    @Test
    fun `an all-caps typo is corrected in all caps`() {
        val result = runBlocking { composer.forWordInProgress("TEH", previousWord = null, beforePreviousWord = null) }

        assertTrue("expected 'THE' among ${result.words}", result.words.contains("THE"))
    }

    @Test
    fun `a two-word split keeps its lowercase form`() {
        // matchCase's single-word rules must not be applied to a split result; "This is" would be
        // wrong, and the index already produced it in a readable form.
        val split = composer.alternatives("thisis", index.contextOf(null, null)).firstOrNull { it.contains(' ') }

        if (split != null) assertEquals(split.lowercase(), split)
    }

    @Test
    fun `an empty prefix produces no corrections`() {
        val result = runBlocking { composer.forWordInProgress("", previousWord = null, beforePreviousWord = null) }

        assertFalse(result.fromCorrection)
    }
}
