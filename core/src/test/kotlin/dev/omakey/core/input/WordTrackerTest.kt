package dev.omakey.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behaviour these lock down is the behaviour that was previously maintained by hand at eight
 * call sites inside `KeyboardViewModel`, where nothing could test it.
 */
class WordTrackerTest {

    private val tracker = WordTracker()

    @Test
    fun `commitWord keeps both the lowercased and the as-typed form`() {
        tracker.commitWord("Hello")

        // The whole reason two fields exist: bigram lookups key on the lowercase form, while
        // offering "Hello" as a fix needs to know the original was capitalised.
        assertEquals("hello", tracker.lastCommitted)
        assertEquals("Hello", tracker.lastCommittedCased)
    }

    @Test
    fun `commitWord shifts the previous word back one place`() {
        tracker.commitWord("the")
        tracker.commitWord("quick")
        tracker.commitWord("brown")

        assertEquals("brown", tracker.lastCommitted)
        assertEquals("quick", tracker.previousToLastCommitted)
    }

    @Test
    fun `commitWord returns the word it displaced`() {
        tracker.commitWord("the")

        // Deferred learning needs the left context read *before* the shift; returning it is what
        // stops a caller reading the field in the wrong order.
        assertEquals("the", tracker.commitWord("cat"))
    }

    @Test
    fun `the first commit has no previous word`() {
        assertNull(tracker.commitWord("hello"))
        assertNull(tracker.previousToLastCommitted)
    }

    @Test
    fun `retargetLastCommitted rewrites the word without shifting history`() {
        tracker.commitWord("the")
        tracker.commitWord("cta")

        tracker.retargetLastCommitted("cat")

        assertEquals("cat", tracker.lastCommitted)
        assertEquals("cat", tracker.lastCommittedCased)
        // The distinction that makes this a separate method: a correction crosses no new word
        // boundary, so shifting would push "the" out of view and leave ranking with no context.
        assertEquals("the", tracker.previousToLastCommitted)
    }

    @Test
    fun `retargeting repeatedly never loses the context word`() {
        tracker.commitWord("i")
        tracker.commitWord("hvae")

        // Cycling through candidates retargets once per step.
        tracker.retargetLastCommitted("have")
        tracker.retargetLastCommitted("gave")
        tracker.retargetLastCommitted("hate")

        assertEquals("i", tracker.previousToLastCommitted)
    }

    @Test
    fun `commitBufferedWord commits what was typed and empties the buffer`() {
        "cat".forEach { tracker.appendToBuffer(it) }

        val committed = tracker.commitBufferedWord()

        assertEquals("cat", committed?.word)
        assertEquals("cat", tracker.lastCommitted)
        assertTrue(tracker.isBufferEmpty)
    }

    @Test
    fun `commitBufferedWord reports the previous word alongside the committed one`() {
        tracker.commitWord("the")
        "cat".forEach { tracker.appendToBuffer(it) }

        assertEquals("the", tracker.commitBufferedWord()?.previousWord)
    }

    @Test
    fun `commitBufferedWord on an empty buffer changes nothing`() {
        tracker.commitWord("hello")

        // Word boundaries fire on a separator regardless of whether a word preceded it — two
        // spaces in a row, or a space after punctuation. None of those may disturb history.
        assertNull(tracker.commitBufferedWord())
        assertEquals("hello", tracker.lastCommitted)
        assertNull(tracker.previousToLastCommitted)
    }

    @Test
    fun `buffer preserves the case that was typed`() {
        "McDonald".forEach { tracker.appendToBuffer(it) }

        assertEquals("McDonald", tracker.bufferedWord)
        assertEquals("McDonald", tracker.commitBufferedWord()?.word)
        assertEquals("mcdonald", tracker.lastCommitted)
        assertEquals("McDonald", tracker.lastCommittedCased)
    }

    @Test
    fun `replaceBuffer swaps the whole word in progress`() {
        "teh".forEach { tracker.appendToBuffer(it) }

        tracker.replaceBuffer("the")

        assertEquals("the", tracker.bufferedWord)
        assertEquals(3, tracker.bufferLength)
    }

    @Test
    fun `deleteLastBufferedChar backspaces within the word`() {
        "cat".forEach { tracker.appendToBuffer(it) }

        tracker.deleteLastBufferedChar()

        assertEquals("ca", tracker.bufferedWord)
    }

    @Test
    fun `deleteLastBufferedChar on an empty buffer is a no-op, not a crash`() {
        // Backspacing past the start of a word is ordinary usage, not an error, so the guard lives
        // here rather than being repeated at each call site.
        tracker.deleteLastBufferedChar()

        assertTrue(tracker.isBufferEmpty)
        assertEquals("", tracker.bufferedWord)
    }

    @Test
    fun `clear forgets every word and the separator`() {
        tracker.commitWord("the")
        tracker.commitWord("cat")
        "sa".forEach { tracker.appendToBuffer(it) }
        tracker.boundarySeparator = "\n"

        tracker.clear()

        assertNull(tracker.lastCommitted)
        assertNull(tracker.lastCommittedCased)
        assertNull(tracker.previousToLastCommitted)
        assertTrue(tracker.isBufferEmpty)
        // A separator describes a boundary in text that is no longer in front of us; carrying a
        // newline into a fresh field would misdescribe the next retroactive correction.
        assertEquals(" ", tracker.boundarySeparator)
    }

    @Test
    fun `buffer emptiness flags stay consistent with each other`() {
        assertTrue(tracker.isBufferEmpty)
        assertFalse(tracker.isBufferNotEmpty)

        tracker.appendToBuffer('a')

        assertFalse(tracker.isBufferEmpty)
        assertTrue(tracker.isBufferNotEmpty)
    }

    @Test
    fun `a realistic sentence keeps the bigram context correct throughout`() {
        // "i hvae a" then a correction of the middle word, which is the sequence that produced the
        // original casing bug.
        "i".forEach { tracker.appendToBuffer(it) }
        tracker.commitBufferedWord()
        "Hvae".forEach { tracker.appendToBuffer(it) }
        tracker.commitBufferedWord()

        assertEquals("i", tracker.previousToLastCommitted)
        assertEquals("Hvae", tracker.lastCommittedCased)

        tracker.retargetLastCommitted("Have")

        assertEquals("Have", tracker.lastCommittedCased)
        assertEquals("have", tracker.lastCommitted)
        assertEquals("i", tracker.previousToLastCommitted)

        "a".forEach { tracker.appendToBuffer(it) }
        tracker.commitBufferedWord()

        // The corrected form, not the typo, is what the next word's context must be.
        assertEquals("have", tracker.previousToLastCommitted)
    }
}
