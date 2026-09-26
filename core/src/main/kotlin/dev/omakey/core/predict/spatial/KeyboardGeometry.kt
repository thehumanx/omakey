package dev.omakey.core.predict.spatial

import dev.omakey.core.layout.KeyType
import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.Layouts
import kotlin.math.abs

/**
 * Where the letter keys physically sit, so that "how likely is it the user meant X when they typed
 * Y" can account for the two keys being neighbours rather than treating every substitution as
 * equally plausible.
 *
 * Without this, correction runs on plain edit distance, where "helko" → "hello" and "helzo" →
 * "hello" cost exactly the same even though `k` is next to `l` and `z` is on the other side of the
 * keyboard. VelociTap measured decoding that ignores key positions at 20.2% character error rate
 * against 4.7% for a decoder that models them; that gap is the single largest one in this area.
 *
 * **Derived from a [KeyboardLayout]** ([from]), using the same arithmetic the renderer uses to size
 * keys (`computeKeyWidthsPx`: each row's width is shared by its own total weight). Units are a
 * "reference key width" horizontally — the width of a 1.0-weight key in the widest letter row — and
 * a row height vertically. For QWERTY that reproduces the stagger this table used to hardcode: the
 * middle row's 9 keys spread across the width the top row gives 10, and the bottom row is offset by
 * the 1.5-weight shift key, which is exactly what makes `s` sit between `w` and `e`. It was written
 * out by hand until AGENTS.md §66 Phase 2; deriving it means AZERTY, a Spanish ñ, or a Devanagari
 * layout get a correct table instead of an English one. `KeyboardGeometryTest` pins the derived
 * QWERTY coordinates to the old hand-written ones, because σ and the substitution cap were tuned
 * against them.
 *
 * This is a *static* approximation: it assumes each keypress landed at the centre of its key. The
 * real distribution is a per-key Gaussian around a point the user's thumb actually chose, which is
 * measurably offset from the key centre for most people.
 */
class KeyboardGeometry private constructor(
    /** Key characters, sorted, for the binary-search fallback above [DIRECT_LIMIT]. */
    private val sortedChars: CharArray,
    private val sortedIndex: IntArray,
    private val centerX: FloatArray,
    private val centerY: FloatArray,
) {

    /** Direct lookup for everything below [DIRECT_LIMIT] (Latin, Latin extensions, Devanagari):
     * [squaredDistance] runs thousands of times per keystroke and must not hash or box a `Char`. */
    private val directIndex = IntArray(DIRECT_LIMIT) { -1 }.also { table ->
        // Both cases are entered, so a lookup is one array read with no lowercasing call.
        for (i in sortedChars.indices) {
            val c = sortedChars[i]
            if (c.code < DIRECT_LIMIT) table[c.code] = sortedIndex[i]
            val upper = c.uppercaseChar()
            if (upper != c && upper.code < DIRECT_LIMIT && table[upper.code] < 0) table[upper.code] = sortedIndex[i]
        }
    }

    /**
     * Squared distance between the keys for [a] and [b], in key widths — squared because every
     * caller wants it that way (a Gaussian exponent), so taking a square root here only to have it
     * immediately re-squared would be wasted work on a path that runs thousands of times per
     * keystroke.
     *
     * Returns [UNRELATED] for characters that aren't keys on this layout — digits, punctuation,
     * accented characters chosen from a long-press popup. Those genuinely have no meaningful
     * distance to a letter, and guessing one would invent evidence.
     */
    fun squaredDistance(a: Char, b: Char): Float {
        if (a == b) return 0f
        val first = index(a)
        val second = index(b)
        if (first < 0 || second < 0) return UNRELATED
        val dx = centerX[first] - centerX[second]
        val dy = centerY[first] - centerY[second]
        return dx * dx + dy * dy
    }

    /**
     * Squared distance from an actual touch point to the centre of [key], in the same normalised
     * units as [squaredDistance] — the version used when real tap coordinates are available.
     * [squaredDistance] is the degraded form of this that assumes every tap landed dead centre.
     */
    fun squaredDistanceFromPoint(x: Float, y: Float, key: Char): Float {
        val index = index(key)
        if (index < 0) return UNRELATED
        val dx = x - centerX[index]
        val dy = y - centerY[index]
        return dx * dx + dy * dy
    }

    /** Grid position of [key]'s centre, for tests and for simulating taps. */
    fun centerOf(key: Char): Pair<Float, Float>? {
        val index = index(key)
        return if (index < 0) null else centerX[index] to centerY[index]
    }

    /** Whether [a] and [b] are immediate neighbours — within roughly one key of each other in both
     * directions. Used for cheap checks that don't need the full distance. */
    fun areAdjacent(a: Char, b: Char): Boolean {
        val first = index(a)
        val second = index(b)
        if (first < 0 || second < 0) return false
        return abs(centerX[first] - centerX[second]) <= 1.2f && abs(centerY[first] - centerY[second]) <= 1.1f
    }

    /** Every key character on the layout, in no particular order. */
    val keys: List<Char> get() = sortedChars.toList()

    // Runs inside the correction DP: one branch and one array read on the common path, with both
    // cases already entered in the table so there is no lowercasing call.
    private fun index(character: Char): Int {
        val code = character.code
        return if (code < DIRECT_LIMIT) directIndex[code] else indexAboveDirectLimit(character)
    }

    private fun indexAboveDirectLimit(character: Char): Int {
        val found = sortedChars.binarySearch(character.lowercaseChar())
        return if (found >= 0) sortedIndex[found] else -1
    }

    companion object {
        /** Squared distance stand-in for a pair that has no position relationship. Large enough that
         * any such substitution is charged the full cap, without being infinite — an unrelated
         * substitution is implausible, not impossible. */
        const val UNRELATED = 100f

        /** Covers U+0000–U+0FFF: every Latin block and Devanagari (U+0900–097F). */
        private const val DIRECT_LIMIT = 0x1000

        /** The English layout's geometry — the default wherever no layout is given. */
        val QWERTY: KeyboardGeometry by lazy { from(Layouts.QwertyEnUS) }

        /**
         * Builds the table for [layout]. A key takes part if it is a [KeyType.CHARACTER] key whose
         * label is a single character accepted by [isKeyChar] (letters by default), which leaves out
         * the space row's "." key and every control key. Rows with no such key are skipped for
         * sizing but keep their index, so y stays the real row position.
         */
        fun from(layout: KeyboardLayout, isKeyChar: (Char) -> Boolean = Char::isLetter): KeyboardGeometry {
            fun keyChar(label: String): Char? = label.singleOrNull()?.lowercaseChar()?.takeIf(isKeyChar)

            val letterRows = layout.rows.filter { row ->
                row.keys.any { it.keyType == KeyType.CHARACTER && keyChar(it.label) != null }
            }
            require(letterRows.isNotEmpty()) { "Layout ${layout.id} has no letter keys" }
            val referenceWeight = letterRows.maxOf { row -> row.keys.sumOf { it.widthWeight.toDouble() }.toFloat() }

            val chars = ArrayList<Char>()
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            for ((rowIndex, row) in layout.rows.withIndex()) {
                val rowWeight = row.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
                if (rowWeight <= 0f) continue
                val scale = referenceWeight / rowWeight
                var start = 0f
                for (key in row.keys) {
                    val c = if (key.keyType == KeyType.CHARACTER) keyChar(key.label) else null
                    if (c != null && c !in chars) {
                        chars += c
                        xs += (start + key.widthWeight / 2f) * scale
                        ys += rowIndex + 0.5f
                    }
                    start += key.widthWeight
                }
            }

            val order = chars.indices.sortedBy { chars[it] }
            return KeyboardGeometry(
                sortedChars = CharArray(order.size) { chars[order[it]] },
                sortedIndex = IntArray(order.size) { order[it] },
                centerX = xs.toFloatArray(),
                centerY = ys.toFloatArray(),
            )
        }
    }
}
