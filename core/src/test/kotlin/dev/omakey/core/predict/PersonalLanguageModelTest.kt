package dev.omakey.core.predict

import dev.omakey.core.predict.eval.TestLanguageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalLanguageModelTest {

    private var now = 1_700_000_000_000L
    private val model = TestLanguageModel.load()
    private val personal = PersonalLanguageModel { now }.apply { load(emptyList(), model) }

    private fun advanceDays(days: Long) {
        now += days * 86_400_000L
    }

    // --- the property that makes implicit learning safe -----------------------------------------

    @Test
    fun `a word typed once is not trusted`() {
        // The bug this guards against shipped: learning on every word boundary marked uncaught
        // typos "known", making them immune to correction forever. Ranking may notice a word
        // immediately; immunity has to be earned.
        personal.record("zzqq", explicit = false)
        assertTrue(personal.contains("zzqq"))
        assertFalse(personal.isTrusted("zzqq"))
    }

    @Test
    fun `a word typed repeatedly earns trust`() {
        repeat(3) { personal.record("kubernetes", explicit = false) }
        assertTrue(personal.isTrusted("kubernetes"))
    }

    @Test
    fun `an explicit save is trusted immediately`() {
        // The user said so; nothing further to prove.
        personal.record("bbk", explicit = true)
        assertTrue(personal.isTrusted("bbk"))
        assertTrue(personal.isExplicit("bbk"))
    }

    @Test
    fun `implicit typing does not make a word removable by unlearning`() {
        // Swipe-up-to-unlearn is only meant to reverse a deliberate save. A casually typed word
        // must not present itself as something the user chose to add.
        personal.record("kubernetes", explicit = false)
        assertFalse(personal.isExplicit("kubernetes"))
    }

    @Test
    fun `explicitness survives later casual typing`() {
        personal.record("bbk", explicit = true)
        personal.record("bbk", explicit = false)
        assertTrue(personal.isExplicit("bbk"))
    }

    // --- ranking --------------------------------------------------------------------------------

    @Test
    fun `a personally typed word outranks a corpus word of similar rarity`() {
        // This is the whole point, and exactly what the previous design could not do: personal
        // counts went into the same column as seeded frequencies running to 60,000, so a saved
        // word arrived with a count of 1 and lost to words the user had never typed.
        val rare = "helot"
        val rareId = model.indexOf(rare)
        val baseline = model.unigramLogProbability(rareId)

        personal.record("kubernetes", explicit = false)
        personal.record("kubernetes", explicit = false)
        val boosted = personal.adjust("kubernetes", baseline)

        assertTrue("personal word should outscore its corpus baseline", boosted > baseline)
    }

    @Test
    fun `words the model has never seen are returned unchanged`() {
        val baseline = -9f
        assertEquals(baseline, personal.adjust("somethingunlearned", baseline), 0.0001f)
    }

    @Test
    fun `an empty model changes nothing`() {
        val baseline = -7.5f
        assertTrue(personal.isEmpty)
        assertEquals(baseline, personal.adjust("anything", baseline), 0f)
        assertEquals(baseline, personal.adjustById(model.indexOf("the"), baseline), 0f)
    }

    // --- what is worth learning -----------------------------------------------------------------

    @Test
    fun `common corpus words are not learned implicitly`() {
        // Re-counting "the" teaches nothing the corpus doesn't say better, and would spend the
        // capacity budget on words that need no help.
        assertNull(personal.record("the", explicit = false))
        assertFalse(personal.contains("the"))
    }

    @Test
    fun `common corpus words can still be saved explicitly`() {
        assertNotNull(personal.record("the", explicit = true))
    }

    @Test
    fun `words outside the corpus are always worth learning`() {
        assertNotNull(personal.record("zzqqxx", explicit = false))
    }

    // --- recency --------------------------------------------------------------------------------

    @Test
    fun `counts decay so stale vocabulary stops competing`() {
        repeat(4) { personal.record("kubernetes", explicit = false) }
        val fresh = personal.adjust("kubernetes", -12f)

        advanceDays(365)
        val stale = personal.adjust("kubernetes", -12f)

        assertTrue("a year-old word should score lower ($stale) than a fresh one ($fresh)", stale < fresh)
    }

    @Test
    fun `decay eventually withdraws trust from an implicitly learned word`() {
        repeat(3) { personal.record("kubernetes", explicit = false) }
        assertTrue(personal.isTrusted("kubernetes"))
        advanceDays(365)
        assertFalse("trust earned by typing should lapse once the word stops being used",
            personal.isTrusted("kubernetes"))
    }

    @Test
    fun `an explicit save keeps its trust regardless of age`() {
        // The user made a deliberate statement about this word; time doesn't retract it.
        personal.record("bbk", explicit = true)
        advanceDays(3650)
        assertTrue(personal.isTrusted("bbk"))
    }

    // --- capacity -------------------------------------------------------------------------------

    @Test
    fun `capacity is bounded and explicit saves are evicted last`() {
        personal.record("keepthisword", explicit = true)
        repeat(PersonalLanguageModel.MAX_ENTRIES + 200) { index ->
            personal.record("zzfiller$index", explicit = false)
        }
        assertTrue(personal.size <= PersonalLanguageModel.MAX_ENTRIES)
        assertTrue("an explicit save should outlive filler", personal.contains("keepthisword"))
    }

    // --- completion -----------------------------------------------------------------------------

    @Test
    fun `matching offers learned words by prefix, most used first`() {
        personal.record("kubernetes", explicit = false)
        repeat(3) { personal.record("kubectl", explicit = false) }
        assertEquals(listOf("kubectl", "kubernetes"), personal.matching("kub", 5))
    }

    @Test
    fun `an empty prefix matches nothing`() {
        personal.record("kubernetes", explicit = false)
        // With no evidence the user is reaching for one of their own words, promoting them over
        // the language model's actual prediction would be noise.
        assertTrue(personal.matching("", 5).isEmpty())
    }

    @Test
    fun `forget removes a word entirely`() {
        personal.record("bbk", explicit = true)
        personal.forget("bbk")
        assertFalse(personal.contains("bbk"))
        assertFalse(personal.isTrusted("bbk"))
    }
}
