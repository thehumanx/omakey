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

    @Test
    fun `prefers a valid split over a coincidental single-word neighbor`() {
        // Real bug: "thisis" is also one substitution away from "thesis" (a real, common word),
        // which used to be returned instead of the split — even though splitting into two
        // extremely common short words is almost always what was actually meant. "thesis" is
        // deliberately included here to prove the split wins over it, not just that a split
        // exists when nothing else does (see the plain split tests above).
        val words = buildList {
            add(WordEntity("this", frequency = 100, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("is", frequency = 99, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("thesis", frequency = 90, isUserAdded = false, lastUsedTimestamp = 0))
            for (i in 1..95) add(WordEntity("word$i", frequency = 96 - i, isUserAdded = false, lastUsedTimestamp = 0))
        }
        val splitIndex = AutocorrectIndex()
        splitIndex.load(words)
        assertEquals("this is", splitIndex.correct("thisis"))
    }

    @Test
    fun `corrects a two-edit typo when no one-edit candidate exists`() {
        // "keynaord" -> "keyboard" needs both a substitution (n->b) and a transposition (ao->oa) —
        // genuinely two edits, not one. Only reachable via the distance-2 fallback.
        val words = buildList {
            add(WordEntity("keyboard", frequency = 100, isUserAdded = false, lastUsedTimestamp = 0))
            for (i in 1..95) add(WordEntity("word$i", frequency = 96 - i, isUserAdded = false, lastUsedTimestamp = 0))
        }
        val distance2Index = AutocorrectIndex()
        distance2Index.load(words)
        assertEquals("keyboard", distance2Index.correct("keynaord"))
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
        // "shoudve" is a typo of "shouldve" (missing the "l") — should still resolve to
        // "should've" via distance-1 fuzzy matching against the contraction keys, not just
        // exact-string lookup.
        assertEquals("should've", index.contractionFor("shoudve"))
        // "dont" itself is below MIN_FUZZY_CONTRACTION_LENGTH, so a typo of it ("dnot") isn't
        // fuzzy-matched — short keys are exact-match only to avoid false-positive collisions.
        assertNull(index.contractionFor("dnot"))
    }

    @Test
    fun `alternatives surfaces contractions for short words below the general min length`() {
        // "im"/"id" are only 2 letters, below MIN_LENGTH — general correction/neighbor search
        // must not gate out the contraction lookup for them.
        assertEquals(listOf("I'm"), index.alternatives("im", limit = 5))
        assertEquals(listOf("I'd"), index.alternatives("id", limit = 5))
    }

    @Test
    fun `isKnown reflects the seeded dictionary and learned words`() {
        assertEquals(true, index.isKnown("hello"))
        assertEquals(false, index.isKnown("zzzznotaword"))
        index.learn("zzzznotaword")
        assertEquals(true, index.isKnown("zzzznotaword"))
    }

    @Test
    fun `alternatives offers a contraction even though the bare word is already valid`() {
        // "well" is a perfectly real, common word — correct() would never touch it — but
        // alternatives() should still surface "we'll" as something to swipe/tap to if that's
        // what was actually meant, since only the user can tell which one is right.
        val words = buildList {
            add(WordEntity("well", frequency = 100, isUserAdded = false, lastUsedTimestamp = 0))
            for (i in 1..95) add(WordEntity("word$i", frequency = 96 - i, isUserAdded = false, lastUsedTimestamp = 0))
        }
        val altIndex = AutocorrectIndex()
        altIndex.load(words)
        assertEquals(true, altIndex.alternatives("well", 6).contains("we'll"))
    }

    @Test
    fun `alternatives finds close real-word neighbors even for an already-valid word`() {
        // "well" and "wall" are both real words one substitution apart — alternatives() should
        // offer "wall" as a cyclable option for "well" even though "well" needs no fixing.
        val words = buildList {
            add(WordEntity("well", frequency = 100, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("wall", frequency = 90, isUserAdded = false, lastUsedTimestamp = 0))
            for (i in 1..95) add(WordEntity("word$i", frequency = 96 - i, isUserAdded = false, lastUsedTimestamp = 0))
        }
        val altIndex = AutocorrectIndex()
        altIndex.load(words)
        assertEquals(true, altIndex.alternatives("well", 6).contains("wall"))
    }

    @Test
    fun `alternatives fixes a typo when the word itself is not real, plus close neighbors`() {
        // "helo" resolves to "hello" as the primary fix, but "help"/"held" are also real,
        // common, one-edit-away words worth offering to cycle through — "helot" (freq 1, well
        // below the frequency floor) should not appear despite also being a one-edit neighbor.
        val alternatives = index.alternatives("helo", 6)
        assertEquals(listOf("hello", "help", "held"), alternatives)
    }

    @Test
    fun `alternatives does not surface an obscure neighbor below the frequency floor`() {
        assertEquals(false, index.alternatives("helo", 6).contains("helot"))
    }

    @Test
    fun `alternatives respects the limit`() {
        val words = buildList {
            add(WordEntity("cat", frequency = 100, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("car", frequency = 99, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("can", frequency = 98, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("cap", frequency = 97, isUserAdded = false, lastUsedTimestamp = 0))
            add(WordEntity("cab", frequency = 96, isUserAdded = false, lastUsedTimestamp = 0))
            for (i in 1..95) add(WordEntity("word$i", frequency = 50 - i, isUserAdded = false, lastUsedTimestamp = 0))
        }
        val altIndex = AutocorrectIndex()
        altIndex.load(words)
        assertEquals(2, altIndex.alternatives("cat", 2).size)
    }
}
