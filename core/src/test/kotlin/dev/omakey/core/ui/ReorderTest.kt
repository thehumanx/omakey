package dev.omakey.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderTest {
    // Three rows 64 tall with 8 between them: centres at 32, 104, 176.
    private val heights = listOf(64f, 64f, 64f)
    private val gap = 8f

    @Test fun `no drag keeps the slot`() = assertEquals(1, Reorder.slotFor(1, 0f, heights, gap))

    @Test fun `a small drag that does not cross the neighbour's centre keeps the slot`() =
        assertEquals(1, Reorder.slotFor(1, 71f, heights, gap))

    @Test fun `crossing the neighbour's centre moves one down`() =
        assertEquals(2, Reorder.slotFor(1, 73f, heights, gap))

    @Test fun `crossing upwards moves one up`() = assertEquals(0, Reorder.slotFor(1, -73f, heights, gap))

    @Test fun `a long drag moves several and stops at the ends`() {
        assertEquals(2, Reorder.slotFor(0, 1000f, heights, gap))
        assertEquals(0, Reorder.slotFor(2, -1000f, heights, gap))
    }

    @Test fun `rows of different heights use their own centres`() {
        // Row 0 is 100 tall (centre 50), row 1 is 40 tall (top 108, centre 128).
        val mixed = listOf(100f, 40f)
        assertEquals(1, Reorder.slotFor(1, -77f, mixed, gap))
        assertEquals(0, Reorder.slotFor(1, -79f, mixed, gap))
    }

    @Test fun `moved reorders a list`() {
        assertEquals(listOf("b", "c", "a"), Reorder.moved(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), Reorder.moved(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b", "c"), Reorder.moved(listOf("a", "b", "c"), 1, 1))
    }

    @Test fun `how far the other rows shift while one is dragged`() {
        // Dragging row 0 to slot 2: rows 1 and 2 slide up by the dragged row's height plus the gap.
        assertEquals(-72f, Reorder.shiftFor(index = 1, from = 0, to = 2, draggedHeight = 64f, gap = gap))
        assertEquals(-72f, Reorder.shiftFor(index = 2, from = 0, to = 2, draggedHeight = 64f, gap = gap))
        // Dragging row 2 to slot 0: rows 0 and 1 slide down.
        assertEquals(72f, Reorder.shiftFor(index = 0, from = 2, to = 0, draggedHeight = 64f, gap = gap))
        // Rows outside the span stay put.
        assertEquals(0f, Reorder.shiftFor(index = 2, from = 0, to = 1, draggedHeight = 64f, gap = gap))
    }
}
