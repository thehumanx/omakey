package dev.omakey.core.gesture

import dev.omakey.core.layout.SpecialKeyCode

/**
 * The "swipe the spacebar to switch language" setting ([GestureSettings.swipeSpacebarForLanguage]).
 *
 * Only a swipe that *starts* on the spacebar counts, so the whole-keyboard swipes (left deletes a
 * word, right types a space) keep working everywhere else. Holding the spacebar and then dragging
 * still moves the cursor: that engages after the long-press delay, and a quick swipe commits
 * before it.
 */
object SpacebarLanguageSwipe {

    /** Which way to step through languages: right is the next one, left the previous one. */
    enum class Step { NEXT, PREVIOUS }

    /**
     * The language step [direction] makes, or null when this swipe is not a language switch and
     * should be handled as an ordinary swipe. Needs two or more languages: with one there is
     * nothing to switch to, and the spacebar keeps its usual swipe behaviour.
     */
    fun step(
        direction: SwipeDirection,
        downKeyCode: Int,
        enabled: Boolean,
        languageCount: Int,
    ): Step? {
        if (!enabled || languageCount < 2 || downKeyCode != SpecialKeyCode.SPACE) return null
        return when (direction) {
            SwipeDirection.RIGHT -> Step.NEXT
            SwipeDirection.LEFT -> Step.PREVIOUS
            SwipeDirection.UP, SwipeDirection.DOWN -> null
        }
    }
}
