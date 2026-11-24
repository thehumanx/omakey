package dev.omakey.core.predict

import dev.omakey.core.predict.eval.InMemoryWordDao
import dev.omakey.core.predict.eval.TestLanguageModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A word the user put back after autocorrect replaced it, then kept, is learned at once.
 *
 * The keyboard decides *when* that happened (`KeyboardViewModel`, not testable on the JVM); this
 * pins what it does to the model, through the same engine, personal model and index the keyboard
 * wires together.
 */
class KeptOverCorrectionTest {

    private var now = 1_700_000_000_000L
    private val model = TestLanguageModel.load()
    private val personal = PersonalLanguageModel { now }.apply { load(emptyList(), model) }
    private val dao = InMemoryWordDao()
    private val engine = NgramPredictionEngine(model, dao, personal, "en_US")
    private val index = AutocorrectIndex().apply { load(model, personal) }

    private fun advanceDays(days: Long) {
        now += days * 86_400_000L
    }

    @Test
    fun `a word kept over a correction is known straight away`() = runBlocking {
        assertFalse(index.isKnown("rohan"))
        engine.recordKeptOverCorrection("Rohan")
        assertTrue(index.isKnown("rohan"))
        // Known means autocorrect leaves it alone.
        assertNull(index.correct("rohan"))
    }

    @Test
    fun `one ordinary use is not enough, which is why keeping needs its own weight`() = runBlocking {
        engine.recordAcceptedWord("rohan", previousWord = null)
        assertFalse(index.isKnown("rohan"))
    }

    @Test
    fun `it is not an explicit save, but a swipe up can still unlearn it`() = runBlocking {
        engine.recordKeptOverCorrection("rohan")
        val stored = dao.findExact("en_US", "rohan")!!
        assertFalse(stored.explicit)
        assertTrue(index.isUserAdded("rohan"))
        index.unlearn("rohan")
        assertFalse(index.isKnown("rohan"))
    }

    @Test
    fun `an accidental keep fades if the word is never used again`() = runBlocking {
        engine.recordKeptOverCorrection("teh")
        assertTrue(index.isKnown("teh"))
        advanceDays(30)
        assertFalse(index.isKnown("teh"))
    }

    @Test
    fun `using the word again keeps it learned`() = runBlocking {
        engine.recordKeptOverCorrection("rohan")
        advanceDays(15)
        engine.recordAcceptedWord("rohan", previousWord = null)
        advanceDays(15)
        assertTrue(index.isKnown("rohan"))
    }

    @Test
    fun `the weight sits between one use and an explicit save`() {
        assertTrue(PersonalLanguageModel.KEPT_OVER_CORRECTION_WEIGHT > PersonalLanguageModel.IMPLICIT_TRUST_THRESHOLD)
        assertEquals(
            PersonalLanguageModel.KEPT_OVER_CORRECTION_WEIGHT,
            personal.record("zoya", explicit = false, weight = PersonalLanguageModel.KEPT_OVER_CORRECTION_WEIGHT)!!.count,
        )
    }
}
