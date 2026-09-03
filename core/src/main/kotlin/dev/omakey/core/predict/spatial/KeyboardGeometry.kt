package dev.omakey.core.predict.spatial

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
 * Coordinates mirror `Layouts.QwertyEnUS`, in units of a top-row key width horizontally and a row
 * height vertically. The three letter rows have different key counts (10 / 9 / 7 with shift and
 * backspace flanking), and `computeKeyWidthsPx` divides each row's width by its own total weight,
 * so the middle row's keys really are wider and its letters really do sit off the grid the top row
 * establishes. That stagger is reproduced here rather than idealised away, because it is exactly
 * what makes `s` sit between `w` and `e` — the kind of near-miss this table exists to price.
 *
 * This is a *static* approximation: it assumes each keypress landed at the centre of its key. The
 * real distribution is a per-key Gaussian around a point the user's thumb actually chose, which is
 * measurably offset from the key centre for most people. Feeding actual touch coordinates in is
 * the next step; this class is shaped so that becomes a change of input, not a change of model.
 */
object KeyboardGeometry {

    private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    /** Leading offset of each row, in top-row key widths. Row 2 is shifted by the 1.5-weight shift
     * key that precedes it; row 1 is centred by its own wider keys rather than an explicit offset. */
    private val ROW_OFFSET = floatArrayOf(0f, 0f, 1.5f)

    /** Width of one key in each row, relative to a top-row key. Row 1 spreads 9 keys across the
     * same width the top row gives to 10. */
    private val ROW_KEY_WIDTH = floatArrayOf(1f, 10f / 9f, 1f)

    private const val ALPHABET_SIZE = 26
    private val centerX = FloatArray(ALPHABET_SIZE)
    private val centerY = FloatArray(ALPHABET_SIZE)
    private val known = BooleanArray(ALPHABET_SIZE)

    init {
        for ((rowIndex, row) in ROWS.withIndex()) {
            for ((column, character) in row.withIndex()) {
                val index = character - 'a'
                centerX[index] = ROW_OFFSET[rowIndex] + (column + 0.5f) * ROW_KEY_WIDTH[rowIndex]
                centerY[index] = rowIndex + 0.5f
                known[index] = true
            }
        }
    }

    /**
     * Squared distance between the keys for [a] and [b], in key widths — squared because every
     * caller wants it that way (a Gaussian exponent), so taking a square root here only to have it
     * immediately re-squared would be wasted work on a path that runs thousands of times per
     * keystroke.
     *
     * Returns [UNRELATED] for characters that aren't letters on this layout — digits, punctuation,
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
     * units as [squaredDistance] — the version used when real tap coordinates are available, which
     * is what the model is really for. [squaredDistance] is the degraded form of this that assumes
     * every tap landed dead centre.
     *
     * Returns [UNRELATED] for characters not on the letter grid, same as [squaredDistance].
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

    private fun index(character: Char): Int {
        val lower = character.lowercaseChar()
        if (lower < 'a' || lower > 'z') return -1
        val index = lower - 'a'
        return if (known[index]) index else -1
    }

    /** Squared distance stand-in for a pair that has no position relationship. Large enough that
     * any such substitution is charged the full cap, without being infinite — an unrelated
     * substitution is implausible, not impossible. */
    const val UNRELATED = 100f
}
