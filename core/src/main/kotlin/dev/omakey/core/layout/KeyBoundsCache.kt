package dev.omakey.core.layout

/**
 * The key grid's cache of where each key is drawn, which the hit tester searches for the key under
 * a finger. Entries are keyed `row * 1000 + keyIndex`.
 *
 * Each row's measurement replaces that row's entries wholesale rather than merging into them.
 * Merging left ghosts: a row that lost keys (split turned off, so no gap and no repeated G) kept its
 * old entries past the new end, and a row count that shrank (compact) kept whole rows that are no
 * longer drawn. A tap there could then type a key that isn't on screen.
 */
object KeyBoundsCache {

    private const val ROW_STRIDE = 1000

    /**
     * [current] with row [rowIndex] replaced by [measured] (keyIndex → value), and every row at or
     * past [rowCount] dropped. Null when that is exactly [current], so the caller can skip the
     * state write. A floating keyboard re-measures every row on every frame of a drag, and writing
     * anyway recomposed the grid sixty times a second.
     */
    fun <V> merge(current: Map<Int, V>, rowIndex: Int, measured: Map<Int, V>, rowCount: Int): Map<Int, V>? {
        val next = HashMap<Int, V>(current.size + measured.size)
        for ((key, value) in current) {
            val row = key / ROW_STRIDE
            if (row != rowIndex && row < rowCount) next[key] = value
        }
        for ((keyIndex, value) in measured) next[rowIndex * ROW_STRIDE + keyIndex] = value
        return if (next == current) null else next
    }

    /**
     * The entry to show for a key [code]: [preferred] (the copy the finger landed on) when it holds
     * that code, otherwise the first entry that does. A split keyboard repeats its middle keys, so
     * two entries can share a code. Looking up by code alone put the right-hand G's tap preview
     * over the left-hand G, across the gap.
     */
    fun <V> entryFor(current: Map<Int, V>, code: Int, preferred: Int?, codeOf: (V) -> Int): Int? {
        if (preferred != null) current[preferred]?.let { if (codeOf(it) == code) return preferred }
        return current.entries.firstOrNull { codeOf(it.value) == code }?.key
    }
}
