package dev.omakey.core.input

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo

/**
 * A minimal in-memory text field behind the `InputConnection` interface, so [TextEditor] can be
 * exercised in a plain JVM test.
 *
 * `InputConnection` is an interface, not a class, so implementing it here touches no real Android
 * code and needs neither a device nor Robolectric. Only the methods [TextEditor] actually calls do
 * anything; the rest satisfy the interface and are never reached.
 *
 * Text is a single [StringBuilder] with a selection range over it, which is the model
 * `deleteSurroundingText`/`commitText` are specified against. [batchDepth] records
 * begin/endBatchEdit nesting so tests can assert that multi-step edits are actually batched —
 * batching is the difference between the host app seeing one edit and seeing hundreds.
 */
class FakeInputConnection(initialText: String = "", selectionStart: Int = -1, selectionEnd: Int = -1) :
    InputConnection {

    private val text = StringBuilder(initialText)

    /** Cursor position when there is no selection; otherwise the selection's start. */
    private var start = if (selectionStart >= 0) selectionStart else initialText.length
    private var end = if (selectionEnd >= 0) selectionEnd else start

    var batchDepth = 0
        private set

    /** Highest nesting reached, so a test can tell "was batched" from "never batched". */
    var maxBatchDepth = 0
        private set

    /** Number of separate commitText/deleteSurroundingText calls — the round-trip count a real host
     * app would have had to service. */
    var editCallCount = 0
        private set

    val content: String get() = text.toString()
    val cursor: Int get() = start

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
        text.substring(maxOf(0, start - n), start)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
        text.substring(end, minOf(text.length, end + n))

    override fun getSelectedText(flags: Int): CharSequence? =
        if (end > start) text.substring(start, end) else null

    /** The composing region, or -1 when nothing is composing. */
    var composingStart = -1
        private set
    var composingEnd = -1
        private set

    val composing: String? get() = if (composingStart >= 0) text.substring(composingStart, composingEnd) else null

    override fun commitText(newText: CharSequence, newCursorPosition: Int): Boolean {
        editCallCount++
        // Like a real editor: committing replaces the composing region if there is one.
        if (composingStart >= 0) {
            start = composingStart
            end = composingEnd
            composingStart = -1
            composingEnd = -1
        }
        text.replace(start, end, newText.toString())
        start += newText.length
        end = start
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        editCallCount++
        val deleteAfter = minOf(afterLength, text.length - end)
        if (deleteAfter > 0) text.delete(end, end + deleteAfter)
        val deleteBefore = minOf(beforeLength, start)
        if (deleteBefore > 0) {
            text.delete(start - deleteBefore, start)
            start -= deleteBefore
        }
        end = start
        return true
    }

    override fun beginBatchEdit(): Boolean {
        batchDepth++
        maxBatchDepth = maxOf(maxBatchDepth, batchDepth)
        return true
    }

    override fun endBatchEdit(): Boolean {
        batchDepth--
        return true
    }

    override fun setComposingText(composing: CharSequence, newCursorPosition: Int): Boolean {
        editCallCount++
        val from = if (composingStart >= 0) composingStart else start
        val to = if (composingStart >= 0) composingEnd else end
        text.replace(from, to, composing.toString())
        composingStart = from
        composingEnd = from + composing.length
        start = composingEnd
        end = start
        if (composing.isEmpty()) {
            composingStart = -1
            composingEnd = -1
        }
        return true
    }

    override fun finishComposingText(): Boolean {
        composingStart = -1
        composingEnd = -1
        return true
    }

    // --- not used by TextEditor -------------------------------------------------------------------

    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun deleteSurroundingTextInCodePoints(before: Int, after: Int): Boolean = false
    override fun setComposingRegion(start: Int, end: Int): Boolean = false
    override fun commitCompletion(info: CompletionInfo?): Boolean = false
    override fun commitCorrection(info: CorrectionInfo?): Boolean = false
    override fun setSelection(start: Int, end: Int): Boolean = false
    override fun performEditorAction(editorAction: Int): Boolean = false
    override fun performContextMenuAction(id: Int): Boolean = false
    override fun sendKeyEvent(event: KeyEvent?): Boolean = false
    override fun clearMetaKeyStates(states: Int): Boolean = false
    override fun reportFullscreenMode(enabled: Boolean): Boolean = false
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun closeConnection() = Unit
    override fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
    override fun getHandler() = null
}
