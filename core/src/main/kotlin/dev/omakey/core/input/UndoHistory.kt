package dev.omakey.core.input

/**
 * One reversible text mutation: [inserted] currently sits where [removed] used to be.
 *
 * Either side may be empty — a plain insertion has no [removed], a plain deletion has no
 * [inserted], and replacing a selection (a paste over selected text, a cut) has both. That last
 * case is why this is one shape rather than a sealed `Inserted | Deleted` pair, which could not
 * express it: undoing a paste-over-selection has to put back the text the paste displaced, and a
 * model that only records one side of the edit has already thrown that away.
 */
data class TextEdit(val removed: String = "", val inserted: String = "")

/**
 * Undo/redo history for raw text mutation, as a plain stack pair with no Android dependency.
 *
 * Deliberately does **not** apply anything. [undo] and [redo] return the [TextEdit] to reverse or
 * replay and leave the caller to perform it (via [TextEditor.replaceBackward]), which is what keeps
 * this testable without an `InputConnection` — the sequencing rules below are the part that is easy
 * to get wrong, and they are all here.
 *
 * ## Granularity
 *
 * One user gesture is one step, however much text it moved. That is the rule Windows rich edit
 * controls and AppKit both encode: Windows names its units (`UID_TYPING`, `UID_PASTE`, `UID_CUT`)
 * and breaks the typing group on caret movement or any non-typing operation, and `NSTextView`
 * coalesces typing into one undo group with `breakUndoCoalescing()` ending it. A paste is one step
 * whether it is one word or a paragraph.
 *
 * The exception, in both of them and here, is a **run of backspaces** — [recordBackspace]
 * accumulates consecutive deletions into a single step, because a backspace run reads as one edit.
 * Any other recorded edit, or an explicit [breakCoalescing], ends the run.
 *
 * omakey is narrower than Windows in one respect: ordinary typing is recorded per word, not per
 * run. A phone has no Ctrl+Z muscle memory to preserve, undo is a button the user aims at, and one
 * tap discarding a whole paragraph is a worse surprise than one tap discarding a word.
 */
class UndoHistory(private val limit: Int = DEFAULT_LIMIT) {

    private val undoStack = ArrayDeque<TextEdit>()
    private val redoStack = ArrayDeque<TextEdit>()

    /** True while the most recent record was a backspace, so the next one merges into it. */
    private var coalescingBackspaces = false

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Records one completed edit as its own step. Ends any backspace run in progress, and drops
     * the redo stack — the timeline has branched, and the old redo entries describe text that no
     * longer exists. */
    fun record(edit: TextEdit) {
        if (edit.removed.isEmpty() && edit.inserted.isEmpty()) return
        coalescingBackspaces = false
        push(edit)
    }

    /**
     * Records a single backspaced character, merging it into the immediately preceding run of
     * backspaces rather than pushing a step per keystroke.
     *
     * [character] is prepended, not appended: backspaces consume text right-to-left, so the
     * earliest-deleted character is the rightmost one in the restored string.
     */
    fun recordBackspace(character: Char) {
        val top = undoStack.lastOrNull()
        // Merges only into another pure deletion. A step with an `inserted` side is some other kind
        // of edit that happens to sit on top, and folding a backspace into it would corrupt both.
        if (coalescingBackspaces && top != null && top.inserted.isEmpty()) {
            undoStack[undoStack.lastIndex] = TextEdit(removed = character + top.removed)
            redoStack.clear()
        } else {
            coalescingBackspaces = false
            push(TextEdit(removed = character.toString()))
        }
        coalescingBackspaces = true
    }

    /** Ends any backspace run, so the next [recordBackspace] starts a fresh step. Called for
     * anything that makes the run no longer contiguous — cursor movement, a layout change, typing
     * a character. */
    fun breakCoalescing() {
        coalescingBackspaces = false
    }

    /** The edit to reverse (apply [TextEdit.removed] in place of [TextEdit.inserted]), or null if
     * there is nothing to undo. */
    fun undo(): TextEdit? {
        val edit = undoStack.removeLastOrNull() ?: return null
        coalescingBackspaces = false
        redoStack.addLast(edit)
        if (redoStack.size > limit) redoStack.removeFirst()
        return edit
    }

    /** The edit to replay (apply [TextEdit.inserted] in place of [TextEdit.removed]), or null if
     * there is nothing to redo. */
    fun redo(): TextEdit? {
        val edit = redoStack.removeLastOrNull() ?: return null
        coalescingBackspaces = false
        undoStack.addLast(edit)
        // Capped on this side too. Without it, a long undo/redo/undo/redo run grows `undoStack`
        // past the limit, since `undo` trims only `redoStack`.
        if (undoStack.size > limit) undoStack.removeFirst()
        return edit
    }

    /** Drops both histories — a new input field is a new editing context, and edits made in the
     * previous one describe text this one never had. */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        coalescingBackspaces = false
    }

    private fun push(edit: TextEdit) {
        undoStack.addLast(edit)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    companion object {
        /** Steps kept per field. Bounded because this is held for the lifetime of an
         * `InputMethodService`, which is not allowed to accumulate memory indefinitely. */
        const val DEFAULT_LIMIT = 50
    }
}
