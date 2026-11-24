package dev.omakey.core.gesture

import dev.omakey.core.gesture.SpacebarTrackpad.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacebarTrackpadTest {

    private val pill = Bounds(100f, 10f, 300f, 60f)

    @Test
    fun `pill is centred on the grid`() =
        assertEquals(Bounds(100f, 10f, 300f, 60f), SpacebarTrackpad.pill(400f, 200f, 50f, 10f))

    @Test
    fun `pill never exceeds a narrow grid`() {
        val narrow = SpacebarTrackpad.pill(150f, 200f, 50f, 10f)
        assertEquals(0f, narrow.left)
        assertEquals(150f, narrow.right)
    }

    @Test
    fun `a finger inside the pill is on it`() =
        assertTrue(SpacebarTrackpad.onPill(200f, 30f, pill, slop = 8f))

    @Test
    fun `slop just below the pill still counts`() {
        assertTrue(SpacebarTrackpad.onPill(200f, 67f, pill, slop = 8f))
        assertFalse(SpacebarTrackpad.onPill(200f, 69f, pill, slop = 8f))
    }

    @Test
    fun `overshooting above the pill, even above the grid, still counts`() =
        assertTrue(SpacebarTrackpad.onPill(200f, -40f, pill, slop = 8f))

    @Test
    fun `beside the pill does not count`() {
        assertFalse(SpacebarTrackpad.onPill(90f, 30f, pill, slop = 8f))
        assertFalse(SpacebarTrackpad.onPill(309f, 30f, pill, slop = 8f))
    }

    @Test
    fun `lifting on the pill selects all, even after moving the cursor`() {
        assertEquals(SpacebarTrackpad.Lift.SELECT_ALL, SpacebarTrackpad.onLift(onPill = true, cursorMoved = false, cancelled = false))
        assertEquals(SpacebarTrackpad.Lift.SELECT_ALL, SpacebarTrackpad.onLift(onPill = true, cursorMoved = true, cancelled = false))
    }

    @Test
    fun `lifting off the pill without moving types a space`() =
        assertEquals(SpacebarTrackpad.Lift.SPACE, SpacebarTrackpad.onLift(onPill = false, cursorMoved = false, cancelled = false))

    @Test
    fun `lifting off the pill after moving the cursor does nothing`() =
        assertEquals(SpacebarTrackpad.Lift.NOTHING, SpacebarTrackpad.onLift(onPill = false, cursorMoved = true, cancelled = false))

    @Test
    fun `a cancelled touch never selects all`() {
        assertEquals(SpacebarTrackpad.Lift.NOTHING, SpacebarTrackpad.onLift(onPill = true, cursorMoved = false, cancelled = true))
        assertEquals(SpacebarTrackpad.Lift.NOTHING, SpacebarTrackpad.onLift(onPill = false, cursorMoved = false, cancelled = true))
    }

    @Test
    fun `the resting spacebar position is not on the pill`() =
        assertFalse(SpacebarTrackpad.onPill(200f, 380f, pill, slop = 8f))
}
