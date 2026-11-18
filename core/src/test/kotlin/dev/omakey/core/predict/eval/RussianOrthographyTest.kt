package dev.omakey.core.predict.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RussianOrthographyTest {

    /** Every misspelling [RussianOrthography.misspell] can produce for [word], over many seeds. */
    private fun outcomes(word: String): Set<String> =
        (0 until 400).mapNotNull { RussianOrthography.misspell(word, Random(it)) }.toSet()

    @Test
    fun `й and и are confused`() {
        assertTrue(outcomes("мой").isEmpty()) // under four letters: skipped
        assertTrue(outcomes("войти").contains("воити"))
    }

    @Test
    fun `hard and soft signs are dropped`() {
        assertTrue(outcomes("объявление").contains("обявление"))
        assertTrue(outcomes("семья").contains("семя"))
    }

    @Test
    fun `a doubled consonant is collapsed`() {
        assertTrue(outcomes("класс").contains("клас"))
    }

    @Test
    fun `unstressed vowels are confused`() {
        assertTrue(outcomes("молоко").contains("малоко"))
        assertTrue(outcomes("сидеть").contains("сидить"))
    }

    @Test
    fun `тся and ться are confused both ways`() {
        assertTrue(outcomes("учится").contains("учиться"))
        assertTrue(outcomes("учиться").contains("учится"))
    }

    @Test
    fun `е and ё are an equivalence, never an error`() {
        assertTrue(outcomes("актёр").none { it == "актер" })
    }

    @Test
    fun `nothing to misspell gives null`() {
        assertNull(RussianOrthography.misspell("да", Random(1)))
    }

    @Test
    fun `the same seed gives the same misspelling`() {
        assertEquals(RussianOrthography.misspell("объявление", Random(66)), RussianOrthography.misspell("объявление", Random(66)))
    }
}
