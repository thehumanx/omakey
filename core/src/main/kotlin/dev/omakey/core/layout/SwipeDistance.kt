package dev.omakey.core.layout

/**
 * Distances measured against the key grid's height, corrected for rows that aren't drawn.
 *
 * Compact mode draws one row fewer, so the grid is a quarter shorter, and anything measured as a
 * fraction of its height shrank with it: vertical swipes fired after three quarters of the usual
 * distance, and a resize drag moved the keyboard's edge at three quarters of the finger's speed.
 * Both are now measured against the height the full keyboard would have.
 */
object SwipeDistance {

    private const val VERTICAL_FRACTION = 0.18f

    /** How far a finger must travel up or down for a vertical swipe. */
    fun verticalPx(gridHeightPx: Float, drawnRows: Int, baseRows: Int, sensitivity: Float): Float =
        fullHeight(gridHeightPx, drawnRows, baseRows) * VERTICAL_FRACTION * sensitivity

    /** A resize drag of [visibleDeltaDp] on the drawn grid, as a change to the full keyboard
     * height, so the visible edge follows the finger. */
    fun fullHeightDelta(visibleDeltaDp: Float, drawnRows: Int, baseRows: Int): Float =
        fullHeight(visibleDeltaDp, drawnRows, baseRows)

    private fun fullHeight(drawn: Float, drawnRows: Int, baseRows: Int): Float =
        if (drawnRows <= 0 || baseRows <= 0) drawn else drawn * baseRows / drawnRows
}
