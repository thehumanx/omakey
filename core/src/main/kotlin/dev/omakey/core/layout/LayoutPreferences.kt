package dev.omakey.core.layout

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LayoutSettings(
    val keyboardHeightDp: Int = DEFAULT_HEIGHT_DP,
    val showKeyBackgrounds: Boolean = false,
    val showMiddleRowStripe: Boolean = true,
    /** Extra blank space left below the keyboard's own content, raising it off the bottom edge
     * for easier one-handed thumb reach — set via the drag-to-position "placement mode" in
     * Settings, not a plain slider (the right amount depends on the device's screen size and
     * hand, which a number alone doesn't convey). Clamped by the placement UI itself to never
     * push the keyboard's top edge above screen center — this field just stores whatever that UI
     * already validated. */
    val bottomOffsetDp: Int = 0,
) {
    companion object {
        const val DEFAULT_HEIGHT_DP = 260
        const val MIN_HEIGHT_DP = 180
        const val MAX_HEIGHT_DP = 360
    }
}

/** Persists user-adjustable keyboard layout settings (height, key box style, home-row stripe)
 * via SharedPreferences — same lightweight pattern as [dev.omakey.core.theme.ThemeRepository],
 * reactive via StateFlow so an already-open keyboard picks up changes made from Settings. */
class LayoutPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<LayoutSettings> = _settings

    // The Settings Activity and the IME service each construct their own LayoutPreferences
    // instance — both back onto the same SharedPreferences file, but a write from one instance
    // would otherwise never reach the other's in-memory StateFlow, since they're separate objects.
    // Without this, a change made in Settings only became visible after the IME was torn down and
    // recreated (e.g. switching keyboards away and back), not live in an already-open keyboard.
    // The listener reference must be held here — SharedPreferences only keeps a weak reference to
    // registered listeners, so an inline lambda would get GC'd almost immediately.
    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settings.value = load()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun setKeyboardHeightDp(heightDp: Int) {
        update { it.copy(keyboardHeightDp = heightDp.coerceIn(LayoutSettings.MIN_HEIGHT_DP, LayoutSettings.MAX_HEIGHT_DP)) }
    }

    fun setShowKeyBackgrounds(show: Boolean) = update { it.copy(showKeyBackgrounds = show) }
    fun setShowMiddleRowStripe(show: Boolean) = update { it.copy(showMiddleRowStripe = show) }

    /** [offsetDp] is expected to already be clamped by the caller (the placement-mode drag UI,
     * which knows the live screen height) — only a non-negative floor is enforced here. */
    fun setBottomOffsetDp(offsetDp: Int) = update { it.copy(bottomOffsetDp = offsetDp.coerceAtLeast(0)) }

    private fun update(transform: (LayoutSettings) -> LayoutSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putInt(KEY_HEIGHT, next.keyboardHeightDp)
            .putBoolean(KEY_KEY_BACKGROUNDS, next.showKeyBackgrounds)
            .putBoolean(KEY_MIDDLE_STRIPE, next.showMiddleRowStripe)
            .putInt(KEY_BOTTOM_OFFSET, next.bottomOffsetDp)
            .apply()
        _settings.value = next
    }

    private fun load(): LayoutSettings = LayoutSettings(
        keyboardHeightDp = prefs.getInt(KEY_HEIGHT, LayoutSettings.DEFAULT_HEIGHT_DP),
        showKeyBackgrounds = prefs.getBoolean(KEY_KEY_BACKGROUNDS, false),
        showMiddleRowStripe = prefs.getBoolean(KEY_MIDDLE_STRIPE, true),
        bottomOffsetDp = prefs.getInt(KEY_BOTTOM_OFFSET, 0),
    )

    private companion object {
        const val PREFS_NAME = "omakey_layout_prefs"
        const val KEY_HEIGHT = "keyboard_height_dp"
        const val KEY_KEY_BACKGROUNDS = "show_key_backgrounds"
        const val KEY_MIDDLE_STRIPE = "show_middle_row_stripe"
        const val KEY_BOTTOM_OFFSET = "keyboard_bottom_offset_dp"
    }
}
