package dev.omakey.core.gesture

import dev.omakey.core.layout.SpecialKeyCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacebarSwipeTest {

    @Test
    fun `a sideways slide that starts on the spacebar still types a space`() {
        assertTrue(SpacebarSwipe.typesSpace(SwipeDirection.LEFT, SpecialKeyCode.SPACE))
        assertTrue(SpacebarSwipe.typesSpace(SwipeDirection.RIGHT, SpecialKeyCode.SPACE))
    }

    @Test
    fun `vertical swipes on the spacebar keep cycling suggestions`() {
        assertFalse(SpacebarSwipe.typesSpace(SwipeDirection.UP, SpecialKeyCode.SPACE))
        assertFalse(SpacebarSwipe.typesSpace(SwipeDirection.DOWN, SpecialKeyCode.SPACE))
    }

    @Test
    fun `swipes from other keys are untouched`() {
        for (direction in SwipeDirection.entries) {
            assertFalse(SpacebarSwipe.typesSpace(direction, 'a'.code))
            assertFalse(SpacebarSwipe.typesSpace(direction, SpecialKeyCode.BACKSPACE))
        }
    }
}
