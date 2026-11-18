package dev.omakey.core.gesture

import dev.omakey.core.layout.SpecialKeyCode

/**
 * A thumb hitting the spacebar often slides along it. The spacebar is several keys wide, so that
 * slide crosses the swipe threshold far more often than on a letter, and a quick sideways swipe
 * from the spacebar used to do nothing at all (left is kept off word-delete so it can't race the
 * hold-to-move-cursor gesture; right did nothing with "swipe right for space" off). Reported on a
 * Galaxy S25 Ultra as "the spacebar is less sensitive than the other keys".
 *
 * So a sideways swipe that starts on the spacebar types a space, as the tap it almost always was.
 * Language switching ([SpacebarLanguageSwipe]) is checked first and still wins when it is on;
 * holding the spacebar and then dragging still moves the cursor. Up and down keep their
 * keyboard-wide meaning (cycling suggestions and punctuation).
 */
object SpacebarSwipe {
    fun typesSpace(direction: SwipeDirection, downKeyCode: Int): Boolean =
        downKeyCode == SpecialKeyCode.SPACE &&
            (direction == SwipeDirection.LEFT || direction == SwipeDirection.RIGHT)
}
