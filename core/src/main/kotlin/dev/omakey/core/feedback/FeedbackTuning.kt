package dev.omakey.core.feedback

/** What the feedback is for. Each feels and sounds a little different, so a swipe that registered
 * or a word that vanished can be told from an ordinary keystroke without looking. */
enum class FeedbackKind { KEY_PRESS, SWIPE, SWIPE_DELETE }

/**
 * How strong each kind of feedback is, from the user's two sliders. Pure numbers, kept apart from
 * the Android calls that play them so the mapping can be tested.
 *
 * The haptic side has three forms, best first, chosen by what the phone's motor supports:
 *  - a **primitive** (Android 11+): a short, shaped click the motor driver renders precisely, at a
 *    0..1 [primitiveScale]. What Gboard and the system keyboard use; the only form whose strength
 *    reliably survives OEM haptic layers. Measured on the developer's phone (2026-10-22): a plain
 *    32 ms buzz at 50% amplitude was replaced by the system with its preset click at STRONG —
 *    the strength slider did nothing — while another app's primitives played at the scale asked.
 *  - a **one-shot** buzz ([oneShotDurationMs] at [oneShotAmplitude]) on motors with amplitude
 *    control but no primitives.
 *  - the phone's **preset** click, where neither exists; strength can't apply there at all.
 */
object FeedbackTuning {

    /** Primitive intensity. Never below [MIN_PRIMITIVE_SCALE]: the slider's low end should be
     * "lighter", not "gone". Swipes sit a step above a keypress so they read as a separate event. */
    fun primitiveScale(kind: FeedbackKind, strength: Float): Float {
        val s = strength.coerceIn(0f, 1f)
        val base = MIN_PRIMITIVE_SCALE + (1f - MIN_PRIMITIVE_SCALE) * s
        return when (kind) {
            FeedbackKind.KEY_PRESS -> base
            FeedbackKind.SWIPE, FeedbackKind.SWIPE_DELETE -> (base * SWIPE_BOOST).coerceAtMost(1f)
        }
    }

    /**
     * One-shot buzz length. Kept above ~20 ms: below that several OEM engines swap in their own
     * fixed tick and ignore amplitude (seen in `dumpsys vibrator_manager`).
     */
    fun oneShotDurationMs(kind: FeedbackKind, strength: Float): Long {
        val s = strength.coerceIn(0f, 1f)
        return when (kind) {
            FeedbackKind.KEY_PRESS -> 20 + (s * 25).toLong()
            FeedbackKind.SWIPE, FeedbackKind.SWIPE_DELETE -> 35 + (s * 30).toLong()
        }
    }

    /** One-shot amplitude, 1..255, floored at a quarter so the low end is still felt. */
    fun oneShotAmplitude(kind: FeedbackKind, strength: Float): Int {
        val s = strength.coerceIn(0f, 1f)
        val fraction = when (kind) {
            FeedbackKind.KEY_PRESS -> s
            FeedbackKind.SWIPE, FeedbackKind.SWIPE_DELETE -> s * SWIPE_BOOST
        }
        return (fraction.coerceIn(MIN_AMPLITUDE_FRACTION, 1f) * 255).toInt().coerceIn(1, 255)
    }

    /** SoundPool volume, 0..1. A swipe click is louder than a keypress; the delete swoosh tracks
     * the keypress level, so it follows the volume slider the same way the taps do. */
    fun soundVolume(kind: FeedbackKind, volume: Float): Float {
        val v = volume.coerceIn(0f, 1f)
        return when (kind) {
            FeedbackKind.KEY_PRESS, FeedbackKind.SWIPE_DELETE -> KEY_PRESS_VOLUME * v
            FeedbackKind.SWIPE -> SWIPE_VOLUME * v
        }
    }

    const val MIN_PRIMITIVE_SCALE = 0.25f
    private const val SWIPE_BOOST = 1.25f
    private const val MIN_AMPLITUDE_FRACTION = 0.25f
    private const val KEY_PRESS_VOLUME = 0.5f
    private const val SWIPE_VOLUME = 0.8f
}
