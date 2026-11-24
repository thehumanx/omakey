package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactModeTest {

    @Test
    fun `off draws every row`() =
        assertSame(Layouts.QwertyEnUS.rows, CompactMode.rows(Layouts.QwertyEnUS, compact = false))

    @Test
    fun `on drops the letter layout's bottom row`() =
        assertEquals(Layouts.QwertyEnUS.rows.dropLast(1), CompactMode.rows(Layouts.QwertyEnUS, compact = true))

    @Test
    fun `symbols pages keep every row`() {
        assertSame(Layouts.Symbols1.rows, CompactMode.rows(Layouts.Symbols1, compact = true))
        assertSame(Layouts.Symbols2.rows, CompactMode.rows(Layouts.Symbols2, compact = true))
    }

    @Test
    fun `number pads keep every row`() = NumberPads.IDS.forEach {
        assertFalse(CompactMode.applies(compact = true, layoutId = it))
    }

    @Test
    fun `a single-row layout is left alone`() {
        val one = Layouts.QwertyEnUS.copy(rows = Layouts.QwertyEnUS.rows.take(1))
        assertSame(one.rows, CompactMode.rows(one, compact = true))
    }

    @Test
    fun `swipe right types space while compact applies even with the setting off`() =
        assertTrue(CompactMode.swipeRightTypesSpace(setting = false, compactApplies = true))

    @Test
    fun `swipe right follows the setting when compact is off`() {
        assertFalse(CompactMode.swipeRightTypesSpace(setting = false, compactApplies = false))
        assertTrue(CompactMode.swipeRightTypesSpace(setting = true, compactApplies = false))
    }

    @Test
    fun `compact is off by default`() = assertFalse(LayoutSettings().compactMode)

    @Test
    fun `compact is active when on and nothing overrides it`() =
        assertTrue(CompactMode.active(compact = true, accessibleMode = false, tilePanelOpen = false))

    @Test
    fun `accessible mode never compacts, since it has no swipe to type a space`() =
        assertFalse(CompactMode.active(compact = true, accessibleMode = true, tilePanelOpen = false))

    @Test
    fun `quick access and the language picker open at full height so their tiles fit`() =
        assertFalse(CompactMode.active(compact = true, accessibleMode = false, tilePanelOpen = true))

    @Test
    fun `off stays off`() =
        assertFalse(CompactMode.active(compact = false, accessibleMode = false, tilePanelOpen = false))
}
