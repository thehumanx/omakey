package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyInsetsTest {

    @Test
    fun `a short row keeps the old thin gap`() = assertEquals(1.5f, KeyInsets.verticalDp(30), 0.01f)

    @Test
    fun `a taller row gets a wider gap, so keys don't turn into tall bars`() {
        assertEquals(5.5f, KeyInsets.verticalDp(55), 0.01f)
        assertTrue(KeyInsets.verticalDp(75) > KeyInsets.verticalDp(55))
    }

    @Test
    fun `the gap stops growing`() = assertEquals(KeyInsets.MAX_VERTICAL_DP, KeyInsets.verticalDp(200), 0.01f)

    @Test
    fun `a key always keeps most of its row`() = listOf(30, 45, 55, 65, 75, 90, 120).forEach { row ->
        assertTrue("row $row", row - 2 * KeyInsets.verticalDp(row) >= row * 0.7f)
    }
}
