package dev.omakey.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GestureStateMachineTest {

    private val thresholds = GestureThresholds(
        touchSlopPx = 10f,
        minSwipeDistancePxHorizontal = 100f,
        minSwipeDistancePxVertical = 80f,
    )
    private lateinit var machine: GestureStateMachine

    @Before
    fun setUp() {
        machine = GestureStateMachine(thresholds) { _, _ -> 42 }
    }

    private fun down(x: Float, y: Float, t: Long) =
        machine.onTouch(TouchSample(x, y, t, TouchAction.DOWN))

    private fun move(x: Float, y: Float, t: Long) =
        machine.onTouch(TouchSample(x, y, t, TouchAction.MOVE))

    private fun up(x: Float, y: Float, t: Long) =
        machine.onTouch(TouchSample(x, y, t, TouchAction.UP))

    @Test
    fun `isPendingTap is false before any touch and true right after down`() {
        assertFalse(machine.isPendingTap())
        down(50f, 50f, 0)
        assertTrue(machine.isPendingTap())
    }

    @Test
    fun `isPendingTap stays true through jitter under slop, letting a rollover finalize it as a tap`() {
        down(50f, 50f, 0)
        move(52f, 51f, 20) // under slop, still ambiguous
        assertTrue(machine.isPendingTap())
        // A second finger landing elsewhere (KeyboardRoot's multi-touch rollover) finalizes this
        // still-pending touch as a tap by feeding it a synthetic UP, exactly like this.
        val event = up(52f, 51f, 25)
        assertTrue(event is GestureEvent.KeyTap)
        assertFalse(machine.isPendingTap())
    }

    @Test
    fun `isPendingTap turns false once a swipe commits`() {
        down(200f, 50f, 0)
        val committed = move(90f, 51f, 30)
        assertTrue(committed is GestureEvent.Swipe)
        assertFalse(machine.isPendingTap())
    }

    @Test
    fun `isPendingTap turns false once a long-press fires`() {
        down(50f, 50f, 0)
        val event = machine.onLongPressTimerFired(50f, 50f)
        assertTrue(event is GestureEvent.KeyLongPress)
        assertFalse(machine.isPendingTap())
    }

    @Test
    fun `clean tap with no movement emits KeyTap`() {
        assertNull(down(50f, 50f, 0))
        val event = up(50f, 50f, 100)
        assertTrue(event is GestureEvent.KeyTap)
        event as GestureEvent.KeyTap
        assertEquals(42, event.keyCode)
    }

    @Test
    fun `jitter under slop still resolves as tap`() {
        assertNull(down(50f, 50f, 0))
        assertNull(move(52f, 51f, 20)) // dist ~2.2px, under 10px slop
        val event = up(52f, 51f, 40)
        assertTrue(event is GestureEvent.KeyTap)
    }

    @Test
    fun `clean horizontal swipe left emits Swipe LEFT on threshold cross, not on up`() {
        assertNull(down(200f, 50f, 0))
        assertNull(move(150f, 51f, 10)) // dist 50, under 100 threshold, under slop check too small diff
        val committed = move(90f, 51f, 30) // dist 110 >= 100, axis-locked horizontal
        assertTrue(committed is GestureEvent.Swipe)
        committed as GestureEvent.Swipe
        assertEquals(SwipeDirection.LEFT, committed.direction)
        // further movement before UP must not re-emit
        assertNull(move(20f, 51f, 50))
        assertNull(up(20f, 51f, 60))
    }

    @Test
    fun `swipe event carries the key code the touch started on`() {
        // 42 comes from the default hitTester in setUp() — this specifically checks the field is
        // populated from the DOWN position, not left at some default/zero value, which is what
        // lets callers (KeyboardRoot's handleGestureEvent) tell a swipe-left starting on the
        // spacebar apart from one starting anywhere else.
        assertNull(down(200f, 50f, 0))
        val committed = move(90f, 51f, 30)
        assertTrue(committed is GestureEvent.Swipe)
        committed as GestureEvent.Swipe
        assertEquals(42, committed.downKeyCode)
    }

    @Test
    fun `clean horizontal swipe right emits Swipe RIGHT`() {
        assertNull(down(50f, 50f, 0))
        val committed = move(160f, 52f, 30) // dist ~110, dx dominant positive
        assertTrue(committed is GestureEvent.Swipe)
        committed as GestureEvent.Swipe
        assertEquals(SwipeDirection.RIGHT, committed.direction)
    }

    @Test
    fun `clean vertical swipe up emits Swipe UP`() {
        assertNull(down(50f, 200f, 0))
        val committed = move(51f, 110f, 30) // dy -90, dominant vertical, exceeds 80 threshold
        assertTrue(committed is GestureEvent.Swipe)
        committed as GestureEvent.Swipe
        assertEquals(SwipeDirection.UP, committed.direction)
    }

    @Test
    fun `clean vertical swipe down emits Swipe DOWN`() {
        assertNull(down(50f, 50f, 0))
        val committed = move(51f, 140f, 30) // dy +90
        assertTrue(committed is GestureEvent.Swipe)
        committed as GestureEvent.Swipe
        assertEquals(SwipeDirection.DOWN, committed.direction)
    }

    @Test
    fun `diagonal movement without axis dominance falls back to a tap on release`() {
        assertNull(down(50f, 50f, 0))
        // dx=90, dy=80: neither exceeds 2x the other -> axis-lock rejects a swipe
        assertNull(move(140f, 130f, 30))
        // A botched/ambiguous drag that never crosses the swipe threshold must still resolve as a
        // tap at the down position on release, not silently drop the input — this is what keeps
        // fast typing (which naturally has some finger drift) and marginal swipe attempts from
        // registering nothing at all.
        val event = up(140f, 130f, 40)
        assertTrue(event is GestureEvent.KeyTap)
        event as GestureEvent.KeyTap
        assertEquals(42, event.keyCode)
    }

    @Test
    fun `swipe attempt that falls short of the distance threshold resolves as a tap`() {
        assertNull(down(200f, 50f, 0))
        // Horizontal, axis-locked, but only 60px of 100px required — same axis as a real swipe,
        // just short of it. Must still resolve as a tap rather than nothing.
        assertNull(move(140f, 51f, 30))
        val event = up(140f, 51f, 40)
        assertTrue(event is GestureEvent.KeyTap)
    }

    @Test
    fun `direction reversal mid-gesture resolves using the sample that crosses threshold`() {
        assertNull(down(200f, 50f, 0))
        assertNull(move(180f, 50f, 10)) // moving left, dist 20, under threshold
        // reverse direction, now moving right past threshold
        val committed = move(320f, 50f, 30) // dist from down = 120, dx positive
        assertTrue(committed is GestureEvent.Swipe)
        committed as GestureEvent.Swipe
        assertEquals(SwipeDirection.RIGHT, committed.direction)
    }

    @Test
    fun `ACTION_CANCEL mid-gesture emits GestureCancelled and resets state`() {
        assertNull(down(50f, 50f, 0))
        assertNull(move(70f, 51f, 10))
        val cancelled = machine.onTouch(TouchSample(0f, 0f, 20, TouchAction.CANCEL))
        assertEquals(GestureEvent.GestureCancelled, cancelled)

        // state machine must be back to IDLE and ready for a fresh gesture
        assertNull(down(10f, 10f, 100))
        val tapAfterReset = up(10f, 10f, 150)
        assertTrue(tapAfterReset is GestureEvent.KeyTap)
    }

    @Test
    fun `tap held past max tap duration without movement does not emit KeyTap`() {
        assertNull(down(50f, 50f, 0))
        val event = up(50f, 50f, 600) // exceeds 500ms maxTapDurationMs, no long-press signal fired by host
        assertNull(event)
    }

    @Test
    fun `long press timer fires and subsequent up does not emit a second tap`() {
        assertNull(down(50f, 50f, 0))
        val longPress = machine.onLongPressTimerFired(50f, 50f)
        assertTrue(longPress is GestureEvent.KeyLongPress)
        val event = up(50f, 50f, 450)
        assertNull(event) // already resolved via long-press path
    }
}
