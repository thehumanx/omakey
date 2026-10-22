package dev.omakey.core.gesture

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.StateFlow

data class GestureSettings(
    /** Multiplier applied to the base swipe-distance thresholds. Below 1.0 = shorter swipes
     * register (more sensitive, easier to trigger by accident); above 1.0 = longer swipes
     * required (less sensitive, fewer accidental triggers). */
    val swipeSensitivity: Float = DEFAULT_SENSITIVITY,
    val showKeyPopup: Boolean = true,
    /** Off by default — swipe-right already inserts a space unconditionally today, which some
     * users want as an explicit opt-in gesture rather than always-on (e.g. it can compete with a
     * word-cycling swipe started slightly diagonally). */
    val swipeRightForSpace: Boolean = false,
    /** Off by default: a swipe left takes the last word together with any space after it, so
     * "hello my name is " and "hello my name is" both become "hello my name ". On: a space after
     * the word is a swipe of its own, the word the next one (the behaviour before 5.3). */
    val swipeLeftSpaceIsOwnStep: Boolean = false,
) {
    companion object {
        const val DEFAULT_SENSITIVITY = 1.0f
        const val MIN_SENSITIVITY = 0.5f
        const val MAX_SENSITIVITY = 1.8f
    }
}

/** Persists gesture tuning + the key-popup toggle. Same SharedPreferences + cross-instance-sync
 * pattern as the other *Preferences classes (see ThemeRepository for why the change listener is
 * necessary, not optional). */
class GesturePreferences(context: Context) {
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val settings: StateFlow<GestureSettings> = store.settings

    fun setSwipeSensitivity(value: Float) {
        update {
            it.copy(swipeSensitivity = value.coerceIn(GestureSettings.MIN_SENSITIVITY, GestureSettings.MAX_SENSITIVITY))
        }
    }

    fun setShowKeyPopup(show: Boolean) = update { it.copy(showKeyPopup = show) }

    fun setSwipeRightForSpace(enabled: Boolean) = update { it.copy(swipeRightForSpace = enabled) }

    fun setSwipeLeftSpaceIsOwnStep(enabled: Boolean) = update { it.copy(swipeLeftSpaceIsOwnStep = enabled) }

    private fun update(transform: (GestureSettings) -> GestureSettings) {
        val next = transform(store.settings.value)
        store.edit {
            putFloat(KEY_SENSITIVITY, next.swipeSensitivity)
            putBoolean(KEY_SHOW_POPUP, next.showKeyPopup)
            putBoolean(KEY_SWIPE_RIGHT_FOR_SPACE, next.swipeRightForSpace)
            putBoolean(KEY_SWIPE_LEFT_SPACE_OWN_STEP, next.swipeLeftSpaceIsOwnStep)
        }
    }

    fun close() = store.close()

    private fun load(prefs: SharedPreferences) = GestureSettings(
        swipeSensitivity = prefs.getFloat(KEY_SENSITIVITY, GestureSettings.DEFAULT_SENSITIVITY),
        showKeyPopup = prefs.getBoolean(KEY_SHOW_POPUP, true),
        swipeRightForSpace = prefs.getBoolean(KEY_SWIPE_RIGHT_FOR_SPACE, false),
        swipeLeftSpaceIsOwnStep = prefs.getBoolean(KEY_SWIPE_LEFT_SPACE_OWN_STEP, false),
    )

    private companion object {
        const val PREFS_NAME = "omakey_gesture_prefs"
        const val KEY_SENSITIVITY = "swipe_sensitivity"
        const val KEY_SHOW_POPUP = "show_key_popup"
        const val KEY_SWIPE_RIGHT_FOR_SPACE = "swipe_right_for_space"
        const val KEY_SWIPE_LEFT_SPACE_OWN_STEP = "swipe_left_space_is_own_step"
    }
}
