package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolsPageTest {

    private val fleksyLetters = Layouts.QwertyEnUS.withSymbolsPage(Layouts.ClassicSymbols1, Layouts.Symbols1)
    private fun secondary(label: String) = fleksyLetters.rows.flatMap { it.keys }.first { it.label == label }.secondarySymbol

    @Test
    fun `each letter offers the symbol under it on the Fleksy page`() {
        val homeRow = "asdfghjkl".map { secondary(it.toString()) }
        assertEquals(listOf("&", "/", ":", ";", "(", ")", "-", "+", "$"), homeRow)
        val bottomRow = "zxcvbnm".map { secondary(it.toString()) }
        assertEquals(listOf(".", ",", "?", "!", "'", "\"", "*"), bottomRow)
    }

    @Test
    fun `digits and accents are untouched`() {
        assertEquals("1", secondary("q"))
        val e = fleksyLetters.rows.flatMap { it.keys }.first { it.label == "e" }
        assertEquals(listOf("è", "é", "ê", "ë", "3"), e.popupChars)
    }

    @Test
    fun `the period key keeps its own punctuation list`() {
        val period = { layout: KeyboardLayout -> layout.rows.last().keys.first { it.label == "." }.popupChars }
        assertEquals(period(Layouts.QwertyEnUS), period(fleksyLetters))
    }

    @Test
    fun `the same page changes nothing`() {
        assertSame(Layouts.QwertyEnUS, Layouts.QwertyEnUS.withSymbolsPage(Layouts.Symbols1, Layouts.Symbols1))
    }

    @Test
    fun `nothing reachable on the classic pages becomes unreachable`() {
        fun symbols(vararg layouts: KeyboardLayout) = layouts.flatMap { l -> l.rows.flatMap { it.keys } }
            .filter { it.keyType == KeyType.CHARACTER }.map { it.committedText }.toSet()
        val before = symbols(Layouts.ClassicSymbols1, Layouts.Symbols2)
        val after = symbols(Layouts.Symbols1, Layouts.Symbols2)
        assertTrue("lost: ${before - after}", after.containsAll(before))
    }

    /** Closed-beta report: there was no "%" anywhere on the grids. It lived only in the number tab
     * shown above the symbols page, which can be turned off — and this test used to count that tab,
     * which is how the gap went unnoticed. Only the grids count now. */
    @Test
    fun `every printable ASCII symbol is on the symbols grids`() {
        val ascii = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~".toSet()
        for (first in listOf(Layouts.Symbols1, Layouts.ClassicSymbols1)) {
            val onGrid = listOf(first, Layouts.Symbols2).flatMap { l -> l.rows.flatMap { it.keys } }
                .filter { it.keyType == KeyType.CHARACTER }.flatMap { it.committedText.toList() }.toSet()
            assertEquals("missing with ${first.rows[1].keys.first().label}…", emptySet<Char>(), ascii - onGrid)
        }
    }

    @Test
    fun `symbols pages are valid, have a home row, and keep the letters' row widths`() {
        for (page in listOf(Layouts.Symbols1, Layouts.ClassicSymbols1, Layouts.Symbols2)) {
            assertEquals(emptyList<String>(), page.validate())
            assertEquals(1, page.homeRow)
        }
        // The Fleksy page lines up with the letters row for row (the classic pages never did on
        // their ten-key second row).
        Layouts.Symbols1.rows.zip(Layouts.QwertyEnUS.rows).drop(1).forEach { (symbolRow, letterRow) ->
            assertEquals(letterRow.keys.sumOf { it.widthWeight.toDouble() }, symbolRow.keys.sumOf { it.widthWeight.toDouble() }, 0.01)
        }
    }
}
