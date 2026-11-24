package dev.omakey.core.layout

/**
 * The gap drawn between keys (Normal layout; Grid mode has none). Keys fill their whole row, so
 * with a fixed 1.5 dp gap a tall keyboard drew tall slabs, the spacebar most visibly: its accent
 * fill made it read as a thick bar (real feedback). The vertical gap now grows with the row
 * height, so a taller keyboard gets more space between rows rather than fatter keys. The touch
 * area is the whole cell either way: only the drawing is inset.
 */
object KeyInsets {

    const val MIN_VERTICAL_DP = 1.5f
    const val MAX_VERTICAL_DP = 8f
    const val HORIZONTAL_DP = 2.5f

    /** Rows of 30 dp and under keep the old 1.5 dp; each dp of row above that adds 0.16 dp of
     * gap per side, up to [MAX_VERTICAL_DP]. A 55 dp row gets 5.5 dp. */
    fun verticalDp(rowHeightDp: Int): Float =
        (MIN_VERTICAL_DP + (rowHeightDp - 30) * 0.16f).coerceIn(MIN_VERTICAL_DP, MAX_VERTICAL_DP)
}
