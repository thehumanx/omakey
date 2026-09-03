package dev.omakey.core.predict.spatial

/**
 * Where the finger actually landed for each character of the word currently being typed, in the
 * same normalised key-grid units [KeyboardGeometry] uses.
 *
 * Without this the correction engine only ever learns *which key* a tap resolved to, and has to
 * treat every tap as though it landed dead centre. That throws away the most useful signal there
 * is: a tap 40% of the way toward `l` is much weaker evidence for `k` than one squarely on `k`,
 * and the difference is exactly what separates "helko → hello" from "helko → help".
 *
 * A fixed-size ring buffer rather than a growing list — it is written on every keystroke, on the
 * main thread, and a word has a bounded length worth remembering. [MAX_TAPS] is generous next to
 * real word lengths; anything longer simply loses its oldest taps, which is harmless because
 * correction only ever looks at words shorter than that anyway.
 *
 * Not thread-safe by construction: writes happen on the main thread from key handling, and the
 * read side ([snapshot]) copies out before handing anything to a background coroutine.
 */
class TouchTrace {

    private val xs = FloatArray(MAX_TAPS)
    private val ys = FloatArray(MAX_TAPS)
    private var count = 0

    /** Records a tap at normalised grid position ([x], [y]). Coordinates outside the letter grid
     * are fine — the model measures distance, and a tap near the edge is meaningful evidence. */
    fun record(x: Float, y: Float) {
        if (count < MAX_TAPS) {
            xs[count] = x
            ys[count] = y
        }
        count++
    }

    /** Drops the most recent tap, keeping the trace aligned with the word buffer after a
     * backspace. Once [count] has run past [MAX_TAPS] the coordinates are gone, so the trace
     * degrades to "no data" rather than silently misaligning. */
    fun removeLast() {
        if (count > 0) count--
    }

    fun clear() {
        count = 0
    }

    val size: Int get() = count

    /**
     * An immutable copy for scoring, or null when there is nothing usable.
     *
     * Returns null unless the trace length matches [expectedLength] exactly. That check is the
     * whole safety story: a trace that has drifted out of sync with the typed text — because the
     * user pasted, moved the cursor, picked an accent from a long-press popup, or typed on a
     * hardware keyboard — would otherwise score character *i* against a tap belonging to some
     * other character, which is worse than having no spatial data at all. Falling back to key
     * centres is a graceful degradation; misaligned coordinates are silent corruption.
     */
    fun snapshot(expectedLength: Int): Taps? {
        if (count != expectedLength || count == 0 || count > MAX_TAPS) return null
        return Taps(xs.copyOf(count), ys.copyOf(count))
    }

    /** Tap positions for one word, aligned index-for-index with its characters. */
    class Taps(private val xs: FloatArray, private val ys: FloatArray) {
        val size: Int get() = xs.size
        fun x(index: Int): Float = xs[index]
        fun y(index: Int): Float = ys[index]
    }

    private companion object {
        /** Longer than any word correction will consider (`AutocorrectIndex.MAX_LENGTH` is 24). */
        const val MAX_TAPS = 32
    }
}
