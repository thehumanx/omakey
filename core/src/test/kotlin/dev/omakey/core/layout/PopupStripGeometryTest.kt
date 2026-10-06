package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class PopupStripGeometryTest {

    private fun strip(count: Int) = PopupStripGeometry.of(widthPx = 400f, paddingPx = 8f, count = count, minCellPx = 36f, maxCellPx = 56f)

    @Test
    fun `cells are centred`() {
        val g = strip(2)
        assertEquals(56f, g.cellPx)
        assertEquals(200f - 56f, g.startPx)
        assertEquals(200f + 56f, g.endPx)
    }

    @Test
    fun `the cell under the finger is the one picked`() {
        val g = strip(3) // 168 wide, 116..284
        assertEquals(0, g.indexAt(120f))
        assertEquals(1, g.indexAt(200f))
        assertEquals(2, g.indexAt(280f))
    }

    @Test
    fun `a finger beyond either end picks that end's cell`() {
        val g = strip(3)
        assertEquals(0, g.indexAt(0f))
        assertEquals(2, g.indexAt(399f))
    }

    @Test
    fun `cells shrink to fit, down to the minimum`() {
        val g = strip(10)
        assertEquals(38.4f, g.cellPx, 0.001f)
        assertEquals(8f, g.startPx, 0.001f)
        assertEquals(10, PopupStripGeometry.maxCells(400f, 8f, 36f))
    }
}
