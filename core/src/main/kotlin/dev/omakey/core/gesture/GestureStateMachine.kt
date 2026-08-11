package dev.omakey.core.gesture

import kotlin.math.abs
import kotlin.math.hypot

/**
 * A single raw touch sample fed into the state machine. Framework-agnostic —
 * callers translate Android MotionEvents into this before calling [GestureStateMachine.onTouch].
 */
data class TouchSample(val x: Float, val y: Float, val timestampMs: Long, val action: TouchAction)

enum class TouchAction { DOWN, MOVE, UP, CANCEL }

enum class SwipeDirection { LEFT, RIGHT, UP, DOWN }

sealed interface GestureEvent {
    data class KeyTap(val keyCode: Int, val x: Float, val y: Float) : GestureEvent
    data class KeyLongPress(val keyCode: Int, val x: Float, val y: Float) : GestureEvent
    data class Swipe(val direction: SwipeDirection, val velocityPxPerMs: Float) : GestureEvent
    data object GestureCancelled : GestureEvent
}

/** Tunable thresholds. Defaults per plan; scale [minSwipeDistancePx] to keyboard geometry per device. */
data class GestureThresholds(
    val touchSlopPx: Float,
    val minSwipeDistancePxHorizontal: Float,
    val minSwipeDistancePxVertical: Float,
    val axisLockRatio: Float = 2.0f,
    val longPressThresholdMs: Long = 400L,
    val maxTapDurationMs: Long = 500L,
)

/**
 * Resolves whichever key is hit-tested at a given point. Down-position is used to resolve tap
 * identity (Android convention: forgiving of small finger drift between down and up).
 */
fun interface KeyHitTester {
    fun keyCodeAt(x: Float, y: Float): Int
}

private enum class State { IDLE, DOWN, TAP_CANDIDATE, SWIPE_CANDIDATE, SWIPE_COMMITTED, LONG_PRESS_POPUP_ACTIVE }

/**
 * Pure Kotlin gesture disambiguation engine. One instance owns the touch lifecycle for the whole
 * keyboard surface (not per-key) since Fleksy-style gestures are edge-to-edge swipes independent
 * of which key sits under the finger at gesture start.
 *
 * Swipe commits the instant its threshold is crossed (not on ACTION_UP) for a snappier feel;
 * further movement before UP is ignored for commit purposes.
 */
class GestureStateMachine(
    private val thresholds: GestureThresholds,
    private val keyHitTester: KeyHitTester,
) {
    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var downKeyCode = 0
    private var lockedAxisHorizontal: Boolean? = null
    private var longPressFired = false

    fun onTouch(sample: TouchSample): GestureEvent? {
        return when (sample.action) {
            TouchAction.DOWN -> handleDown(sample)
            TouchAction.MOVE -> handleMove(sample)
            TouchAction.UP -> handleUp(sample)
            TouchAction.CANCEL -> handleCancel()
        }
    }

    /** Call from the host when the long-press timer (started at DOWN) elapses with no cancellation. */
    fun onLongPressTimerFired(atX: Float, atY: Float): GestureEvent? {
        if (state != State.DOWN && state != State.TAP_CANDIDATE) return null
        longPressFired = true
        state = State.LONG_PRESS_POPUP_ACTIVE
        return GestureEvent.KeyLongPress(downKeyCode, atX, atY)
    }

    private fun reset() {
        state = State.IDLE
        lockedAxisHorizontal = null
        longPressFired = false
    }

    private fun handleDown(sample: TouchSample): GestureEvent? {
        downX = sample.x
        downY = sample.y
        downTimeMs = sample.timestampMs
        downKeyCode = keyHitTester.keyCodeAt(sample.x, sample.y)
        lockedAxisHorizontal = null
        longPressFired = false
        state = State.DOWN
        return null
    }

    private fun handleMove(sample: TouchSample): GestureEvent? {
        if (state == State.IDLE) return null
        if (state == State.LONG_PRESS_POPUP_ACTIVE) return null
        if (state == State.SWIPE_COMMITTED) return null

        val dx = sample.x - downX
        val dy = sample.y - downY
        val dist = hypot(dx, dy)

        if (dist < thresholds.touchSlopPx) {
            return null
        }

        // Past slop: long-press candidacy is over.
        state = State.TAP_CANDIDATE

        val horizontalDominant = abs(dx) > thresholds.axisLockRatio * abs(dy)
        val verticalDominant = abs(dy) > thresholds.axisLockRatio * abs(dx)
        val minDist = if (horizontalDominant) {
            thresholds.minSwipeDistancePxHorizontal
        } else {
            thresholds.minSwipeDistancePxVertical
        }

        if ((horizontalDominant || verticalDominant) && dist >= minDist) {
            val direction = when {
                horizontalDominant && dx < 0 -> SwipeDirection.LEFT
                horizontalDominant && dx > 0 -> SwipeDirection.RIGHT
                verticalDominant && dy < 0 -> SwipeDirection.UP
                else -> SwipeDirection.DOWN
            }
            state = State.SWIPE_COMMITTED
            val elapsed = (sample.timestampMs - downTimeMs).coerceAtLeast(1L)
            val velocity = dist / elapsed
            return GestureEvent.Swipe(direction, velocity)
        }

        state = State.SWIPE_CANDIDATE
        return null
    }

    private fun handleUp(sample: TouchSample): GestureEvent? {
        val result = when (state) {
            State.DOWN, State.TAP_CANDIDATE -> {
                val elapsed = sample.timestampMs - downTimeMs
                if (elapsed <= thresholds.maxTapDurationMs && !longPressFired) {
                    GestureEvent.KeyTap(downKeyCode, downX, downY)
                } else {
                    null
                }
            }
            State.SWIPE_CANDIDATE -> null // released before crossing swipe threshold; not a valid tap either
            State.SWIPE_COMMITTED -> null // already emitted on threshold-cross
            State.LONG_PRESS_POPUP_ACTIVE -> null // host resolves popup selection itself from move history
            State.IDLE -> null
        }
        reset()
        return result
    }

    private fun handleCancel(): GestureEvent {
        reset()
        return GestureEvent.GestureCancelled
    }
}
