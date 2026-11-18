package dev.omakey.core.db

import dev.omakey.core.locale.LanguageProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearnedWordEditsTest {

    private val english = LanguageProfile.English

    @Test
    fun `an edited word is stored the way the keyboard looks it up`() {
        assertEquals("bibek", LearnedWordEdits.normalise("Bibek", english))
        assertEquals("bibek", LearnedWordEdits.normalise("  bibek ", english))
    }

    @Test
    fun `what isn't one word is refused`() {
        for (bad in listOf("", "   ", "hello world", "123", "bi.bek", "bibek!")) {
            assertNull(bad, LearnedWordEdits.normalise(bad, english))
        }
    }

    private fun row(word: String, frequency: Int, explicit: Boolean, lastUsed: Long) =
        WordEntity(word = word, frequency = frequency, isUserAdded = true, lastUsedTimestamp = lastUsed, explicit = explicit, locale = "en_US")

    @Test
    fun `renaming onto a word already learned merges the two`() {
        val merged = LearnedWordEdits.merge(row("bibk", 300, explicit = true, lastUsed = 10), row("bibek", 200, explicit = false, lastUsed = 20), "bibek")
        assertEquals("bibek", merged.word)
        assertEquals(500, merged.frequency)
        assertEquals(true, merged.explicit)
        assertEquals(20L, merged.lastUsedTimestamp)
    }

    @Test
    fun `renaming onto a new word keeps the row's history`() {
        val renamed = LearnedWordEdits.merge(row("bibk", 300, explicit = true, lastUsed = 10), null, "bibek")
        assertEquals(row("bibek", 300, explicit = true, lastUsed = 10), renamed)
    }

    /** Closed-beta report: editing a learned word "doesn't work". Renaming onto a word already in the
     * list hit the table's primary key and crashed the screen. */
    @Test
    fun `renameOrMerge onto an existing word leaves one row and no old one`() = runBlocking {
        val dao = dev.omakey.core.predict.eval.InMemoryWordDao(listOf(row("bibk", 300, true, 10), row("bibek", 200, false, 20)))
        dao.renameOrMerge("en_US", "bibk", "bibek")
        assertNull(dao.findExact("en_US", "bibk"))
        assertEquals(500, dao.findExact("en_US", "bibek")!!.frequency)
    }
}
