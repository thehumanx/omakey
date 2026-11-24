package dev.omakey.core.ui

/**
 * The arithmetic behind drag to reorder (Settings → Bar pages): which slot a dragged row has
 * reached, and how far its neighbours slide to make room. Pure, so it's tested without a screen.
 *
 * Rows are stacked top to bottom with [gap] between them. A dragged row takes a neighbour's slot
 * once its own centre passes that neighbour's centre, the rule people expect from iOS and
 * Android launchers: half-way is the point of no return in both directions.
 */
object Reorder {
    /** The slot row [from] lands in after being dragged [dy] (down is positive). */
    fun slotFor(from: Int, dy: Float, heights: List<Float>, gap: Float): Int {
        if (heights.isEmpty()) return from
        val centres = centres(heights, gap)
        val c = centres[from] + dy
        var slot = from
        while (slot + 1 < heights.size && c > centres[slot + 1]) slot++
        while (slot - 1 >= 0 && slot <= from && c < centres[slot - 1]) slot--
        return slot
    }

    /** How far row [index] slides while row [from] is held over slot [to]. */
    fun shiftFor(index: Int, from: Int, to: Int, draggedHeight: Float, gap: Float): Float = when {
        index == from -> 0f
        to > from && index in (from + 1)..to -> -(draggedHeight + gap)
        to < from && index in to until from -> draggedHeight + gap
        else -> 0f
    }

    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (from == to) return list
        val out = list.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }

    private fun centres(heights: List<Float>, gap: Float): List<Float> {
        var top = 0f
        return heights.map { h -> (top + h / 2).also { top += h + gap } }
    }
}
