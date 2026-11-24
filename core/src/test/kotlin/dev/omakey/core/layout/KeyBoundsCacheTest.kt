package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyBoundsCacheTest {

    private val twoRows = mapOf(0 to "q", 1 to "w", 2 to "gap", 3 to "e", 1000 to "a", 1001 to "s")

    @Test
    fun `a row that lost keys drops the extra entries`() =
        assertEquals(
            mapOf(0 to "q", 1 to "w", 1000 to "a", 1001 to "s"),
            KeyBoundsCache.merge(twoRows, rowIndex = 0, measured = mapOf(0 to "q", 1 to "w"), rowCount = 2),
        )

    @Test
    fun `rows beyond the drawn count are dropped`() =
        assertEquals(
            mapOf(0 to "q", 1 to "w", 2 to "gap", 3 to "e"),
            KeyBoundsCache.merge(twoRows, rowIndex = 0, measured = mapOf(0 to "q", 1 to "w", 2 to "gap", 3 to "e"), rowCount = 1),
        )

    @Test
    fun `an unchanged row returns null`() =
        assertNull(KeyBoundsCache.merge(twoRows, rowIndex = 1, measured = mapOf(0 to "a", 1 to "s"), rowCount = 2))

    @Test
    fun `other rows are kept`() =
        assertEquals(
            mapOf(0 to "q", 1 to "w", 2 to "gap", 3 to "e", 1000 to "z"),
            KeyBoundsCache.merge(twoRows, rowIndex = 1, measured = mapOf(0 to "z"), rowCount = 2),
        )

    // A split row repeats G: two entries with the same code. The copy under the finger is the one
    // to preview, not whichever the map happens to list first.
    private val splitRow = mapOf(1004 to "g", 1005 to "gap", 1006 to "g", 1007 to "h")

    @Test
    fun `the entry under the finger wins over another copy with the same code`() =
        assertEquals(1006, KeyBoundsCache.entryFor(splitRow, code = 'g'.code, preferred = 1006) { it.first().code })

    @Test
    fun `a preferred entry with another code is ignored`() =
        assertEquals(1007, KeyBoundsCache.entryFor(splitRow, code = 'h'.code, preferred = 1006) { it.first().code })

    @Test
    fun `no preference finds the code anywhere`() =
        assertEquals(1007, KeyBoundsCache.entryFor(splitRow, code = 'h'.code, preferred = null) { it.first().code })

    @Test
    fun `a missing code finds nothing`() =
        assertNull(KeyBoundsCache.entryFor(splitRow, code = 'x'.code, preferred = 1006) { it.first().code })
}
