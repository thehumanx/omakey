package dev.omakey.core.layout

/**
 * Where the keyboard sits in the IME window.
 *
 * Deliberately one enum rather than a pair of booleans ("floating" + "one-handed side"): the three
 * arrangements are mutually exclusive, and the boolean form makes it possible to represent states
 * that don't exist (floating *and* one-handed) which every call site would then have to rule out.
 */
enum class KeyboardPlacement {
    /** Full width, pinned to the bottom — the default and what every version before this did. */
    DOCKED,

    /** Detached: a smaller keyboard the user positions anywhere, with the app visible and touchable
     * around it. See `OmakeyInputMethodService.onComputeInsets` for what makes that possible. */
    FLOATING,

    ONE_HANDED_LEFT,
    ONE_HANDED_RIGHT,
    ;

    val isOneHanded: Boolean get() = this == ONE_HANDED_LEFT || this == ONE_HANDED_RIGHT
}

/**
 * Clamping rules for keyboard size and position, kept as pure arithmetic with no Android or Compose
 * dependency so they can be unit-tested — the rest of this feature is window insets and Compose
 * layout, neither of which a JVM test can reach.
 *
 * Every value the user can drag passes through here. The invariant is that **the keyboard is always
 * fully on screen and always big enough to type on**: a drag that would push it off an edge stops at
 * the edge rather than being rejected, which is what makes dragging feel continuous instead of
 * sticky.
 */
object KeyboardPlacementGeometry {

    const val MIN_FLOATING_WIDTH_DP = 240
    const val MIN_FLOATING_HEIGHT_DP = 160
    const val MAX_FLOATING_HEIGHT_DP = 400

    const val MIN_ONE_HANDED_WIDTH_DP = 220

    /** Width of the gutter holding the one-handed side buttons. The keyboard is capped so it can
     * never grow into it — otherwise "switch side" and "full width" end up underneath the keys,
     * leaving no way out of one-handed mode. */
    const val ONE_HANDED_GUTTER_DP = 56

    /** How much of the floating keyboard must stay on screen horizontally. Not the whole thing: a
     * keyboard nudged slightly off the edge and unable to come back is worse than one that can
     * overhang a little. */
    const val MIN_VISIBLE_DP = 80

    fun clampFloatingWidth(widthDp: Int, screenWidthDp: Int): Int =
        widthDp.coerceIn(MIN_FLOATING_WIDTH_DP, maxOf(MIN_FLOATING_WIDTH_DP, screenWidthDp))

    fun clampFloatingHeight(heightDp: Int, screenHeightDp: Int): Int =
        heightDp.coerceIn(
            MIN_FLOATING_HEIGHT_DP,
            maxOf(MIN_FLOATING_HEIGHT_DP, minOf(MAX_FLOATING_HEIGHT_DP, screenHeightDp)),
        )

    fun clampOneHandedWidth(widthDp: Int, screenWidthDp: Int): Int {
        val ceiling = screenWidthDp - ONE_HANDED_GUTTER_DP
        return widthDp.coerceIn(MIN_ONE_HANDED_WIDTH_DP, maxOf(MIN_ONE_HANDED_WIDTH_DP, ceiling))
    }

    /** Horizontal position of the floating keyboard's left edge, measured from the window's left. */
    fun clampFloatingX(xDp: Int, widthDp: Int, screenWidthDp: Int): Int {
        val leftmost = MIN_VISIBLE_DP - widthDp
        val rightmost = screenWidthDp - MIN_VISIBLE_DP
        return xDp.coerceIn(minOf(leftmost, rightmost), maxOf(leftmost, rightmost))
    }

    /**
     * Vertical position of the floating keyboard's bottom edge, measured **up from the window's
     * bottom** — the opposite of the usual top-down convention, and on purpose: a keyboard sits at
     * the bottom, so "0" meaning "resting on the bottom edge" is the value the user's fingers are
     * actually anchored to, and it stays put when the window height changes.
     */
    fun clampFloatingY(yDp: Int, heightDp: Int, screenHeightDp: Int): Int =
        yDp.coerceIn(0, maxOf(0, screenHeightDp - heightDp))

    /** Where a floating keyboard should first appear: horizontally centred, lifted clear of the
     * navigation area. Used when the user switches to floating for the first time, so it never
     * starts somewhere it has to be dragged out of. */
    fun defaultFloatingX(widthDp: Int, screenWidthDp: Int): Int =
        clampFloatingX((screenWidthDp - widthDp) / 2, widthDp, screenWidthDp)

    const val DEFAULT_FLOATING_Y_DP = 48
}
