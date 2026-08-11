package dev.omakey.app.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dev.omakey.app.R
import dev.omakey.core.feedback.HapticSoundPreferences

/** Decouples KeyboardViewModel/KeyboardRoot from raw Vibrator/SoundPool calls — those live here,
 * behind two intent-based methods, so the caller doesn't need to know or care how "a key was
 * pressed" turns into an actual vibration or click. */
interface KeyboardFeedback {
    fun onKeyPress()
    fun onSwipe()
}

/**
 * Real implementation using VibrationEffect (amplitude-controlled, not just on/off — the whole
 * point of a "strength" setting is a vibration that actually feels different, not the same buzz
 * clipped to a different length) and a bundled click sound played via SoundPool, rather than
 * AudioManager.playSoundEffect — the system sound-effect API silently no-ops on many OEM builds
 * when the device's own "Touch sounds" system setting is off, which is outside this app's control
 * and was making the in-app sound toggle look broken even though it was firing correctly.
 * SoundPool bypasses that: our own click plays on our own terms.
 */
class VibratorKeyboardFeedback(context: Context, private val preferences: HapticSoundPreferences) : KeyboardFeedback {
    private val appContext = context.applicationContext

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private val soundPool: SoundPool by lazy {
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    }
    private var clickSoundId = 0
    private var clickSoundLoaded = false

    private fun ensureSoundLoaded() {
        if (clickSoundId != 0) return
        soundPool.setOnLoadCompleteListener { _, _, status -> clickSoundLoaded = status == 0 }
        clickSoundId = soundPool.load(appContext, R.raw.key_click, 1)
    }

    override fun onKeyPress() {
        val settings = preferences.settings.value
        if (settings.hapticEnabled) {
            // Below ~20ms, several OEM haptic engines silently substitute their own fixed-length
            // "TICK" primitive and largely ignore the requested amplitude — confirmed via
            // dumpsys vibrator_manager, which showed our short requests being delivered but
            // collapsed onto a canned effect regardless of strength. Staying above that floor
            // gets a real, adjustable buzz instead of the same barely-there tick every time.
            vibrate(durationMs = 20 + (settings.hapticStrength * 25).toLong(), amplitudeFraction = settings.hapticStrength)
        }
        if (settings.soundEnabled) playClick(volume = 0.5f)
    }

    override fun onSwipe() {
        val settings = preferences.settings.value
        if (settings.hapticEnabled) {
            // Noticeably longer/punchier than a tap — confirms a swipe actually registered,
            // distinct from an ordinary keystroke.
            vibrate(
                durationMs = 35 + (settings.hapticStrength * 30).toLong(),
                amplitudeFraction = (settings.hapticStrength * 1.2f).coerceAtMost(1f),
            )
        }
        if (settings.soundEnabled) playClick(volume = 0.8f)
    }

    private fun playClick(volume: Float) {
        ensureSoundLoaded()
        if (clickSoundLoaded) {
            soundPool.play(clickSoundId, volume, volume, 1, 0, 1f)
        }
    }

    private fun vibrate(durationMs: Long, amplitudeFraction: Float) {
        // Floor above 0 regardless of strength setting — a near-zero amplitude at low "strength"
        // is functionally the same as no feedback at all, which defeats a settings slider whose
        // whole point is "still felt, just lighter."
        val amplitude = (amplitudeFraction.coerceIn(0.25f, 1f) * 255).toInt().coerceIn(1, 255)
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        }.onFailure { Log.w(TAG, "vibrate() failed", it) }
    }

    private companion object {
        const val TAG = "OmakeyFeedback"
    }
}

/** No-op used wherever a KeyboardFeedback is required but there's nothing to hook up to — e.g.
 * the Settings screen's keyboard height preview, which isn't a real typing surface. */
object NoOpKeyboardFeedback : KeyboardFeedback {
    override fun onKeyPress() = Unit
    override fun onSwipe() = Unit
}
