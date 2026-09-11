package dev.omakey.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextEditor]'s edit primitives against an in-memory field ([FakeInputConnection]).
 *
 * Focused on [TextEditor.replaceBackward], which undo/redo is now built on, and on the selection
 * semantics paste depends on — the two places where getting the model wrong silently corrupts the
 * user's text rather than failing loudly.
 */
class TextEditorTest {

    private fun editorOver(connection: FakeInputConnection) = TextEditor { connection }

    @Test
    fun `replaceBackward swaps the characters before the cursor`() {
        val connection = FakeInputConnection("hello world")
        editorOver(connection).replaceBackward(5, "there")

        assertEquals("hello there", connection.content)
        assertEquals("hello there".length, connection.cursor)
    }

    @Test
    fun `replaceBackward with no replacement is a plain deletion`() {
        val connection = FakeInputConnection("hello world")
        editorOver(connection).replaceBackward(6, "")

        assertEquals("hello", connection.content)
    }

    @Test
    fun `replaceBackward with nothing to delete is a plain insertion`() {
        val connection = FakeInputConnection("hello")
        editorOver(connection).replaceBackward(0, " world")

        assertEquals("hello world", connection.content)
    }

    @Test
    fun `replaceBackward is a single batched edit`() {
        // The point of the primitive. Undo used to loop deleteCharacterBackward() once per
        // character, so undoing a pasted paragraph was hundreds of InputConnection round-trips and
        // visibly unwound character by character in some host apps.
        val connection = FakeInputConnection("a pasted paragraph of some length")
        editorOver(connection).replaceBackward(connection.content.length, "")

        assertTrue("should be wrapped in a batch", connection.maxBatchDepth >= 1)
        assertEquals("should be one delete call, not one per character", 1, connection.editCallCount)
        assertEquals(0, connection.batchDepth)
    }

    @Test
    fun `replaceBackward does nothing when there is nothing to do`() {
        val connection = FakeInputConnection("hello")
        editorOver(connection).replaceBackward(0, "")

        assertEquals("hello", connection.content)
        assertEquals(0, connection.editCallCount)
    }

    // --- the undo/redo round trip ----------------------------------------------------------------

    @Test
    fun `an undo and redo of a multi-word paste restores the text exactly`() {
        // End-to-end over the two pieces together: UndoHistory decides what to reverse,
        // replaceBackward performs it. This is the reported bug's exact scenario.
        val connection = FakeInputConnection("note: ")
        val editor = editorOver(connection)
        val history = UndoHistory()

        val pasted = "the quick brown fox"
        editor.insertText(pasted)
        history.record(TextEdit(inserted = pasted))
        assertEquals("note: the quick brown fox", connection.content)

        val undone = history.undo()!!
        editor.replaceBackward(undone.inserted.length, undone.removed)
        assertEquals("one tap removes the whole paste", "note: ", connection.content)

        val redone = history.redo()!!
        editor.replaceBackward(redone.removed.length, redone.inserted)
        assertEquals("note: the quick brown fox", connection.content)
    }

    @Test
    fun `an undo of a paste over a selection restores what it displaced`() {
        val connection = FakeInputConnection("keep REPLACED tail", selectionStart = 5, selectionEnd = 13)
        val editor = editorOver(connection)
        val history = UndoHistory()

        val replaced = editor.selectedText().orEmpty()
        assertEquals("REPLACED", replaced)

        editor.insertText("new")
        history.record(TextEdit(removed = replaced, inserted = "new"))
        assertEquals("keep new tail", connection.content)

        val undone = history.undo()!!
        editor.replaceBackward(undone.inserted.length, undone.removed)
        assertEquals("keep REPLACED tail", connection.content)
    }

    @Test
    fun `a backspace run undoes in one step with the characters in the right order`() {
        val connection = FakeInputConnection("hello world")
        val editor = editorOver(connection)
        val history = UndoHistory()

        repeat(5) {
            val deleted = editor.textBeforeCursor(1).last()
            editor.deleteCharacterBackward()
            history.recordBackspace(deleted)
        }
        assertEquals("hello ", connection.content)

        val undone = history.undo()!!
        editor.replaceBackward(undone.inserted.length, undone.removed)
        assertEquals("hello world", connection.content)
    }

    // --- selection -------------------------------------------------------------------------------

    @Test
    fun `insertText replaces an active selection`() {
        // What makes a paste over selected text one replacement rather than an insertion.
        val connection = FakeInputConnection("keep THIS tail", selectionStart = 5, selectionEnd = 9)
        editorOver(connection).insertText("that")

        assertEquals("keep that tail", connection.content)
    }

    @Test
    fun `hasSelection and selectedText agree with the field`() {
        val selected = FakeInputConnection("abc def", selectionStart = 0, selectionEnd = 3)
        assertTrue(editorOver(selected).hasSelection())
        assertEquals("abc", editorOver(selected).selectedText())

        val plain = FakeInputConnection("abc def")
        assertEquals(false, editorOver(plain).hasSelection())
    }
}
