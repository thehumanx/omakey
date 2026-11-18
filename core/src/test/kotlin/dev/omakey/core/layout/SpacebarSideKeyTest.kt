package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SpacebarSideKeyTest {

    private fun nepaliLike() = Layouts.QwertyEnUS.copy(
        rows = Layouts.QwertyEnUS.rows.dropLast(1) + KeyRow(
            Layouts.QwertyEnUS.rows.last().keys.map {
                if (it.label == ".") KeyDefinition("।", "।".first().code, popupChars = listOf("?", "!")) else it
            },
        ),
    )

    private fun sideKey(layout: KeyboardLayout): KeyDefinition {
        val bottom = layout.rows.last().keys
        val space = bottom.indexOfFirst { it.code == SpecialKeyCode.SPACE }
        return bottom.drop(space + 1).first { it.keyType == KeyType.CHARACTER }
    }

    @Test
    fun `the default changes nothing`() {
        assertSame(Layouts.QwertyEnUS, Layouts.QwertyEnUS.withSpacebarSideKey(DEFAULT_SPACEBAR_SIDE_KEY))
        assertSame(Layouts.QwertyEnUS, Layouts.QwertyEnUS.withSpacebarSideKey("."))
    }

    @Test
    fun `the default keeps a language's own key`() {
        val nepali = nepaliLike()
        assertSame(nepali, nepali.withSpacebarSideKey(DEFAULT_SPACEBAR_SIDE_KEY))
    }

    @Test
    fun `a comma replaces the period and the period moves to long-press`() {
        val before = sideKey(Layouts.QwertyEnUS)
        val after = sideKey(Layouts.QwertyEnUS.withSpacebarSideKey(","))
        assertEquals(",", after.label)
        assertEquals(",", after.committedText)
        assertEquals(before.code, after.code) // taps and long-press are found by code
        assertEquals(listOf(".") + before.popupChars.filter { it != "," }, after.popupChars)
    }

    @Test
    fun `nepali danda stays reachable`() {
        val after = sideKey(nepaliLike().withSpacebarSideKey(","))
        assertEquals(",", after.committedText)
        assertEquals(listOf("।", "?", "!"), after.popupChars)
    }

    @Test
    fun `a layout without a key right of the spacebar is unchanged`() {
        val noSideKey = Layouts.QwertyEnUS.copy(
            rows = Layouts.QwertyEnUS.rows.dropLast(1) + KeyRow(Layouts.QwertyEnUS.rows.last().keys.filter { it.label != "." }),
        )
        assertSame(noSideKey, noSideKey.withSpacebarSideKey(","))
    }

    @Test
    fun `an emoji works`() {
        assertEquals("😀", sideKey(Layouts.QwertyEnUS.withSpacebarSideKey("😀")).committedText)
    }

    @Test
    fun `only one visible character is accepted`() {
        assertEquals(",", sanitizeSpacebarSideKey(","))
        assertEquals("😀", sanitizeSpacebarSideKey("😀"))
        assertEquals("👍🏽", sanitizeSpacebarSideKey("👍🏽"))
        assertEquals(DEFAULT_SPACEBAR_SIDE_KEY, sanitizeSpacebarSideKey(""))
        assertEquals(DEFAULT_SPACEBAR_SIDE_KEY, sanitizeSpacebarSideKey(" "))
        assertEquals(DEFAULT_SPACEBAR_SIDE_KEY, sanitizeSpacebarSideKey("ab"))
        assertEquals(DEFAULT_SPACEBAR_SIDE_KEY, sanitizeSpacebarSideKey(null))
    }
}
