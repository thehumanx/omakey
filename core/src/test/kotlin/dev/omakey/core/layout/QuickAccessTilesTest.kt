package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAccessTilesTest {

    @Test fun `nothing stored gives the default tiles in order, new extras off`() {
        val s = QuickAccessTiles.parse(storedOrder = null, storedHidden = null)
        assertEquals(QuickTileId.entries, s.order)
        assertEquals(QuickTileId.entries.filter { it.shownByDefault }, s.shown)
        assertFalse(QuickTileId.CLIPBOARD in s.shown)
    }

    @Test fun `stored order and hidden tiles are kept`() {
        val s = QuickAccessTiles.parse("SPLIT,THEME", setOf("THEME"))
        assertEquals(listOf(QuickTileId.SPLIT, QuickTileId.THEME), s.order.take(2))
        assertFalse(QuickTileId.THEME in s.shown)
        assertTrue(QuickTileId.SPLIT in s.shown)
    }

    @Test fun `a tile added in a later version joins at the end with its default visibility`() {
        val s = QuickAccessTiles.parse("SPLIT,THEME", emptySet())
        assertEquals(QuickTileId.entries.size, s.order.size)
        assertTrue(QuickTileId.INCOGNITO in s.shown)
        assertFalse(QuickTileId.EMOJI in s.shown)
    }

    @Test fun `unknown stored names are ignored`() {
        val s = QuickAccessTiles.parse("GONE,SPLIT", setOf("GONE"))
        assertEquals(QuickTileId.SPLIT, s.order.first())
    }

    @Test fun `moving reorders and the last shown tile can't be hidden`() {
        val s = QuickAccessTiles.parse(null, null)
        assertEquals(QuickTileId.SPLIT, s.moved(QuickTileId.entries.indexOf(QuickTileId.SPLIT), 0).order.first())
        val onlyOne = s.copy(hidden = QuickTileId.entries.toSet() - QuickTileId.SETTINGS)
        assertEquals(onlyOne, onlyOne.withShown(QuickTileId.SETTINGS, false))
        assertTrue(QuickTileId.THEME in onlyOne.withShown(QuickTileId.THEME, true).shown)
    }
}
