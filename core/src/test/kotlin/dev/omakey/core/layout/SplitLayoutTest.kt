package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLayoutTest {

    private val qwerty = Layouts.QwertyEnUS.rows

    private fun ch(label: String) = KeyDefinition(label, label.first().code)
    private fun spacer(weight: Float) = KeyDefinition("", 0, widthWeight = weight, keyType = KeyType.SPACER)

    /** The labels either side of the (single) gap spacer. */
    private fun halves(row: KeyRow): Pair<List<String>, List<String>> {
        val gap = row.keys.indexOfFirst { it.keyType == KeyType.SPACER && it.code == SplitLayout.GAP_KEY_CODE && it.label.isEmpty() && isGap(row, it) }
        return row.keys.subList(0, gap).filter { it.keyType != KeyType.SPACER }.map { it.label } to
            row.keys.subList(gap + 1, row.keys.size).filter { it.keyType != KeyType.SPACER }.map { it.label }
    }

    /** The gap is the widest spacer in the row. */
    private fun isGap(row: KeyRow, key: KeyDefinition) =
        key === row.keys.filter { it.keyType == KeyType.SPACER }.maxBy { it.widthWeight }

    private fun gapWeight(row: KeyRow) = row.keys.filter { it.keyType == KeyType.SPACER }.maxOf { it.widthWeight }
    private fun total(row: KeyRow) = row.keys.sumOf { it.widthWeight.toDouble() }.toFloat()

    private fun halfWeights(row: KeyRow): Pair<Float, Float> {
        val gapIndex = row.keys.indexOfFirst { isGap(row, it) }
        return row.keys.subList(0, gapIndex).sumOf { it.widthWeight.toDouble() }.toFloat() to
            row.keys.subList(gapIndex + 1, row.keys.size).sumOf { it.widthWeight.toDouble() }.toFloat()
    }

    private fun splittableRows() = Layouts.all.flatMap { layout ->
        layout.rows.filter { row -> row.keys.none { it.code == SpecialKeyCode.SPACE } && row.keys.any { it.keyType == KeyType.CHARACTER } }
    }

    @Test
    fun `QWERTY top row splits five and five`() =
        assertEquals(listOf("q", "w", "e", "r", "t") to listOf("y", "u", "i", "o", "p"), halves(SplitLayout.splitRow(qwerty[0], duplicateMiddle = true)))

    @Test
    fun `odd row repeats its middle letter like Gboard`() {
        assertEquals(listOf("a", "s", "d", "f", "g") to listOf("g", "h", "j", "k", "l"), halves(SplitLayout.splitRow(qwerty[1], duplicateMiddle = true)))
        assertEquals(listOf("⇧", "z", "x", "c", "v") to listOf("v", "b", "n", "m", "⌫"), halves(SplitLayout.splitRow(qwerty[2], duplicateMiddle = true)))
    }

    @Test
    fun `without duplicates the middle letter goes left and the halves still weigh the same`() {
        val row = SplitLayout.splitRow(qwerty[1], duplicateMiddle = false)
        assertEquals(listOf("a", "s", "d", "f", "g") to listOf("h", "j", "k", "l"), halves(row))
        val (left, right) = halfWeights(row)
        assertEquals(left, right, 1e-4f)
    }

    @Test
    fun `every split row's gap is a quarter of its width`() = listOf(true, false).forEach { dup ->
        splittableRows().forEach { original ->
            val row = SplitLayout.splitRow(original, dup)
            assertEquals("row ${original.keys.map { it.label }}", 0.25f, gapWeight(row) / total(row), 1e-4f)
        }
    }

    @Test
    fun `halves weigh the same in every split row`() = listOf(true, false).forEach { dup ->
        splittableRows().forEach { original ->
            val (left, right) = halfWeights(SplitLayout.splitRow(original, dup))
            assertEquals("row ${original.keys.map { it.label }}", left, right, 1e-4f)
        }
    }

    @Test
    fun `space row keeps one spacebar that spans the gap`() {
        val original = qwerty[3]
        val row = SplitLayout.splitRow(original, duplicateMiddle = true)
        assertEquals(original.keys.size, row.keys.size)
        assertTrue(row.keys.none { it.keyType == KeyType.SPACER })
        val w = total(original)
        assertEquals(4f + w / 3f, row.keys.single { it.code == SpecialKeyCode.SPACE }.widthWeight, 1e-4f)
    }

    @Test
    fun `eleven-letter row repeats the sixth`() {
        val keys = "йцукенгшщзх".map { ch(it.toString()) }
        assertEquals(
            "йцукен".map { it.toString() } to "нгшщзх".map { it.toString() },
            halves(SplitLayout.splitRow(KeyRow(keys), duplicateMiddle = true)),
        )
    }

    @Test
    fun `existing spacer indents stay on their side and are not letters`() {
        val keys = listOf(spacer(0.5f)) + "asdfghjkl".map { ch(it.toString()) } + spacer(0.5f)
        val row = SplitLayout.splitRow(KeyRow(keys), duplicateMiddle = false)
        assertEquals(listOf("a", "s", "d", "f", "g") to listOf("h", "j", "k", "l"), halves(row))
        assertEquals(KeyType.SPACER, row.keys.first().keyType)
        assertEquals(KeyType.SPACER, row.keys.last().keyType)
    }

    @Test
    fun `a lopsided row keeps its letters the same size and pads the short half`() {
        // Shift on the left, no backspace on the right (a pack layout can look like this).
        val keys = listOf(KeyDefinition("⇧", SpecialKeyCode.SHIFT, widthWeight = 1.5f, keyType = KeyType.SPECIAL)) +
            "zxcvbnmw".map { ch(it.toString()) }
        val row = SplitLayout.splitRow(KeyRow(keys), duplicateMiddle = true)
        row.keys.filter { it.keyType == KeyType.CHARACTER }.forEach { assertEquals(it.label, 1f, it.widthWeight, 0f) }
        val last = row.keys.last()
        assertEquals(KeyType.SPACER, last.keyType)
        assertEquals(1.5f, last.widthWeight, 1e-4f)
    }

    @Test
    fun `a single-letter row keeps the gap in the middle`() {
        val row = SplitLayout.splitRow(KeyRow(listOf(ch("a"))), duplicateMiddle = false)
        assertEquals(listOf(KeyType.CHARACTER, KeyType.SPACER, KeyType.SPACER), row.keys.map { it.keyType })
        assertEquals(1f, row.keys[0].widthWeight, 0f)
        assertEquals(1f, row.keys[2].widthWeight, 1e-4f)
    }

    @Test
    fun `a row with no letters is untouched`() {
        val row = KeyRow(listOf(KeyDefinition("⇧", SpecialKeyCode.SHIFT, keyType = KeyType.SPECIAL)))
        assertSame(row, SplitLayout.splitRow(row, duplicateMiddle = true))
    }

    @Test
    fun `number pads never split and symbol pages do`() {
        NumberPads.IDS.forEach { assertFalse(SplitLayout.applies(it)) }
        assertTrue(SplitLayout.applies(Layouts.Symbols1.id))
        assertTrue(SplitLayout.applies(Layouts.QwertyEnUS.id))
    }

    @Test
    fun `rows returns the same list when not split`() {
        assertSame(qwerty, SplitLayout.rows(Layouts.QwertyEnUS.id, qwerty, split = false, duplicateMiddle = true))
        assertSame(qwerty, SplitLayout.rows(NumberPads.NUMBER_ID, qwerty, split = true, duplicateMiddle = true))
    }

    @Test
    fun `rows splits every row when split`() {
        val rows = SplitLayout.rows(Layouts.QwertyEnUS.id, qwerty, split = true, duplicateMiddle = true)
        assertEquals(qwerty.size, rows.size)
        assertTrue(rows.take(3).all { r -> r.keys.any { it.keyType == KeyType.SPACER } })
    }

    @Test
    fun `docked wide screens split by default and narrow ones follow their own choice`() {
        assertTrue(SplitLayout.active(KeyboardPlacement.DOCKED, 600, splitOnWide = true, splitOnNarrow = false))
        assertFalse(SplitLayout.active(KeyboardPlacement.DOCKED, 599, splitOnWide = true, splitOnNarrow = false))
        assertTrue(SplitLayout.active(KeyboardPlacement.DOCKED, 599, splitOnWide = false, splitOnNarrow = true))
        assertFalse(SplitLayout.active(KeyboardPlacement.DOCKED, 900, splitOnWide = false, splitOnNarrow = true))
    }

    @Test
    fun `defaults split wide screens only, with the middle keys repeated`() {
        val s = LayoutSettings()
        assertTrue(s.splitOnWide)
        assertFalse(s.splitOnNarrow)
        assertTrue(s.splitDuplicateKeys)
    }

    @Test
    fun `the tile flips only the current screen class`() {
        val wide = SplitLayout.toggled(LayoutSettings(), screenWidthDp = 800)
        assertFalse(wide.splitOnWide)
        assertFalse(wide.splitOnNarrow)
        val narrow = SplitLayout.toggled(LayoutSettings(), screenWidthDp = 400)
        assertTrue(narrow.splitOnWide)
        assertTrue(narrow.splitOnNarrow)
    }

    @Test
    fun `the tile while floating docks and splits`() {
        val s = SplitLayout.toggled(LayoutSettings(placement = KeyboardPlacement.FLOATING, splitOnWide = false), screenWidthDp = 800)
        assertEquals(KeyboardPlacement.DOCKED, s.placement)
        assertTrue(s.splitOnWide)
    }

    @Test
    fun `the tile while one-handed docks and splits`() {
        val s = SplitLayout.toggled(LayoutSettings(placement = KeyboardPlacement.ONE_HANDED_LEFT), screenWidthDp = 400)
        assertEquals(KeyboardPlacement.DOCKED, s.placement)
        assertTrue(s.splitOnNarrow)
    }

    @Test
    fun `floating and one-handed never split`() = listOf(
        KeyboardPlacement.FLOATING, KeyboardPlacement.ONE_HANDED_LEFT, KeyboardPlacement.ONE_HANDED_RIGHT,
    ).forEach { assertFalse(SplitLayout.active(it, 900, splitOnWide = true, splitOnNarrow = true)) }
}
