package dev.omakey.core.predict

import dev.omakey.core.predict.eval.InMemoryWordDao
import dev.omakey.core.predict.eval.TestLanguageModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A spelling the user saved reaches the database and stays there when the word is typed again. */
class SavedSpellingPersistenceTest {

    private val dao = InMemoryWordDao()
    private val engine = NgramPredictionEngine(TestLanguageModel.load(), dao, PersonalLanguageModel(), "en_US")

    @Test
    fun `a word saved with capitals is stored exactly as written`() = runBlocking {
        engine.saveWord("iPhone")
        assertEquals("iPhone", dao.findExact("en_US", "iphone")!!.display)
        assertEquals("iPhone", engine.spellingOf("iphone"))
    }

    @Test
    fun `typing the word again keeps the saved spelling in the database`() = runBlocking {
        engine.saveWord("iPhone")
        repeat(3) { engine.recordAcceptedWord("iphone", previousWord = null) }
        assertEquals("iPhone", dao.findExact("en_US", "iphone")!!.display)
    }

    @Test
    fun `a word saved in lowercase has no fixed spelling`() = runBlocking {
        engine.saveWord("bibek")
        assertNull(dao.findExact("en_US", "bibek")!!.display)
        assertNull(engine.spellingOf("bibek"))
    }
}
