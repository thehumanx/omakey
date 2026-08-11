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

    fun sendEditorAction(actionId: Int) {
        ic?.performEditorAction(actionId)
    }

    /** Deletes the whole word immediately before the cursor, including trailing whitespace,
     * via a manual boundary scan + deleteSurroundingText rather than KEYCODE_DEL spam, since
     * "delete word" isn't a single Android editor primitive and this behaves more reliably
     * across third-party InputConnection implementations. */
    fun deleteWordBackward() {
        val connection = ic ?: return
        val textBefore = connection.getTextBeforeCursor(MAX_LOOKBACK_CHARS, 0)?.toString() ?: return
        if (textBefore.isEmpty()) return

        var end = textBefore.length
        var index = end
        // Skip trailing whitespace.
        while (index > 0 && textBefore[index - 1].isWhitespace()) index--
        // Skip the word itself.
        while (index > 0 && !textBefore[index - 1].isWhitespace()) index--

        val deleteCount = end - index
        if (deleteCount <= 0) return

        connection.beginBatchEdit()
        connection.deleteSurroundingText(deleteCount, 0)
        connection.endBatchEdit()
    }

    fun deleteCharacterBackward() {
        ic?.deleteSurroundingText(1, 0)
    }

    fun toggleCaps(currentlyCaps: Boolean): Boolean = !currentlyCaps

    fun sendKeyEvent(keyCode: Int) {
        val connection = ic ?: return
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    companion object {
        private const val MAX_LOOKBACK_CHARS = 128
    }
}
