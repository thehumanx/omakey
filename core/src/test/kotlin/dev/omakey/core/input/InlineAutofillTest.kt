package dev.omakey.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineAutofillTest {

    @Test
    fun `asks only on Android 11+, with the setting on and somewhere to show the chips`() {
        assertTrue(InlineAutofill.shouldRequest(30, enabled = true, suggestionsPageShown = true))
        assertTrue(InlineAutofill.shouldRequest(35, enabled = true, suggestionsPageShown = true))
        assertFalse(InlineAutofill.shouldRequest(29, enabled = true, suggestionsPageShown = true))
        assertFalse(InlineAutofill.shouldRequest(35, enabled = false, suggestionsPageShown = true))
        assertFalse(InlineAutofill.shouldRequest(35, enabled = true, suggestionsPageShown = false))
    }

    @Test
    fun `a chip leaves room for the pager button beside it`() {
        // 1080px strip, 2×44dp buttons + 8dp padding at 3x, a 44dp pager button, 6dp gap.
        assertEquals(1080 - 288 - 132 - 18, InlineAutofill.maxChipWidth(1080, reserved = 288, trailing = 132, gap = 18, minWidth = 102))
        // A strip too narrow for anything still gets icon-sized chips, never a negative width.
        assertEquals(102, InlineAutofill.maxChipWidth(300, reserved = 288, trailing = 132, gap = 18, minWidth = 102))
    }

    @Test
    fun `chips that all fit are one page`() {
        assertEquals(listOf(0..2), InlineAutofill.chipPages(listOf(300, 300, 100), available = 740, gap = 18, pagerButton = 132))
    }

    @Test
    fun `overflowing chips split into pages of whole chips, leaving room for the pager button`() {
        // 740px row: three 300px chips can't all fit; with a 132px button + gap, 590px per page.
        val pages = InlineAutofill.chipPages(listOf(300, 300, 300, 102), available = 740, gap = 18, pagerButton = 132)
        assertEquals(listOf(0..0, 1..1, 2..3), pages)
        for (page in pages) {
            val used = page.sumOf { listOf(300, 300, 300, 102)[it] } + 18 * (page.count() - 1)
            assertTrue(used <= 740 - 18 - 132)
        }
    }

    @Test
    fun `a chip wider than a page still gets one, alone`() {
        assertEquals(listOf(0..0, 1..1), InlineAutofill.chipPages(listOf(900, 100), available = 740, gap = 18, pagerButton = 132))
        assertEquals(emptyList<IntRange>(), InlineAutofill.chipPages(emptyList(), available = 740, gap = 18, pagerButton = 132))
    }

    @Test
    fun `pinned chips go last, the rest keep their order`() {
        val items = listOf("pin1", "a", "b", "pin2", "c")
        assertEquals(listOf("a", "b", "c", "pin1", "pin2"), InlineAutofill.pinnedLast(items) { it.startsWith("pin") })
    }

    @Test
    fun `chips publish once all have arrived, in response order, failures dropped`() {
        val published = mutableListOf<List<String>>()
        val collector = InlineChipCollector<String> { published += it }
        val batch = collector.start(3)
        batch.deliver(2, "c")
        batch.deliver(0, "a")
        assertTrue(published.isEmpty())
        batch.deliver(1, null)
        assertEquals(listOf(listOf("a", "c")), published)
    }

    @Test
    fun `a newer response or a clear makes an older batch's late chips go nowhere`() {
        val published = mutableListOf<List<String>>()
        val collector = InlineChipCollector<String> { published += it }
        val old = collector.start(1)
        val new = collector.start(1)
        old.deliver(0, "old")
        new.deliver(0, "new")
        assertEquals(listOf(listOf("new")), published)

        val stale = collector.start(1)
        collector.clear()
        stale.deliver(0, "late")
        assertEquals(listOf(listOf("new"), emptyList()), published)
    }

    @Test
    fun `an empty response clears at once`() {
        val published = mutableListOf<List<String>>()
        InlineChipCollector<String> { published += it }.start(0)
        assertEquals(listOf(emptyList<String>()), published)
    }
}
