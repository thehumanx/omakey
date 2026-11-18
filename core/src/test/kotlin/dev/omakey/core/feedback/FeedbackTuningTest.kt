package dev.omakey.core.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTuningTest {

    private val strengths = listOf(
        HapticSoundSettings.MIN_HAPTIC_STRENGTH, 0.25f, HapticSoundSettings.DEFAULT_HAPTIC_STRENGTH, 0.75f,
        HapticSoundSettings.MAX_HAPTIC_STRENGTH,
    )

    @Test
    fun `the strength slider changes the primitive, low to high`() {
        for (kind in FeedbackKind.entries) {
            val scales = strengths.map { FeedbackTuning.primitiveScale(kind, it) }
            assertEquals("$kind should rise with strength", scales.sorted(), scales)
            assertTrue("$kind never vanishes at the low end", scales.first() >= FeedbackTuning.MIN_PRIMITIVE_SCALE)
            assertTrue("$kind stays within 0..1", scales.all { it in 0f..1f })
        }
        val keyLow = FeedbackTuning.primitiveScale(FeedbackKind.KEY_PRESS, HapticSoundSettings.MIN_HAPTIC_STRENGTH)
        val keyHigh = FeedbackTuning.primitiveScale(FeedbackKind.KEY_PRESS, HapticSoundSettings.MAX_HAPTIC_STRENGTH)
        assertTrue("a felt difference across the slider: $keyLow..$keyHigh", keyHigh - keyLow > 0.5f)
    }

    @Test
    fun `a swipe is felt more than a keypress, until both are at full strength`() {
        for (s in strengths) {
            val key = FeedbackTuning.primitiveScale(FeedbackKind.KEY_PRESS, s)
            val swipe = FeedbackTuning.primitiveScale(FeedbackKind.SWIPE, s)
            assertTrue(swipe >= key)
            if (key < 0.8f) assertTrue("strength $s: swipe $swipe vs key $key", swipe > key)
        }
    }

    @Test
    fun `deleting a word feels the same as typing a key`() {
        // Reported on a Galaxy S25 Ultra: swipe-to-delete buzzed noticeably harder than typing,
        // and held, it repeats every 150 ms. The swoosh sound already tells it apart.
        for (s in strengths) {
            assertEquals(
                FeedbackTuning.primitiveScale(FeedbackKind.KEY_PRESS, s),
                FeedbackTuning.primitiveScale(FeedbackKind.SWIPE_DELETE, s),
                0.0001f,
            )
            assertEquals(
                FeedbackTuning.oneShotDurationMs(FeedbackKind.KEY_PRESS, s),
                FeedbackTuning.oneShotDurationMs(FeedbackKind.SWIPE_DELETE, s),
            )
            assertEquals(
                FeedbackTuning.oneShotAmplitude(FeedbackKind.KEY_PRESS, s),
                FeedbackTuning.oneShotAmplitude(FeedbackKind.SWIPE_DELETE, s),
            )
        }
    }

    @Test
    fun `one-shot fallback stays above the OEM tick floor and inside the amplitude range`() {
        for (kind in FeedbackKind.entries) for (s in strengths) {
            assertTrue(FeedbackTuning.oneShotDurationMs(kind, s) >= 20)
            assertTrue(FeedbackTuning.oneShotAmplitude(kind, s) in 63..255)
        }
    }

    @Test
    fun `sound follows the volume slider and swipes are louder than taps`() {
        assertEquals(0.5f, FeedbackTuning.soundVolume(FeedbackKind.KEY_PRESS, 1f), 0.001f)
        assertEquals(0.8f, FeedbackTuning.soundVolume(FeedbackKind.SWIPE, 1f), 0.001f)
        assertEquals(0.05f, FeedbackTuning.soundVolume(FeedbackKind.KEY_PRESS, 0.1f), 0.001f)
        assertEquals(
            FeedbackTuning.soundVolume(FeedbackKind.KEY_PRESS, 0.6f),
            FeedbackTuning.soundVolume(FeedbackKind.SWIPE_DELETE, 0.6f),
            0.001f,
        )
    }
}
