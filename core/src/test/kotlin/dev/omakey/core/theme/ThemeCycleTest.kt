package dev.omakey.core.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeCycleTest {

    private val presets = Presets.all
    private val mine = listOf(
        Presets.all.first().copy(id = "custom_a", name = "Mine A"),
        Presets.all.first().copy(id = "custom_b", name = "Mine B"),
    )

    @Test
    fun `presets come first, in order`() {
        assertEquals(presets[1].id, nextTheme(presets[0].id, presets, mine).id)
    }

    @Test
    fun `custom themes follow the last preset`() {
        assertEquals("custom_a", nextTheme(presets.last().id, presets, mine).id)
        assertEquals("custom_b", nextTheme("custom_a", presets, mine).id)
    }

    @Test
    fun `the last custom theme wraps to the first preset`() {
        assertEquals(presets.first().id, nextTheme("custom_b", presets, mine).id)
    }

    @Test
    fun `with no custom themes it is the presets alone`() {
        assertEquals(presets.first().id, nextTheme(presets.last().id, presets, emptyList()).id)
    }

    @Test
    fun `a deleted current theme starts again at the first preset`() {
        assertEquals(presets.first().id, nextTheme("gone", presets, mine).id)
    }
}
