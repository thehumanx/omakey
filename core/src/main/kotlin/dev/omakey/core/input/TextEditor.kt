package dev.omakey.core.input

import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/**
 * The sole InputConnection access point. Both gesture actions and key taps must route through
 * this, never touch InputConnection directly, so the gesture engine stays framework-agnostic
 * and testable, and multi-step edits are consistently batched.
 */
class TextEditor(private val connectionProvider: () -> InputConnection?) {

    private val ic: InputConnection? get() = connectionProvider()

    fun commitCharacter(char: Char) {
        ic?.commitText(char.toString(), 1)
    }

    fun commitComposing(text: String) {
        ic?.setComposingText(text, 1)
    }

    fun finishComposing() {
        ic?.finishComposingText()
    }

    fun insertSpace() {
        ic?.commitText(" ", 1)
    }

    fun insertNewline() {
        ic?.commitText("\n", 1)
    }

    /** Inserts [text] as a single batch, e.g. for undo/redo retyping a multi-character run —
     * one `commitText` call rather than looping [commitCharacter], so the host app sees one
     * edit instead of N. */
    fun insertText(text: String) {
        if (text.isEmpty()) return
        ic?.commitText(text, 1)
    }

    fun sendEditorAction(actionId: Int) {
        ic?.performEditorAction(actionId)
    }

    /** Exactly what [deleteWordBackward] would remove — the trailing-whitespace run plus the
     * word before it — without actually deleting it. Exposed so callers that need to record the
     * deletion (undo/redo) capture the literal text, not just the letters-only word from
     * [wordAtCursor], which would silently drop whatever whitespace came with it. */
    fun wordBackwardDeletionPreview(): String? {
        val textBefore = ic?.getTextBeforeCursor(MAX_CURSOR_CONTEXT_CHARS, 0)?.toString() ?: return null
        if (textBefore.isEmpty()) return null

        var end = textBefore.length
        var index = end
        // Skip trailing whitespace.
        while (index > 0 && textBefore[index - 1].isWhitespace()) index--
        // Skip the word itself.
        while (index > 0 && !textBefore[index - 1].isWhitespace()) index--

        return textBefore.substring(index, end).takeIf { it.isNotEmpty() }
    }

    /** Deletes the whole word immediately before the cursor, including trailing whitespace,
     * via a manual boundary scan + deleteSurroundingText rather than KEYCODE_DEL spam, since
     * "delete word" isn't a single Android editor primitive and this behaves more reliably
     * across third-party InputConnection implementations. Shares its boundary scan with
     * [wordBackwardDeletionPreview] so callers recording the deletion for undo see exactly what
     * this removes. */
    fun deleteWordBackward() {
        val connection = ic ?: return
        val deleteCount = wordBackwardDeletionPreview()?.length ?: return

        connection.beginBatchEdit()
        connection.deleteSurroundingText(deleteCount, 0)
        connection.endBatchEdit()
    }

    /** Real bug, fixed: plain `deleteSurroundingText(1, 0)` deletes exactly one UTF-16 code unit,
     * but most emoji (anything outside the Basic Multilingual Plane — the overwhelming majority
     * of the emoji panel's own catalog) are encoded as a *surrogate pair*, two code units for one
     * visible character. Deleting just one left the other half of the pair behind as a lone
     * unpaired surrogate, which every renderer shows as the "?" tofu box (confirmed: type an
     * emoji, hit backspace once, the emoji visibly turns into "?" instead of disappearing).
     * Doesn't attempt full grapheme-cluster awareness (ZWJ sequences, skin-tone modifiers, flags —
     * each themselves multiple codepoints) — deliberately scoped to the specific, common case this
     * bug report was about; those compound emoji still delete one codepoint at a time same as
     * before, just without ever leaving a *dangling* surrogate. */
    fun deleteCharacterBackward() {
        val connection = ic ?: return
        val before = connection.getTextBeforeCursor(2, 0)
        val deleteCount = if (before != null && before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
        connection.deleteSurroundingText(deleteCount, 0)
    }

    /** Plain text immediately before the cursor, up to [maxChars] — the same
     * `getTextBeforeCursor` [wordAtCursor]/[deleteWordBackward] already use internally, exposed
     * publicly for callers (e.g. the inline calculator) that need to inspect a run of characters
     * `wordAtCursor`'s letters-only definition doesn't cover, like digits and operators. */
    fun textBeforeCursor(maxChars: Int): String = ic?.getTextBeforeCursor(maxChars, 0)?.toString() ?: ""

    /** True if there's currently a non-empty text selection (e.g. after "Select all", or a
     * host-app text-selection drag) — [getSelectedText] is a standard `InputConnection` method,
     * not a heuristic. Used to make delete-word/delete-character remove the *whole* selection
     * instead of just nibbling at whatever sits immediately before the cursor, which is what
     * `deleteSurroundingText`-based deletion does by default (it's relative to the cursor, not
     * selection-aware). */
    fun hasSelection(): Boolean = !ic?.getSelectedText(0).isNullOrEmpty()

    /** The currently selected text, if any — read before triggering a copy/cut so the caller
     * already knows what's about to land on the clipboard, without needing to read it back from
     * [android.content.ClipboardManager] afterwards (which is exactly the kind of clipboard read
     * that triggers Android 12+'s privacy toast). */
    fun selectedText(): String? = ic?.getSelectedText(0)?.toString()

    /** Replaces the active selection with nothing, i.e. deletes it — a plain empty `commitText`
     * replaces whatever's currently selected, same as typing over a selection replaces it. */
    fun deleteSelection() {
        ic?.commitText("", 1)
    }

    /** The contiguous run of letters touching the cursor on both sides, e.g. the cursor sitting
     * anywhere inside/at the edges of "thys" — whether it landed there by typing, a tap, or arrow-
     * key navigation — resolves to the whole word "thys", not just whichever half happens to be
     * adjacent. Null if the cursor isn't touching a word at all (sitting in whitespace, at the
     * very start/end of the field, etc). */
    fun wordAtCursor(): WordAtCursor? {
        val connection = ic ?: return null
        val before = connection.getTextBeforeCursor(MAX_CURSOR_CONTEXT_CHARS, 0)?.toString() ?: ""
        val after = connection.getTextAfterCursor(MAX_CURSOR_CONTEXT_CHARS, 0)?.toString() ?: ""
        val beforePart = before.takeLastWhile { it.isLetter() }
        val afterPart = after.takeWhile { it.isLetter() }
        if (beforePart.isEmpty() && afterPart.isEmpty()) return null
        return WordAtCursor(word = beforePart + afterPart, charsBeforeCursor = beforePart.length, charsAfterCursor = afterPart.length)
    }

    /** Replaces [word] (as previously returned by [wordAtCursor]) with [replacement], regardless
     * of where within the word the cursor was sitting — deletes both the before- and after-cursor
     * portions in one batched edit and leaves the cursor right after the replacement, matching how
     * tapping a correction for a word you've navigated into behaves on mainstream keyboards. */
    fun replaceWordAtCursor(word: WordAtCursor, replacement: String) {
        val connection = ic ?: return
        connection.beginBatchEdit()
        connection.deleteSurroundingText(word.charsBeforeCursor, word.charsAfterCursor)
        connection.commitText(replacement, 1)
        connection.endBatchEdit()
    }

    data class WordAtCursor(val word: String, val charsBeforeCursor: Int, val charsAfterCursor: Int)

    /** The nearest run of letters before the cursor, plus whatever non-letter separator (if any)
     * sits between it and the cursor — e.g. cursor at the end of "wont " (a trailing space with
     * nothing typed after it) resolves to word="wont", separator=" ". Unlike [wordAtCursor], this
     * doesn't require the word to be touching the cursor, which is exactly the case right after a
     * word/character delete leaves the cursor sitting just past a separator instead of a letter.
     * Null if there's no letter run within [MAX_CURSOR_CONTEXT_CHARS] before the cursor. */
    fun wordBeforeCursor(): WordBeforeCursor? {
        val connection = ic ?: return null
        val textBefore = connection.getTextBeforeCursor(MAX_CURSOR_CONTEXT_CHARS, 0)?.toString() ?: return null
        if (textBefore.isEmpty()) return null
        var end = textBefore.length
        var index = end
        while (index > 0 && !textBefore[index - 1].isLetter()) index--
        val separator = textBefore.substring(index, end)
        end = index
        while (index > 0 && textBefore[index - 1].isLetter()) index--
        val word = textBefore.substring(index, end)
        if (word.isEmpty()) return null
        return WordBeforeCursor(word, separator)
    }

    data class WordBeforeCursor(val word: String, val separator: String)

    fun toggleCaps(currentlyCaps: Boolean): Boolean = !currentlyCaps

    fun sendKeyEvent(keyCode: Int) {
        val connection = ic ?: return
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // Routed through the same performContextMenuAction ids the system text-selection toolbar
    // uses, rather than hand-rolling selection/clipboard logic — every InputConnection
    // implementation already has to support these for the OS long-press menu to work.
    fun selectAll() {
        ic?.performContextMenuAction(android.R.id.selectAll)
    }

    fun copySelection() {
        ic?.performContextMenuAction(android.R.id.copy)
    }

    fun cutSelection() {
        ic?.performContextMenuAction(android.R.id.cut)
    }

    fun pasteFromClipboard() {
        ic?.performContextMenuAction(android.R.id.paste)
    }

    companion object {
        private const val MAX_CURSOR_CONTEXT_CHARS = 128
    }
}
