package dev.omakey.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sequencing rules for undo/redo, tested where they live rather than through
 * `KeyboardViewModel` — which needs a `Context` for every one of its preference dependencies and so
 * cannot be constructed in a plain JVM test. Extracting [UndoHistory] into `core` is what made
 * these assertions possible at all.
 *
 * The cases below are the ones the previous implementation got wrong, plus the invariants that kept
 * it honest.
 */
class UndoHistoryTest {

    private val history = UndoHistory()

    // --- one gesture, one step ------------------------------------------------------------------

    @Test
    fun `a paste is one step regardless of length`() {
        // The reported bug: pasting a four-word paragraph and undoing removed it a word at a time.
        // Nothing here should depend on how much text the edit moved.
        val pasted = "the quick brown fox"
        history.record(TextEdit(inserted = pasted))

        assertEquals(TextEdit(inserted = pasted), history.undo())
        assertFalse("one paste is one step", history.canUndo)
    }

    @Test
    fun `an edit that replaces a selection round-trips both sides`() {
        // A paste over selected text. The old sealed Inserted/Deleted shape could not represent
        // this, so undo restored the clip's removal without restoring what it had displaced.
        val edit = TextEdit(removed = "old text", inserted = "new")
        history.record(edit)

        val undone = history.undo()
        assertEquals("old text", undone?.removed)
        assertEquals("new", undone?.inserted)

        assertEquals(edit, history.redo())
    }

    @Test
    fun `an empty edit is not recorded`() {
        history.record(TextEdit())
        assertFalse(history.canUndo)
    }

    // --- backspace coalescing -------------------------------------------------------------------

    @Test
    fun `consecutive backspaces collapse into one step, in the right order`() {
        // Deleting "abc" right-to-left: 'c' goes first, so the restored string must be "abc" and
        // not "cba".
        history.recordBackspace('c')
        history.recordBackspace('b')
        history.recordBackspace('a')

        assertEquals(TextEdit(removed = "abc"), history.undo())
        assertFalse("the run is a single step", history.canUndo)
    }

    @Test
    fun `an intervening edit breaks the backspace run`() {
        history.recordBackspace('b')
        history.record(TextEdit(inserted = "word "))
        history.recordBackspace('a')

        assertEquals(TextEdit(removed = "a"), history.undo())
        assertEquals(TextEdit(inserted = "word "), history.undo())
        assertEquals(TextEdit(removed = "b"), history.undo())
    }

    @Test
    fun `breakCoalescing starts a fresh step`() {
        history.recordBackspace('b')
        history.breakCoalescing()
        history.recordBackspace('a')

        assertEquals(TextEdit(removed = "a"), history.undo())
        assertEquals(TextEdit(removed = "b"), history.undo())
    }

    @Test
    fun `a backspace never merges into an insertion sitting on top`() {
        // Guards the merge condition itself: folding a deletion into a step that also has an
        // `inserted` side would corrupt both halves of that step.
        history.record(TextEdit(removed = "x", inserted = "y"))
        history.recordBackspace('a')

        assertEquals(TextEdit(removed = "a"), history.undo())
        assertEquals(TextEdit(removed = "x", inserted = "y"), history.undo())
    }

    // --- redo ------------------------------------------------------------------------------------

    @Test
    fun `redo replays in order and undo unwinds it again`() {
        history.record(TextEdit(inserted = "one "))
        history.record(TextEdit(inserted = "two "))

        assertEquals(TextEdit(inserted = "two "), history.undo())
        assertEquals(TextEdit(inserted = "one "), history.undo())
        assertFalse(history.canUndo)

        assertEquals(TextEdit(inserted = "one "), history.redo())
        assertEquals(TextEdit(inserted = "two "), history.redo())
        assertFalse(history.canRedo)
    }

    @Test
    fun `a new edit after undoing invalidates redo`() {
        history.record(TextEdit(inserted = "one "))
        history.undo()
        assertTrue(history.canRedo)

        history.record(TextEdit(inserted = "different "))

        assertFalse("the timeline branched; the old redo describes text that no longer exists", history.canRedo)
    }

    @Test
    fun `a backspace after undoing also invalidates redo`() {
        history.record(TextEdit(inserted = "one "))
        history.undo()
        history.recordBackspace('x')

        assertFalse(history.canRedo)
    }

    @Test
    fun `undo and redo report null when empty rather than throwing`() {
        assertNull(history.undo())
        assertNull(history.redo())
    }

    // --- bounds ------------------------------------------------------------------------------------

    @Test
    fun `the undo stack is capped, dropping the oldest`() {
        val history = UndoHistory(limit = 3)
        listOf("a", "b", "c", "d").forEach { history.record(TextEdit(inserted = it)) }

        assertEquals(TextEdit(inserted = "d"), history.undo())
        assertEquals(TextEdit(inserted = "c"), history.undo())
        assertEquals(TextEdit(inserted = "b"), history.undo())
        assertFalse("\"a\" should have been dropped", history.canUndo)
    }

    @Test
    fun `redoing does not grow the undo stack past the limit`() {
        // undo() trimmed redoStack but redo() used to not trim undoStack, so a long alternating
        // run could push it past the cap.
        val history = UndoHistory(limit = 2)
        history.record(TextEdit(inserted = "a"))
        history.record(TextEdit(inserted = "b"))
        repeat(20) {
            history.undo()
            history.redo()
        }

        var depth = 0
        while (history.undo() != null) depth++
        assertEquals(2, depth)
    }

    @Test
    fun `clear drops both histories`() {
        history.record(TextEdit(inserted = "one "))
        history.undo()
        history.clear()

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }
}
