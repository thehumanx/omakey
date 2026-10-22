package dev.omakey.app.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import dev.omakey.app.R
import dev.omakey.core.feedback.FeedbackKind
import dev.omakey.core.feedback.FeedbackTuning
import dev.omakey.core.feedback.HapticSoundPreferences
import dev.omakey.core.feedback.HapticSoundSettings
import dev.omakey.core.feedback.SoundChoices

/** Decouples KeyboardViewModel/KeyboardRoot from raw Vibrator/SoundPool calls — those live here,
 * behind two intent-based methods, so the caller doesn't need to know or care how "a key was
 * pressed" turns into an actual vibration or click. */
interface KeyboardFeedback {
    fun onKeyPress()
    fun onSwipe()

    /** Swipe-left-to-delete-word specifically — a distinct "swoosh" sample rather than
     * [onSwipe]'s plain click, so a word actually vanishing reads differently than an ordinary
     * swipe (cycling suggestions, inserting a space). */
    fun onSwipeDelete()
}

/**
 * The real engine.
 *
 * **Haptics** use the best form the phone's motor supports — see [FeedbackTuning] for the three
 * forms and the measurement behind preferring primitives. A keypress is a click; a swipe a stronger
 * click; a word deleted a thud, so it reads as something going away.
 *
 * **Sound** is a bundled sample played via SoundPool, rather than AudioManager.playSoundEffect —
 * the system sound-effect API silently no-ops on many OEM builds when the device's own "Touch
 * sounds" setting is off, which made the in-app sound toggle look broken. Every sample is loaded
 * when the keyboard starts: SoundPool loads asynchronously, and a sample loaded on first use is
 * not ready for that first play, so the first click after a restart (and the first swoosh) used to
 * be silent. Clicks follow the phone's silent and vibrate modes, as other keyboards' do.
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

    private val audioManager: AudioManager by lazy { appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    /** Which haptic form this motor gets, decided once — capabilities don't change at runtime. */
    private val hapticForm: HapticForm by lazy {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) -> HapticForm.PRIMITIVE
            vibrator.hasAmplitudeControl() -> HapticForm.ONE_SHOT
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> HapticForm.PRESET
            else -> HapticForm.ONE_SHOT
        }
    }

    private val thudSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)
    }

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    /** Sample id per sound-choice id, every choice loaded up front (a few KB each) so switching the
     * click in Settings never lands on an unloaded sample either. */
    private val clickSoundIds: Map<String, Int> =
        SoundCatalog.displayNames.associate { (choice, _) -> choice to soundPool.load(appContext, SoundCatalog.resolve(choice), 1) }

    // Always this one fixed sample regardless of the user's click choice — a "swoosh" reads as
    // "something got deleted" whichever click the rest of the keyboard uses.
    private val swooshSoundId: Int = soundPool.load(appContext, R.raw.swipe_swoosh, 1)

    /** Samples that finished loading. Written on SoundPool's callback thread, read on main. */
    private val readySoundIds: MutableSet<Int> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) readySoundIds += sampleId }
    }

    override fun onKeyPress() = play(FeedbackKind.KEY_PRESS)
    override fun onSwipe() = play(FeedbackKind.SWIPE)
    override fun onSwipeDelete() = play(FeedbackKind.SWIPE_DELETE)

    private fun play(kind: FeedbackKind) {
        val settings = preferences.settings.value
        if (settings.hapticEnabled) vibrate(kind, settings.hapticStrength)
        if (settings.soundEnabled) playSound(kind, settings)
    }

    private fun vibrate(kind: FeedbackKind, strength: Float) {
        runCatching {
            val effect = when (hapticForm) {
                HapticForm.PRIMITIVE -> primitive(kind, strength)
                HapticForm.ONE_SHOT -> VibrationEffect.createOneShot(
                    FeedbackTuning.oneShotDurationMs(kind, strength),
                    FeedbackTuning.oneShotAmplitude(kind, strength),
                )
                HapticForm.PRESET -> preset(kind)
            }
            vibrator.vibrate(effect)
        }.onFailure { Log.w(TAG, "vibrate() failed", it) }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun primitive(kind: FeedbackKind, strength: Float): VibrationEffect {
        val id = if (kind == FeedbackKind.SWIPE_DELETE && thudSupported) {
            VibrationEffect.Composition.PRIMITIVE_THUD
        } else {
            VibrationEffect.Composition.PRIMITIVE_CLICK
        }
        return VibrationEffect.startComposition()
            .addPrimitive(id, FeedbackTuning.primitiveScale(kind, strength))
            .compose()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun preset(kind: FeedbackKind): VibrationEffect = VibrationEffect.createPredefined(
        if (kind == FeedbackKind.KEY_PRESS) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_HEAVY_CLICK,
    )

    private fun playSound(kind: FeedbackKind, settings: HapticSoundSettings) {
        // Silent and vibrate modes mean "no noise from this phone"; a keyboard clicking anyway is
        // the thing people turn those modes on to stop.
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val soundId = if (kind == FeedbackKind.SWIPE_DELETE) {
            swooshSoundId
        } else {
            clickSoundIds[settings.soundChoice] ?: clickSoundIds.getValue(SoundChoices.DEFAULT)
        }
        if (soundId !in readySoundIds) return
        val volume = FeedbackTuning.soundVolume(kind, settings.soundVolume)
        soundPool.play(soundId, volume, volume, 1, 0, 1f)
    }

    /** Frees the SoundPool's decoder and samples. Call when the owner (the keyboard service, the
     * Settings screen) is destroyed; this instance plays nothing afterwards. */
    fun release() = soundPool.release()

    private enum class HapticForm { PRIMITIVE, ONE_SHOT, PRESET }

    private companion object {
        const val TAG = "OmakeyFeedback"
    }
}

/** No-op used wherever a KeyboardFeedback is required but there's nothing to hook up to — e.g.
 * the Settings screen's keyboard height preview, which isn't a real typing surface. */
object NoOpKeyboardFeedback : KeyboardFeedback {
    override fun onKeyPress() = Unit
    override fun onSwipe() = Unit
    override fun onSwipeDelete() = Unit
}
