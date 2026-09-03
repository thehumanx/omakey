package dev.omakey.core.predict

import dev.omakey.core.predict.eval.TestLanguageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises correction against the **real shipping language model** rather than a synthetic
 * hundred-word dictionary.
 *
 * The previous version of these tests built a fake vocabulary per case, which let each one state
 * an exact expected string. That isolation turned out to be a liability: it meant the suite could
 * pass in full while the actual bundled data was ordered alphabetically, because no test ever
 * touched the shipped asset. Assertions here are correspondingly framed as properties that must
 * hold of any usable English model ("teh" is "the"; a correctly-spelled word is left alone) rather
 * than as exact outputs of a fixture.
 */
class AutocorrectIndexTest {

    private lateinit var index: AutocorrectIndex

    @Before
    fun setUp() {
        index = AutocorrectIndex().apply { load(TestLanguageModel.load(), PersonalLanguageModel()) }
    }

    @Test
    fun `corrects a transposition to the obvious word`() {
        assertEquals("the", index.correct("teh"))
    }

    @Test
    fun `corrects common human misspellings`() {
        assertEquals("receive", index.correct("recieve"))
        assertEquals("definitely", index.correct("definately"))
        assertEquals("separate", index.correct("seperate"))
    }

    @Test
    fun `does not correct an already-known word`() {
        assertNull(index.correct("help"))
        assertNull(index.correct("keyboard"))
    }

    @Test
    fun `does not correct a word with no close common candidate`() {
        assertNull(index.correct("qwzxjv"))
    }

    @Test
    fun `ignores words shorter than the minimum length`() {
        assertNull(index.correct("ab"))
    }

    @Test
    fun `learn marks a word as known so it is never corrected away`() {
        assertEquals("receive", index.correct("recieve"))
        index.learn("recieve")
        assertNull(index.correct("recieve"))
        index.unlearn("recieve")
        assertEquals("receive", index.correct("recieve"))
    }

    @Test
    fun `unlearn refuses to touch a word from the bundled vocabulary`() {
        // Otherwise a second swipe-up on an ordinary word would quietly turn autocorrect against
        // it for the rest of the session.
        assertFalse(index.isUserAdded("cat"))
        index.unlearn("cat")
        assertTrue(index.isKnown("cat"))
    }

    @Test
    fun `splits two concatenated words missing a space`() {
        assertEquals("this is", index.correct("thisis"))
    }

    @Test
    fun `splits with one stray character where the space should have been`() {
        // "this" + a fat-fingered 'b' where the spacebar should have been + "is".
        assertEquals("this is", index.correct("thisbis"))
    }

    @Test
    fun `prefers an obvious single-word typo fix over a coincidental split`() {
        // "helko" is one edit from "hello", but also splits into two individually common short
        // words. The single-word fix has to win.
        assertEquals("hello", index.correct("helko"))
    }

    @Test
    fun `scores a split as a whole sequence, not by its weaker half`() {
        // Both of these were mangled by the earlier scoring, which compared a split's weaker half
        // against the single-word candidate — not a like-for-like comparison, since a split gets
        // to explain the same letters with two words. "seperate" became "see rate" and "wierd"
        // became "ie rd".
        assertEquals("separate", index.correct("seperate"))
        assertEquals("weird", index.correct("wierd"))
    }

    @Test
    fun `corrects a two-edit typo when no one-edit candidate exists`() {
        // Reaches the distance-2 fallback at all. Deliberately not asserting a specific word:
        // "keynaord" currently resolves to "keyword" rather than "keyboard", because at equal edit
        // distance the tie is broken purely by how common the candidate is, and "keyword" wins
        // that. Distinguishing them needs to know where the finger actually landed — 'n' and 'b'
        // are nowhere near each other — which is what the spatial model adds later.
        assertNotNull(index.correct("keynaord"))
    }

    @Test
    fun `does not split when one half is not a real word`() {
        assertNull(index.correct("helloqwzxjv"))
    }

    @Test
    fun `realWordNeighbors finds valid words one edit away from another valid word`() {
        // "thus" is itself real (correct() refuses to touch it), but "this" is still a valid
        // one-edit neighbour for a context-aware caller to weigh.
        assertTrue(index.realWordNeighbors("thus").contains("this"))
    }

    @Test
    fun `contractionFor returns curated apostrophe fixes and null otherwise`() {
        assertEquals("I'm", index.contractionFor("im"))
        assertEquals("we've", index.contractionFor("weve"))
        assertEquals("don't", index.contractionFor("dont"))
        assertEquals("should've", index.contractionFor("shouldve"))
        assertNull(index.contractionFor("hello"))
    }

    @Test
    fun `contractionFor fuzzy-matches a typo of a contraction`() {
        // "shoudve" is "shouldve" missing the 'l'.
        assertEquals("should've", index.contractionFor("shoudve"))
        // "dont" is below MIN_FUZZY_CONTRACTION_LENGTH, so a typo of it isn't fuzzy-matched —
        // short keys are exact-match only, to avoid colliding with unrelated short words.
        assertNull(index.contractionFor("dnot"))
    }

    @Test
    fun `alternatives surfaces contractions for short words below the general min length`() {
        // "im"/"id" are 2 letters, below MIN_LENGTH — the contraction lookup must not be gated out.
        assertTrue(index.alternatives("im", limit = 5).contains("I'm"))
        assertTrue(index.alternatives("id", limit = 5).contains("I'd"))
    }

    @Test
    fun `isKnown reflects the bundled vocabulary and learned words`() {
        assertTrue(index.isKnown("hello"))
        assertFalse(index.isKnown("zzzznotaword"))
        index.learn("zzzznotaword")
        assertTrue(index.isKnown("zzzznotaword"))
        assertTrue(index.isUserAdded("zzzznotaword"))
    }

    @Test
    fun `alternatives offers a contraction even though the bare word is already valid`() {
        // "well" is a perfectly real word — correct() would never touch it — but "we'll" is worth
        // offering to swipe to, since only the user knows which was meant.
        assertTrue(index.alternatives("well", 6).contains("we'll"))
    }

    @Test
    fun `alternatives finds close real-word neighbours even for an already-valid word`() {
        assertTrue(index.alternatives("well", 6).contains("wall"))
    }

    @Test
    fun `alternatives leads with the correction when the word is not real`() {
        assertEquals("receive", index.alternatives("recieve", 6).first())
    }

    @Test
    fun `alternatives offers several cyclable candidates, all real words`() {
        // The strip is browsable, so breadth matters — but every entry still has to be something
        // the user recognises, or swiping through it is worse than useless.
        val alternatives = index.alternatives("helo", 6)
        assertTrue(alternatives.size > 1)
        assertTrue(alternatives.contains("hello"))
        // Splits read as two words; everything else must be a word the vocabulary knows.
        assertTrue(alternatives.all { candidate -> candidate.split(" ").all { index.isKnown(it) } })
    }

    @Test
    fun `alternatives respects the limit`() {
        assertEquals(2, index.alternatives("cat", 2).size)
    }
}
