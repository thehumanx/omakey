package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardLayoutTest {

    @Test
    fun `computeKeyWidthsPx distributes width proportionally to widthWeight`() {
        val row = KeyRow(
            listOf(
                KeyDefinition("a", 'a'.code, widthWeight = 1f),
                KeyDefinition("b", 'b'.code, widthWeight = 1f),
                KeyDefinition("space", SpecialKeyCode.SPACE, widthWeight = 2f),
            ),
        )
        val widths = row.computeKeyWidthsPx(400f)
        assertEquals(100f, widths[0], 0.01f)
        assertEquals(100f, widths[1], 0.01f)
        assertEquals(200f, widths[2], 0.01f)
        assertEquals(400f, widths.sum(), 0.01f)
    }

    @Test
    fun `computeKeyWidthsPx handles zero total weight without dividing by zero`() {
        val row = KeyRow(listOf(KeyDefinition("a", 'a'.code, widthWeight = 0f)))
        val widths = row.computeKeyWidthsPx(400f)
        assertEquals(0f, widths[0], 0.01f)
    }
}
