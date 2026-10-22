package dev.omakey.core.input

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeUpActionTest {

    @Test
    fun `the typed word showing is saved`() {
        assertEquals(SwipeUpAction.SAVE, SwipeUpAction.decide(cycleIndex = -1, hasWordInFocus = true))
    }

    @Test
    fun `nothing to go back to is saved`() {
        assertEquals(SwipeUpAction.SAVE, SwipeUpAction.decide(cycleIndex = -1, hasWordInFocus = false))
        assertEquals(SwipeUpAction.SAVE, SwipeUpAction.decide(cycleIndex = 2, hasWordInFocus = false))
    }

    @Test
    fun `the first candidate showing - swiped to or autocorrected - restores without saving`() {
        assertEquals(SwipeUpAction.RESTORE_TYPED, SwipeUpAction.decide(cycleIndex = 0, hasWordInFocus = true))
    }

    @Test
    fun `deeper candidates step back one at a time`() {
        assertEquals(SwipeUpAction.STEP_BACK, SwipeUpAction.decide(cycleIndex = 2, hasWordInFocus = true))
    }

    @Test
    fun `swipe down then two swipes up restores and then saves`() {
        var index = 0 // after one swipe down
        val first = SwipeUpAction.decide(index, hasWordInFocus = true)
        assertEquals(SwipeUpAction.RESTORE_TYPED, first)
        index = -1 // restored
        assertEquals(SwipeUpAction.SAVE, SwipeUpAction.decide(index, hasWordInFocus = true))
    }
}
