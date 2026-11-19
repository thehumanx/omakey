package dev.omakey.core.db

import dev.omakey.core.locale.LanguageProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearnedWordEditsTest {

    private val english = LanguageProfile.English
    private val russian = LanguageProfile(
        script = dev.omakey.core.locale.Script.CYRILLIC, hasCase = true, capitalizeAfter = setOf('.'),
        doubleSpaceInserts = '.', punctuationCycle = listOf('.'),
    )

    @Test
    fun `an edited word is looked up lowercase and keeps the spelling the user wrote`() {
        assertEquals(LearnedWordEdits.Edit("bibek", "Bibek"), LearnedWordEdits.normalise("Bibek", english))
        assertEquals(LearnedWordEdits.Edit("bibek", null), LearnedWordEdits.normalise("  bibek ", english))
        assertEquals(LearnedWordEdits.Edit("iphone", "iPhone"), LearnedWordEdits.normalise(" iPhone ", english))
        assertEquals(LearnedWordEdits.Edit("москва", "Москва"), LearnedWordEdits.normalise("Москва", russian))
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
        val merged = LearnedWordEdits.merge(row("bibk", 300, explicit = true, lastUsed = 10), row("bibek", 200, explicit = false, lastUsed = 20), "bibek", display = "Bibek")
        assertEquals("bibek", merged.word)
        assertEquals(500, merged.frequency)
        assertEquals(true, merged.explicit)
        assertEquals(20L, merged.lastUsedTimestamp)
        assertEquals("the edited spelling wins", "Bibek", merged.display)
    }

    @Test
    fun `renaming onto a new word keeps the row's history`() {
        val renamed = LearnedWordEdits.merge(row("bibk", 300, explicit = true, lastUsed = 10), null, "bibek", display = null)
        assertEquals(row("bibek", 300, explicit = true, lastUsed = 10), renamed)
        assertEquals("iPhone", LearnedWordEdits.merge(row("iphon", 300, true, 10), null, "iphone", display = "iPhone").display)
    }

    /** Closed-beta report: editing a learned word "doesn't work". Renaming onto a word already in the
     * list hit the table's primary key and crashed the screen. */
    @Test
    fun `renameOrMerge onto an existing word leaves one row and no old one`() = runBlocking {
        val dao = dev.omakey.core.predict.eval.InMemoryWordDao(listOf(row("bibk", 300, true, 10), row("bibek", 200, false, 20)))
        dao.renameOrMerge("en_US", "bibk", "bibek", display = null)
        assertNull(dao.findExact("en_US", "bibk"))
        assertEquals(500, dao.findExact("en_US", "bibek")!!.frequency)
    }

    /** Beta report (5.8.0): saving "bibek" and editing it to "Bibek" left it "bibek". */
    @Test
    fun `a change in capitals only is saved`() = runBlocking {
        val dao = dev.omakey.core.predict.eval.InMemoryWordDao(listOf(row("bibek", 300, true, 10)))
        dao.setDisplay("en_US", "bibek", "Bibek")
        assertEquals("Bibek", dao.findExact("en_US", "bibek")!!.display)
        dao.setDisplay("en_US", "bibek", null)
        assertNull(dao.findExact("en_US", "bibek")!!.display)
    }
}
