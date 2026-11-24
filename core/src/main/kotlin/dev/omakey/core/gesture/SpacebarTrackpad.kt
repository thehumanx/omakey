package dev.omakey.core.gesture

/**
 * Holding the spacebar turns the key grid into a blank trackpad (as Fleksy did): sideways slides
 * move the cursor, and a "SELECT ALL" pill near the top of the grid selects all the text when the
 * finger lifts on it. This is the pill's geometry and the "is the finger on it" rule; `KeyGrid`
 * draws the pill and runs the gesture. All values are pixels in the key grid's own coordinates.
 */
object SpacebarTrackpad {

    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** The pill, centred horizontally, [topMargin] below the grid's top. Never wider than the grid
     * (a floating or one-handed keyboard can be narrower than the pill's preferred width). */
    fun pill(gridWidth: Float, pillWidth: Float, pillHeight: Float, topMargin: Float): Bounds {
        val width = pillWidth.coerceAtMost(gridWidth)
        val left = (gridWidth - width) / 2f
        return Bounds(left, topMargin, left + width, topMargin + pillHeight)
    }

    /**
     * Whether the finger counts as on the pill: within its width (plus [slop]) and no lower than
     * its bottom (plus [slop]). There is deliberately no top bound: a thumb sliding up to a small
     * target tends to overshoot, even past the grid into the suggestion bar, and that is still
     * plainly "up onto the pill". Missing it sideways or stopping short below does not count.
     */
    fun onPill(x: Float, y: Float, pill: Bounds, slop: Float): Boolean =
        x >= pill.left - slop && x <= pill.right + slop && y <= pill.bottom + slop

    enum class Lift { SELECT_ALL, SPACE, NOTHING }

    /**
     * What lifting the finger does. A [cancelled] touch (the system took it away: the keyboard
     * hid, the notification shade was pulled) does nothing, so Select All only ever happens on a
     * real lift on the pill: the spec's one anti-goal is selecting all by accident.
     */
    fun onLift(onPill: Boolean, cursorMoved: Boolean, cancelled: Boolean): Lift = when {
        cancelled -> Lift.NOTHING
        onPill -> Lift.SELECT_ALL
        !cursorMoved -> Lift.SPACE
        else -> Lift.NOTHING
    }
}
