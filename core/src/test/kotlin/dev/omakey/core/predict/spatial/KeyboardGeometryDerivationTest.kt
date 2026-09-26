package dev.omakey.core.predict.spatial

import dev.omakey.core.layout.KeyDefinition
import dev.omakey.core.layout.KeyRow
import dev.omakey.core.layout.KeyType
import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.Layouts
import dev.omakey.core.layout.SpecialKeyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeyboardGeometry.from] against the table it replaced. σ and the substitution cap were tuned on
 * the hand-written QWERTY coordinates, so the derived ones must match them exactly — a "close
 * enough" geometry is a silently retuned autocorrect.
 */
class KeyboardGeometryDerivationTest {

    /** The coordinates `KeyboardGeometry` hardcoded until AGENTS.md §66 Phase 2, copied verbatim. */
    private val legacyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val legacyRowOffset = floatArrayOf(0f, 0f, 1.5f)
    private val legacyKeyWidth = floatArrayOf(1f, 10f / 9f, 1f)

    @Test
    fun `derived QWERTY matches the hand-written table exactly`() {
        val geometry = KeyboardGeometry.from(Layouts.QwertyEnUS)
        for ((row, letters) in legacyRows.withIndex()) {
            for ((column, letter) in letters.withIndex()) {
                val expectedX = legacyRowOffset[row] + (column + 0.5f) * legacyKeyWidth[row]
                val (x, y) = geometry.centerOf(letter) ?: error("$letter missing")
                assertEquals("x of $letter", expectedX, x, 0f)
                assertEquals("y of $letter", row + 0.5f, y, 0f)
            }
        }
        assertEquals(26, geometry.keys.size)
    }

    @Test
    fun `the space row's punctuation key is not a letter key`() {
        assertNull(KeyboardGeometry.QWERTY.centerOf('.'))
    }

    @Test
    fun `uppercase input finds the lowercase key`() {
        assertEquals(KeyboardGeometry.QWERTY.centerOf('q'), KeyboardGeometry.QWERTY.centerOf('Q'))
    }

    @Test
    fun `a Spanish row gains ñ as a real neighbour of l`() {
        fun key(c: String) = KeyDefinition(c, c.first().code)
        val shift = KeyDefinition("⇧", SpecialKeyCode.SHIFT, widthWeight = 1.5f, keyType = KeyType.SPECIAL)
        val backspace = KeyDefinition("⌫", SpecialKeyCode.BACKSPACE, widthWeight = 1.5f, keyType = KeyType.SPECIAL)
        val spanish = KeyboardLayout(
            id = "test_es",
            rows = listOf(
                KeyRow("qwertyuiop".map { key(it.toString()) }),
                KeyRow("asdfghjklñ".map { key(it.toString()) }),
                KeyRow(listOf(shift) + "zxcvbnm".map { key(it.toString()) } + backspace),
            ),
        )
        val geometry = KeyboardGeometry.from(spanish)
        assertNotNull(geometry.centerOf('ñ'))
        assertTrue(geometry.areAdjacent('l', 'ñ'))
        assertTrue(geometry.areAdjacent('ñ', 'p'))
        assertFalse(geometry.areAdjacent('ñ', 'a'))
        // Row 1 now has 10 keys, so it no longer staggers against row 0.
        assertEquals(geometry.centerOf('q')!!.first, geometry.centerOf('a')!!.first, 0f)
    }

    @Test
    fun `Devanagari keys are looked up directly`() {
        fun key(c: String) = KeyDefinition(c, c.first().code)
        val layout = KeyboardLayout("test_ne", listOf(KeyRow(listOf("क", "ख", "ग").map(::key))))
        val geometry = KeyboardGeometry.from(layout)
        assertTrue(geometry.areAdjacent('क', 'ख'))
        assertEquals(4f, geometry.squaredDistance('क', 'ग'), 0f)
        assertEquals(KeyboardGeometry.UNRELATED, geometry.squaredDistance('क', 'a'), 0f)
    }
}
