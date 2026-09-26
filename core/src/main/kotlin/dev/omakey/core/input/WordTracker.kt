package dev.omakey.core.input

/**
 * Typing-order bookkeeping: the word currently being typed, and the two words finished before it.
 *
 * Extracted from `KeyboardViewModel` for one specific reason. Three of these fields have to move
 * together on every word boundary — `lastCommitted` lowercased, `lastCommittedCased` as typed, and
 * `previousToLastCommitted` taking the old value of the first — and that three-line dance was
 * written out by hand at eight separate call sites. `lastCommittedCased`'s own doc comment used to
 * end with "Mirrored at every assignment site", which is a defect being documented rather than
 * designed: the field exists *because* one of those sites had already got it wrong, passing an
 * already-lowercased word where the original casing was needed, so case restoration had nothing to
 * restore from. Eight hand-maintained copies of a three-field invariant produce that bug again
 * eventually. Here the invariant can only be changed through [commitWord], which cannot forget
 * part of itself.
 *
 * Pure Kotlin with no Android dependency, so it is directly unit-testable — the same reasoning that
 * moved [UndoHistory] here, and for the same reason: `KeyboardViewModel` needs a `Context` for
 * every one of its preference dependencies and cannot be constructed in a plain JVM test, so
 * anything that stays inside it is effectively untested.
 *
 * Not thread-safe, and doesn't need to be: every caller is on the IME's main thread.
 */
class WordTracker {

    private val buffer = StringBuilder()

    /**
     * The word most recently finished, **lowercased**. Lowercase because this is what bigram
     * lookups key on; use [lastCommittedCased] anywhere the user's own capitalisation matters.
     */
    var lastCommitted: String? = null
        private set

    /**
     * [lastCommitted] exactly as the user typed it, mixed case preserved.
     *
     * Both exist because the two consumers genuinely want different things: prediction wants a
     * normalised key, while offering "Hello" as the fix for "Hwllo" needs to know the original was
     * capitalised. Neither can be derived from the other.
     */
    var lastCommittedCased: String? = null
        private set

    /**
     * Whatever [lastCommitted] was immediately before it — the left-hand bigram context for the
     * word just finished, used to rank alternatives by what fits the surrounding sentence.
     */
    var previousToLastCommitted: String? = null
        private set

    /**
     * The literal text that ended [lastCommitted]: a space, a newline, or a punctuation character.
     *
     * Retroactive corrections need to know exactly how many characters sit between the target word
     * and the cursor, and what to put back after it. Assuming a space here silently corrupts
     * "word.<fix>" into "word.<fix> " and "word\n<fix>" into "word\n<fix> ".
     */
    var boundarySeparator: String = " "

    // --- the word in progress --------------------------------------------------------------

    val bufferedWord: String get() = buffer.toString()
    val bufferLength: Int get() = buffer.length
    val isBufferEmpty: Boolean get() = buffer.isEmpty()
    val isBufferNotEmpty: Boolean get() = buffer.isNotEmpty()

    fun appendToBuffer(char: Char) {
        buffer.append(char)
    }

    /** For keys that type more than one character, e.g. a Devanagari conjunct. */
    fun appendToBuffer(text: CharSequence) {
        buffer.append(text)
    }

    fun clearBuffer() {
        buffer.setLength(0)
    }

    /** Clear and refill in one step — the shape every correction path wants, since replacing the
     * buffered word with a corrected one is a single logical edit. */
    fun replaceBuffer(text: String) {
        buffer.setLength(0)
        buffer.append(text)
    }

    /** Drops the last character of the word in progress. No-op on an empty buffer, so callers
     * don't each have to guard against the `StringBuilder` index exception. */
    fun deleteLastBufferedChar() {
        if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
    }

    // --- word boundaries -------------------------------------------------------------------

    /**
     * Records [word] as finished, shifting the previous one back into [previousToLastCommitted].
     *
     * Returns the word that was in [lastCommitted] beforehand. That return value is not a
     * convenience: deferred learning needs the left context of the word being staged, read *before*
     * the shift, and having it come back from this call is what stops a caller from reading the
     * field in the wrong order.
     */
    fun commitWord(word: String): String? {
        val previous = lastCommitted
        previousToLastCommitted = previous
        lastCommitted = word.lowercase()
        lastCommittedCased = word
        return previous
    }

    /**
     * Commits whatever is in the buffer and clears it, or does nothing if the buffer is empty.
     *
     * Returns the committed word, or null if there was nothing to commit — so callers can branch on
     * "was there a word here" without separately testing the buffer and risking the two checks
     * drifting apart.
     */
    fun commitBufferedWord(): CommittedWord? {
        if (buffer.isEmpty()) return null
        val word = buffer.toString()
        val previous = commitWord(word)
        clearBuffer()
        return CommittedWord(word, previous)
    }

    /**
     * Replaces the identity of the word already recorded as last-committed, leaving
     * [previousToLastCommitted] alone.
     *
     * Distinct from [commitWord] and not interchangeable with it. This is for a correction
     * rewriting a word that was *already* finished, and for re-anchoring onto a word the cursor
     * happens to be sitting after: no new word boundary was crossed, so shifting history would
     * push a real previous word out of view and leave bigram ranking with the wrong context.
     */
    fun retargetLastCommitted(word: String) {
        lastCommitted = word.lowercase()
        lastCommittedCased = word
    }

    /** Forgets everything, for a new field or any edit that lands the cursor somewhere the
     * typing-order bookkeeping can no longer describe. [boundarySeparator] returns to a space
     * rather than persisting, since there is no longer a boundary it refers to. */
    fun clear() {
        lastCommitted = null
        lastCommittedCased = null
        previousToLastCommitted = null
        boundarySeparator = " "
        clearBuffer()
    }

    /** @property previousWord what preceded [word], captured before the shift. */
    data class CommittedWord(val word: String, val previousWord: String?)
}
