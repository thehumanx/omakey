package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clamping rules behind every drag. This is the only part of the placement feature a JVM test
 * can reach — the rest is Compose layout and IME window insets — so the invariants that matter most
 * are asserted here: the keyboard never ends up unreachable, and never ends up too small to type on.
 */
class KeyboardPlacementGeometryTest {

    private val screenWidth = 400
    private val screenHeight = 800

    // --- sizes -----------------------------------------------------------------------------------

    @Test
    fun `floating size is held between its floor and the screen`() {
        assertEquals(320, KeyboardPlacementGeometry.clampFloatingWidth(320, screenWidth))
        assertEquals(
            KeyboardPlacementGeometry.MIN_FLOATING_WIDTH_DP,
            KeyboardPlacementGeometry.clampFloatingWidth(10, screenWidth),
        )
        assertEquals(screenWidth, KeyboardPlacementGeometry.clampFloatingWidth(9999, screenWidth))
        assertEquals(
            KeyboardPlacementGeometry.MAX_FLOATING_HEIGHT_DP,
            KeyboardPlacementGeometry.clampFloatingHeight(9999, screenHeight),
        )
    }

    @Test
    fun `a screen narrower than the minimum still yields the minimum`() {
        // A foldable's cover display, or a small freeform window. Returning something below the
        // minimum would be worse than overflowing: the keys would be untappable either way, and at
        // least this way the layout is coherent.
        val tiny = 100
        assertEquals(
            KeyboardPlacementGeometry.MIN_FLOATING_WIDTH_DP,
            KeyboardPlacementGeometry.clampFloatingWidth(50, tiny),
        )
        assertEquals(
            KeyboardPlacementGeometry.MIN_FLOATING_HEIGHT_DP,
            KeyboardPlacementGeometry.clampFloatingHeight(50, 120),
        )
    }

    @Test
    fun `one-handed width always leaves the gutter intact`() {
        // The gutter holds "switch side" and "full width". A keyboard that grew over them would be
        // a one-handed mode with no way out of it.
        val clamped = KeyboardPlacementGeometry.clampOneHandedWidth(9999, screenWidth)
        assertEquals(screenWidth - KeyboardPlacementGeometry.ONE_HANDED_GUTTER_DP, clamped)
        assertTrue(
            "gutter must survive at every screen width",
            (200..1200 step 37).all {
                it - KeyboardPlacementGeometry.clampOneHandedWidth(9999, it) >=
                    KeyboardPlacementGeometry.ONE_HANDED_GUTTER_DP ||
                    KeyboardPlacementGeometry.clampOneHandedWidth(9999, it) ==
                    KeyboardPlacementGeometry.MIN_ONE_HANDED_WIDTH_DP
            },
        )
        assertEquals(
            KeyboardPlacementGeometry.MIN_ONE_HANDED_WIDTH_DP,
            KeyboardPlacementGeometry.clampOneHandedWidth(0, screenWidth),
        )
    }

    // --- position --------------------------------------------------------------------------------

    @Test
    fun `a floating keyboard always keeps a grabbable strip on screen`() {
        val width = 320
        val farLeft = KeyboardPlacementGeometry.clampFloatingX(-9999, width, screenWidth)
        val farRight = KeyboardPlacementGeometry.clampFloatingX(9999, width, screenWidth)

        assertTrue("left edge must leave something visible", farLeft + width >= KeyboardPlacementGeometry.MIN_VISIBLE_DP)
        assertTrue("right edge must leave something visible", farRight <= screenWidth - KeyboardPlacementGeometry.MIN_VISIBLE_DP)
    }

    @Test
    fun `an ordinary horizontal position passes through untouched`() {
        assertEquals(40, KeyboardPlacementGeometry.clampFloatingX(40, 320, screenWidth))
    }

    @Test
    fun `vertical position runs from resting on the bottom to the top of the screen`() {
        val height = 240
        assertEquals(0, KeyboardPlacementGeometry.clampFloatingY(-50, height, screenHeight))
        assertEquals(screenHeight - height, KeyboardPlacementGeometry.clampFloatingY(9999, height, screenHeight))
        assertEquals(48, KeyboardPlacementGeometry.clampFloatingY(48, height, screenHeight))
    }

    @Test
    fun `a keyboard taller than the screen is pinned to the bottom rather than going negative`() {
        assertEquals(0, KeyboardPlacementGeometry.clampFloatingY(100, 900, screenHeight))
    }

    @Test
    fun `the first floating position is centred and already valid`() {
        val width = 320
        val x = KeyboardPlacementGeometry.defaultFloatingX(width, screenWidth)

        assertEquals(40, x)
        assertEquals("must not need clamping on its first use", x, KeyboardPlacementGeometry.clampFloatingX(x, width, screenWidth))
    }

    // --- the enum ---------------------------------------------------------------------------------

    @Test
    fun `isOneHanded covers both sides and nothing else`() {
        assertTrue(KeyboardPlacement.ONE_HANDED_LEFT.isOneHanded)
        assertTrue(KeyboardPlacement.ONE_HANDED_RIGHT.isOneHanded)
        assertTrue(!KeyboardPlacement.DOCKED.isOneHanded)
        assertTrue(!KeyboardPlacement.FLOATING.isOneHanded)
    }
}
