package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The float-precision clamps, which exist because the integer ones silently ate drag movement.
 */
class PlacementGeometryPrecisionTest {

    private val screenWidth = 400
    private val screenHeight = 800
    private val keyboardWidth = 300f
    private val keyboardHeight = 250f

    @Test
    fun `a sub-dp drag accumulates instead of vanishing`() {
        // The bug: the old code rounded the running total back to a whole dp every frame, so a
        // per-frame delta under half a dp resolved to the same value forever and the keyboard did
        // not move at all. Ten frames of 0.3dp must add up to 3dp.
        var x = 100f
        repeat(10) {
            x = KeyboardPlacementGeometry.clampFloatingX(x + 0.3f, keyboardWidth, screenWidth)
        }

        assertEquals(103f, x, 0.001f)
    }

    @Test
    fun `the same is true vertically`() {
        var y = 50f
        repeat(10) {
            y = KeyboardPlacementGeometry.clampFloatingY(y + 0.3f, keyboardHeight, screenHeight)
        }

        assertEquals(53f, y, 0.001f)
    }

    @Test
    fun `float and int clamps agree on whole values`() {
        // The Int overload delegates to the Float one, so they cannot drift — but the rounding
        // boundary is worth pinning.
        for (x in listOf(-500, -80, 0, 100, 320, 400, 900)) {
            assertEquals(
                KeyboardPlacementGeometry.clampFloatingX(x, keyboardWidth.toInt(), screenWidth),
                KeyboardPlacementGeometry.clampFloatingX(x.toFloat(), keyboardWidth, screenWidth).toInt(),
            )
        }
    }

    @Test
    fun `a floating keyboard cannot be dragged fully off screen`() {
        val farLeft = KeyboardPlacementGeometry.clampFloatingX(-9999f, keyboardWidth, screenWidth)
        val farRight = KeyboardPlacementGeometry.clampFloatingX(9999f, keyboardWidth, screenWidth)

        // Some of it must remain reachable, or it can never be dragged back.
        assertTrue(farLeft + keyboardWidth >= KeyboardPlacementGeometry.MIN_VISIBLE_DP)
        assertTrue(farRight <= screenWidth - KeyboardPlacementGeometry.MIN_VISIBLE_DP)
    }

    @Test
    fun `a docked keyboard cannot be raised past the middle of the screen`() {
        val raised = KeyboardPlacementGeometry.clampBottomOffset(9999f, keyboardHeight, screenHeight)

        // Above this it stops being "raised for thumb reach" and becomes stranded mid-screen.
        assertEquals(screenHeight / 2f - keyboardHeight, raised, 0.001f)
    }

    @Test
    fun `a docked keyboard cannot be pushed below the bottom edge`() {
        assertEquals(0f, KeyboardPlacementGeometry.clampBottomOffset(-50f, keyboardHeight, screenHeight), 0.001f)
    }

    @Test
    fun `a keyboard taller than half the screen simply cannot be raised`() {
        // The cap is about where the top ends up, so a tall keyboard legitimately has no room.
        val tall = screenHeight * 0.75f

        assertEquals(0f, KeyboardPlacementGeometry.clampBottomOffset(100f, tall, screenHeight), 0.001f)
    }
}
