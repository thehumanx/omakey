package dev.omakey.core.gesture

import dev.omakey.core.gesture.SpacebarLanguageSwipe.Step
import dev.omakey.core.layout.SpecialKeyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpacebarLanguageSwipeTest {

    private fun step(
        direction: SwipeDirection,
        downKeyCode: Int = SpecialKeyCode.SPACE,
        enabled: Boolean = true,
        languageCount: Int = 2,
    ) = SpacebarLanguageSwipe.step(direction, downKeyCode, enabled, languageCount)

    @Test
    fun `right on the spacebar is the next language, left the previous`() {
        assertEquals(Step.NEXT, step(SwipeDirection.RIGHT))
        assertEquals(Step.PREVIOUS, step(SwipeDirection.LEFT))
    }

    @Test
    fun `vertical swipes on the spacebar are left alone`() {
        assertNull(step(SwipeDirection.UP))
        assertNull(step(SwipeDirection.DOWN))
    }

    @Test
    fun `a swipe starting on a letter keeps its usual job`() {
        assertNull(step(SwipeDirection.LEFT, downKeyCode = 'a'.code))
        assertNull(step(SwipeDirection.RIGHT, downKeyCode = 'a'.code))
    }

    @Test
    fun `nothing happens with the setting off`() {
        assertNull(step(SwipeDirection.RIGHT, enabled = false))
        assertNull(step(SwipeDirection.LEFT, enabled = false))
    }

    @Test
    fun `nothing happens with one language`() {
        assertNull(step(SwipeDirection.RIGHT, languageCount = 1))
        assertNull(step(SwipeDirection.LEFT, languageCount = 1))
    }
}
