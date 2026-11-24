package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class KeySpansTest {

    private fun ch(c: String, w: Float = 1f) = KeyDefinition(c, c.first().code, widthWeight = w)
    private fun gap(w: Float) = KeyDefinition("", 0, widthWeight = w, keyType = KeyType.SPACER)

    @Test
    fun `a row with no spacers is one span`() =
        assertEquals(listOf(0f to 1f), KeyRow(listOf(ch("a"), ch("b"))).keySpans())

    @Test
    fun `a split row's gap is left out`() =
        assertEquals(listOf(0f to 0.4f, 0.6f to 1f), KeyRow(listOf(ch("a"), ch("b"), gap(1f), ch("c"), ch("d"))).keySpans())

    @Test
    fun `padding spacers at the edges are left out too`() =
        assertEquals(listOf(0.25f to 1f), KeyRow(listOf(gap(1f), ch("a"), ch("b"), ch("c"))).keySpans())

    @Test
    fun `an all-spacer row has no spans`() = assertEquals(emptyList<Pair<Float, Float>>(), KeyRow(listOf(gap(1f))).keySpans())
}
