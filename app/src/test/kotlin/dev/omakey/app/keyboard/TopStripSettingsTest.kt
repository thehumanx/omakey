package dev.omakey.app.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TopStripSettingsTest {

    @Test
    fun `suggestions always come first, then the user's order`() =
        assertEquals(
            listOf(TopStripTab.SUGGESTIONS, TopStripTab.TOOLS, TopStripTab.SNIPPETS, TopStripTab.NUMBERS),
            TopStripSettings(order = listOf(TopStripTab.TOOLS, TopStripTab.SNIPPETS, TopStripTab.NUMBERS)).pages,
        )

    @Test
    fun `a stored order missing a page gets it at the end, and junk is ignored`() =
        assertEquals(
            listOf(TopStripTab.NUMBERS, TopStripTab.SNIPPETS, TopStripTab.TOOLS),
            TopStripSettings(order = listOf(TopStripTab.NUMBERS, TopStripTab.SUGGESTIONS, TopStripTab.NUMBERS)).movableOrder,
        )

    @Test
    fun `hidden pages are left out but keep their place`() =
        assertEquals(
            listOf(TopStripTab.SUGGESTIONS, TopStripTab.NUMBERS),
            TopStripSettings(hidden = setOf(TopStripTab.SNIPPETS, TopStripTab.TOOLS)).pages,
        )

    @Test
    fun `moving a page swaps it with its neighbour and stops at the ends`() {
        val s = TopStripSettings()
        assertEquals(listOf(TopStripTab.NUMBERS, TopStripTab.SNIPPETS, TopStripTab.TOOLS), s.moved(TopStripTab.NUMBERS, -1))
        assertEquals(listOf(TopStripTab.SNIPPETS, TopStripTab.NUMBERS, TopStripTab.TOOLS), s.moved(TopStripTab.SNIPPETS, -1))
        assertEquals(listOf(TopStripTab.SNIPPETS, TopStripTab.NUMBERS, TopStripTab.TOOLS), s.moved(TopStripTab.TOOLS, +1))
    }

    @Test
    fun `suggestions can't be moved`() =
        assertEquals(TopStripSettings().movableOrder, TopStripSettings().moved(TopStripTab.SUGGESTIONS, +1))
}
