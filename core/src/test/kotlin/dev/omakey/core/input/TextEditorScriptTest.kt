package dev.omakey.core.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TextEditor]'s notion of "the word at/before the cursor" on non-Latin text. Every one of these
 * used `isLetter()` before AGENTS.md §66 Phase 1, which stops at the first vowel sign or halant:
 * "किताब" read as "ब", and a swipe-left deleted "ाब" and stopped.
 */
class TextEditorScriptTest {

    private fun editorOver(connection: FakeInputConnection) = TextEditor(connectionProvider = { connection })

    @Test
    fun `word at cursor spans vowel signs`() {
        val word = editorOver(FakeInputConnection("मेरो किताब")).wordAtCursor()
        assertEquals("किताब", word?.word)
        assertEquals("किताब".length, word?.charsBeforeCursor)
    }

    @Test
    fun `word at cursor spans a halant conjunct on both sides of the cursor`() {
        // Cursor between म and स्ते.
        val connection = FakeInputConnection("नमस्ते", selectionStart = 2, selectionEnd = 2)
        val word = editorOver(connection).wordAtCursor()
        assertEquals("नमस्ते", word?.word)
        assertEquals(2, word?.charsBeforeCursor)
        assertEquals(4, word?.charsAfterCursor)
    }

    @Test
    fun `word before cursor skips a space or a danda`() {
        assertEquals(TextEditor.WordBeforeCursor("नाम", " "), editorOver(FakeInputConnection("मेरो नाम ")).wordBeforeCursor())
        assertEquals(TextEditor.WordBeforeCursor("नाम", "।"), editorOver(FakeInputConnection("मेरो नाम।")).wordBeforeCursor())
    }

    @Test
    fun `swipe delete removes the whole Devanagari word`() {
        val connection = FakeInputConnection("मेरो किताब")
        editorOver(connection).deleteWordBackward()
        assertEquals("मेरो ", connection.content)
    }

    @Test
    fun `accented Latin is unaffected`() {
        val connection = FakeInputConnection("très café")
        assertEquals("café", editorOver(connection).wordAtCursor()?.word)
        editorOver(connection).deleteWordBackward()
        assertEquals("très ", connection.content)
    }
}
