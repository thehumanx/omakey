package dev.omakey.core.predict

import dev.omakey.core.db.WordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AutocorrectIndexTest {

    private lateinit var index: AutocorrectIndex

    @Before
    fun setUp() {
        index = AutocorrectIndex()
        // Rank-based frequencies, same scheme DictionarySeeder uses: higher = more common.
        // 100 words total, "the"/"hello"/"help" are near the top (very common); "helot" is
        // deliberately near the bottom (rare) to exercise the correction-floor cutoff.
        val words = buildList {
            add(WordEntity("the", frequency = 100, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("hello", frequency = 99, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("help", frequency = 98, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("held", frequency = 97, isUserAdded = false, lastUsedTimestamp = 0))
            // Padding so "the top 20%" cutoff has enough entries to be meaningful.
            for (i in 1..95) add(WordEntity("word$i", frequency = 96 - i, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("helot", frequency = 1, isUserAdded = false, lastUsedTimestamp = 0))
        }
        index.load(words)
    }

    @Test
    fun `corrects a one-edit typo to the most common candidate`() {
        // "helo" is one deletion away from both "hello" and "help" (and others) — should pick
        // the higher-frequency one.
        assertEquals("hello", index.correct("helo"))
    }

    @Test
    fun `does not correct an already-known word`() {
        assertNull(index.correct("help"))
    }

    @Test
    fun `does not correct a word with no close common candidate`() {
        assertNull(index.correct("xyzzy"))
    }

    @Test
    fun `does not correct into a rare word below the frequency floor`() {
        // "helot" only differs from "helo" by nothing extra to test directly, so use a
        // one-edit neighbor of "helot" that isn't also a neighbor of a common word.
        assertNull(index.correct("helott"))
    }

    @Test
    fun `ignores words shorter than the minimum length`() {
        assertNull(index.correct("ab"))
    }

    @Test
    fun `learn marks a word as known so it is never corrected away`() {
        assertEquals("hello", index.correct("helo"))
        index.learn("helo")
        assertNull(index.correct("helo"))
    }

    @Test
    fun `splits two concatenated words missing a space`() {
        val words = buildList {
            add(WordEntity("this", frequency = 100, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("is", frequency = 99, isUserAdded = false, lastUsedTimestamp = 0))
            for (i in 1..95) add(WordEntity("word$i", frequency = 96 - i, isUserAdded = false, lastUsedTimestamp = 0))
        }
        val splitIndex = AutocorrectIndex()
        splitIndex.load(words)
        // "thisis" isn't itself a real word and has no close single-edit dictionary neighbor —
        // only the split fallback should be able to explain it.
        assertEquals("this is", splitIndex.correct("thisis"))
    }

    @Test
    fun `splits with one stray character where the space should have been`() {
        val words = buildList {
            add(WordEntity("this", frequency = 100, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("is", frequency = 99, isUserAdded = false, lastUsedTimestamp = 0))
            for (i in 1..95) add(WordEntity("word$i", frequency = 96 - i, isUserAdded = false, lastUsedTimestamp = 0))
        }
        val splitIndex = AutocorrectIndex()
        splitIndex.load(words)
        // "thisbis" = "this" + a stray 'b' + "is" — a fat-fingered key landed where the spacebar
        // should have been, on top of the missing space itself.
        assertEquals("this is", splitIndex.correct("thisbis"))
    }

    @Test
    fun `realWordNeighbors finds a valid word one substitution away from another valid word`() {
        val words = buildList {
            add(WordEntity("this", frequency = 100, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("thus", frequency = 50, isUserAdded = false, lastUsedTimestamp = 0))
            for (i in 1..95) add(WordEntity("word$i", frequency = 96 - i, isUserAdded = false, lastUsedTimestamp = 0))
        }
        val neighborIndex = AutocorrectIndex()
        neighborIndex.load(words)
        // "thus" is itself a real word (correct() would refuse to touch it), but it's still a
        // valid one-edit neighbor of "this" for a context-aware caller to consider.
        assertEquals(setOf("this"), neighborIndex.realWordNeighbors("thus"))
    }

    @Test
    fun `does not split when one half is not a real word`() {
        // "helloxyzzy" isn't a real word, isn't a close single-edit neighbor of one, and doesn't
        // split into two real words either ("xyzzy" isn't in the dictionary) — should stay null
        // rather than force a low-confidence split.
        assertNull(index.correct("helloxyzzy"))
    }
}
