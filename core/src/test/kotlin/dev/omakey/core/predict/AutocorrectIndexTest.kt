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
}
