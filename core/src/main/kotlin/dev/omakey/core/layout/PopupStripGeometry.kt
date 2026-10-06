package dev.omakey.core.layout

import kotlin.math.floor

/**
 * Where the long-press popup's option cells sit: one row of equal cells centred across the key
 * grid, [count] wide, starting at [startPx].
 *
 * Shared by the overlay that draws the cells and the gesture loop that picks one, so the option a
 * finger is over is the option drawn under it. Before, selection went by drag *distance* from the
 * held key while the cells were drawn centred somewhere else entirely — so sliding onto the "ё" you
 * could see selected whatever happened to be that many key-widths away, and a user reported they
 * "can't tap or select" it at all.
 */
class PopupStripGeometry(val startPx: Float, val cellPx: Float, val count: Int) {
    val endPx: Float get() = startPx + cellPx * count

    /** The cell under [x]; a finger beyond either end picks the cell at that end. */
    fun indexAt(x: Float): Int = floor((x - startPx) / cellPx).toInt().coerceIn(0, count - 1)

    companion object {
        /** [count] cells in a strip [widthPx] wide with [paddingPx] each side, each between
         * [minCellPx] and [maxCellPx] wide. Pass at most [maxCells] cells: past that they would not
         * fit even at the minimum width. */
        fun of(widthPx: Float, paddingPx: Float, count: Int, minCellPx: Float, maxCellPx: Float): PopupStripGeometry {
            val available = (widthPx - 2 * paddingPx).coerceAtLeast(0f)
            val n = count.coerceAtLeast(1)
            val cell = (available / n).coerceIn(minCellPx, maxCellPx)
            return PopupStripGeometry(paddingPx + (available - cell * n) / 2f, cell, n)
        }

        fun maxCells(widthPx: Float, paddingPx: Float, minCellPx: Float): Int =
            floor((widthPx - 2 * paddingPx).coerceAtLeast(0f) / minCellPx).toInt().coerceAtLeast(1)
    }
}
