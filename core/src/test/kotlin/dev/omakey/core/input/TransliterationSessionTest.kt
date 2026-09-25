package dev.omakey.core.input

import dev.omakey.core.translit.LiteralTransliterator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransliterationSessionTest {

    private val connection = FakeInputConnection("म ")
    private val session = TransliterationSession(TextEditor(connectionProvider = { connection }))

    /** Stand-in for the model: the literal transliteration, plus a fixed alternative for "timi". */
    private val candidates: (String) -> List<String> = { latin ->
        if (latin == "timi") listOf("तिमी", "तिमि") else listOf(LiteralTransliterator.transliterate(latin))
    }

    private fun type(word: String) = word.forEach { session.type(it.toString(), candidates) }

    @Test
    fun `the field shows the script while Latin is typed, and nothing is committed yet`() {
        type("namaste")
        assertEquals("म नमस्ते", connection.content)
        assertEquals("नमस्ते", connection.composing)
        assertEquals("namaste", session.typed)
        assertEquals(listOf("नमस्ते", "namaste"), session.strip)
    }

    @Test
    fun `commit settles the word with its separator`() {
        type("timi")
        assertEquals("तिमी", session.commit(" "))
        assertEquals("म तिमी ", connection.content)
        assertNull(connection.composing)
        assertFalse(session.isComposing)
    }

    @Test
    fun `swiping selects another candidate, and the Latin itself is offered`() {
        type("timi")
        session.select(1)
        assertEquals("तिमि", connection.composing)
        session.select(2)
        assertEquals("timi", connection.composing) // mixed English: keep the Latin
        session.select(3) // wraps
        assertEquals("तिमी", connection.composing)
    }

    @Test
    fun `backspace edits the Latin, and on an empty word falls through`() {
        type("mero")
        assertTrue(session.backspace(candidates))
        assertEquals("मेर", connection.composing)
        repeat(3) { session.backspace(candidates) }
        assertEquals("म ", connection.content)
        assertNull(connection.composing)
        assertFalse("nothing composing: an ordinary backspace", session.backspace(candidates))
    }

    @Test
    fun `moving the cursor away keeps what is shown`() {
        type("mero")
        session.abandon()
        assertEquals("म मेरो", connection.content)
        assertNull(connection.composing)
        assertFalse(session.isComposing)
    }

    @Test
    fun `a tapped entry is what gets committed`() {
        type("timi")
        session.commit("timi", " ")
        assertEquals("म timi ", connection.content)
    }
}
