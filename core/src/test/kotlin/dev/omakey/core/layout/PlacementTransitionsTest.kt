package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The placement tiles' toggle rules. Small, but each one encodes a decision that is easy to
 * "simplify" into something subtly worse, and they previously lived inside `KeyboardViewModel`
 * where nothing could check them.
 */
class PlacementTransitionsTest {

    @Test
    fun `tapping a tile switches to that placement`() {
        assertEquals(
            KeyboardPlacement.FLOATING,
            KeyboardPlacement.DOCKED.toggledWith(KeyboardPlacement.FLOATING),
        )
    }

    @Test
    fun `tapping the tile you are already in returns to docked`() {
        // Every tile is its own off switch; there is no separate "back to normal" control.
        assertEquals(
            KeyboardPlacement.DOCKED,
            KeyboardPlacement.FLOATING.toggledWith(KeyboardPlacement.FLOATING),
        )
    }

    @Test
    fun `switching between two non-docked placements does not pass through docked`() {
        assertEquals(
            KeyboardPlacement.FLOATING,
            KeyboardPlacement.ONE_HANDED_LEFT.toggledWith(KeyboardPlacement.FLOATING),
        )
    }

    @Test
    fun `the one-handed tile starts on the right`() {
        // Majority hand.
        assertEquals(KeyboardPlacement.ONE_HANDED_RIGHT, KeyboardPlacement.DOCKED.nextOneHanded())
        assertEquals(KeyboardPlacement.ONE_HANDED_RIGHT, KeyboardPlacement.FLOATING.nextOneHanded())
    }

    @Test
    fun `the one-handed tile flips sides before switching off`() {
        // The rule that matters: a second tap must flip, not exit, or the tile disagrees with the
        // gutter's own switch-side button about what "tap again" means.
        assertEquals(
            KeyboardPlacement.ONE_HANDED_LEFT,
            KeyboardPlacement.ONE_HANDED_RIGHT.nextOneHanded(),
        )
        assertEquals(KeyboardPlacement.DOCKED, KeyboardPlacement.ONE_HANDED_LEFT.nextOneHanded())
    }

    @Test
    fun `the full one-handed cycle returns to docked in three taps`() {
        val afterThree = KeyboardPlacement.DOCKED.nextOneHanded().nextOneHanded().nextOneHanded()
        assertEquals(KeyboardPlacement.DOCKED, afterThree)
    }

    @Test
    fun `switching side never lands on docked`() {
        // It backs a button that only exists while one-handed — landing on DOCKED would dismiss
        // the gutter being pressed.
        assertEquals(
            KeyboardPlacement.ONE_HANDED_LEFT,
            KeyboardPlacement.ONE_HANDED_RIGHT.flippedOneHandedSide(),
        )
        assertEquals(
            KeyboardPlacement.ONE_HANDED_RIGHT,
            KeyboardPlacement.ONE_HANDED_LEFT.flippedOneHandedSide(),
        )
    }

    @Test
    fun `isOneHanded covers both sides and nothing else`() {
        assertEquals(true, KeyboardPlacement.ONE_HANDED_LEFT.isOneHanded)
        assertEquals(true, KeyboardPlacement.ONE_HANDED_RIGHT.isOneHanded)
        assertEquals(false, KeyboardPlacement.DOCKED.isOneHanded)
        assertEquals(false, KeyboardPlacement.FLOATING.isOneHanded)
    }
}
