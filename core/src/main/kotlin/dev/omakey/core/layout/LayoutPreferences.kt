package dev.omakey.core.layout

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
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
    /** True (the shipped default, matching omakey's original look): letter keycaps always show
     * uppercase glyphs regardless of shift state — only [KeyboardUiState.shiftOn]/`capsLockOn`
     * change what's actually *typed*. False switches to the conventional mobile-keyboard
     * behavior instead: keycaps show lowercase normally and switch to uppercase only while shift
     * is engaged (one-shot or locked), matching what almost every other keyboard does and what
     * users coming from one expect. */
    val alwaysShowUppercaseLetters: Boolean = true,
    /** The brief enlarged-character bubble that floats above a tapped character key (distinct
     * from the long-press accent/punctuation popup, `GestureSettings.showKeyPopup`, which is a
     * held-key thing). On by default; off makes ordinary taps give no extra visual feedback
     * beyond the key's own press state. */
    val showTapPreview: Boolean = true,
    /** Blank gutters down the left and right of the keyboard, so the outermost keys (Q/P, shift,
     * backspace) don't sit flush against the screen edge. On a bezel-less phone with curved glass
     * that edge is where a thumb slides off, or where the display itself starts to distort — the
     * same reason Fleksy offered this. Off by default: it costs key width, which on a narrow
     * device is the more common complaint. */
    val edgePadding: Boolean = false,
    /** Docked, floating, or one-handed to either side. See [KeyboardPlacement]. */
    val placement: KeyboardPlacement = KeyboardPlacement.DOCKED,
    /** Size and position of the *floating* keyboard, held separately from [keyboardHeightDp] and
     * full width. That separation is the point: resizing while floating must not silently resize
     * the docked keyboard too, and vice versa — "resize in the current mode" is the whole feature.
     * [floatingYDp] is measured up from the window's bottom edge, see
     * [KeyboardPlacementGeometry.clampFloatingY]. */
    val floatingWidthDp: Int = DEFAULT_FLOATING_WIDTH_DP,
    val floatingHeightDp: Int = DEFAULT_FLOATING_HEIGHT_DP,
    /** -1 means "not positioned yet" — the first switch to floating centres it rather than
     * dropping it in a corner. Any real position, including 0, is honoured. */
    val floatingXDp: Int = UNSET_POSITION,
    val floatingYDp: Int = KeyboardPlacementGeometry.DEFAULT_FLOATING_Y_DP,
    /** Width of the one-handed keyboard; the rest of the row is the gutter holding its side
     * buttons. Independent of the floating size for the same reason as above. */
    val oneHandedWidthDp: Int = DEFAULT_ONE_HANDED_WIDTH_DP,
    /** Where the emoji and language buttons sit. Default: the language button at the right end of
     * the suggestion bar and the emoji key in the bottom row, next to the spacebar. Swapped puts the
     * language key in the bottom row and the emoji button in the suggestion bar. Only matters with
     * two or more languages enabled — with one, there is no language button at all. */
    val swapEmojiAndLanguage: Boolean = false,
) {
    companion object {
        const val DEFAULT_HEIGHT_DP = 260
        const val MIN_HEIGHT_DP = 180
        const val MAX_HEIGHT_DP = 360

        /** Width of each gutter when [edgePadding] is on. A fixed dp rather than a percentage:
         * this is compensating for a physical bezel/curve, which doesn't scale with screen width. */
        const val EDGE_PADDING_DP = 10

        const val DEFAULT_FLOATING_WIDTH_DP = 320
        const val DEFAULT_FLOATING_HEIGHT_DP = 240
        const val DEFAULT_ONE_HANDED_WIDTH_DP = 300
        const val UNSET_POSITION = -1
    }
}

/** Persists user-adjustable keyboard layout settings (height, key box style, home-row stripe)
 * via SharedPreferences — same lightweight pattern as [dev.omakey.core.theme.ThemeRepository],
 * reactive via StateFlow so an already-open keyboard picks up changes made from Settings. */
class LayoutPreferences(context: Context) {
    // Cross-instance sync (Settings and the IME each build their own instance) and the weak-
    // reference trap that makes holding the listener mandatory are both handled by PreferenceStore
    // now — see its doc, which is where that reasoning was worth keeping in one place.
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val settings: StateFlow<LayoutSettings> = store.settings

    fun setKeyboardHeightDp(heightDp: Int) {
        update { it.copy(keyboardHeightDp = heightDp.coerceIn(LayoutSettings.MIN_HEIGHT_DP, LayoutSettings.MAX_HEIGHT_DP)) }
    }

    fun setShowKeyBackgrounds(show: Boolean) = update { it.copy(showKeyBackgrounds = show) }
    fun setShowMiddleRowStripe(show: Boolean) = update { it.copy(showMiddleRowStripe = show) }
    fun setAlwaysShowUppercaseLetters(show: Boolean) = update { it.copy(alwaysShowUppercaseLetters = show) }
    fun setShowTapPreview(show: Boolean) = update { it.copy(showTapPreview = show) }
    fun setEdgePadding(enabled: Boolean) = update { it.copy(edgePadding = enabled) }
    fun setSwapEmojiAndLanguage(swap: Boolean) = update { it.copy(swapEmojiAndLanguage = swap) }

    fun setPlacement(placement: KeyboardPlacement) = update { it.copy(placement = placement) }

    /** [xDp]/[yDp] are expected to already be clamped by the caller, which is the only party that
     * knows the live window size — same contract as [setBottomOffsetDp]. */
    fun setFloatingBounds(widthDp: Int, heightDp: Int, xDp: Int, yDp: Int) = update {
        it.copy(floatingWidthDp = widthDp, floatingHeightDp = heightDp, floatingXDp = xDp, floatingYDp = yDp)
    }

    fun setOneHandedWidthDp(widthDp: Int) = update { it.copy(oneHandedWidthDp = widthDp) }

    /** [offsetDp] is expected to already be clamped by the caller (the placement-mode drag UI,
     * which knows the live screen height) — only a non-negative floor is enforced here. */
    fun setBottomOffsetDp(offsetDp: Int) = update { it.copy(bottomOffsetDp = offsetDp.coerceAtLeast(0)) }

    private fun update(transform: (LayoutSettings) -> LayoutSettings) {
        val next = transform(store.settings.value)
        store.edit {
            putInt(KEY_HEIGHT, next.keyboardHeightDp)
            putBoolean(KEY_KEY_BACKGROUNDS, next.showKeyBackgrounds)
            putBoolean(KEY_MIDDLE_STRIPE, next.showMiddleRowStripe)
            putInt(KEY_BOTTOM_OFFSET, next.bottomOffsetDp)
            putBoolean(KEY_ALWAYS_UPPERCASE, next.alwaysShowUppercaseLetters)
            putBoolean(KEY_SHOW_TAP_PREVIEW, next.showTapPreview)
            putBoolean(KEY_EDGE_PADDING, next.edgePadding)
            putString(KEY_PLACEMENT, next.placement.name)
            putInt(KEY_FLOATING_WIDTH, next.floatingWidthDp)
            putInt(KEY_FLOATING_HEIGHT, next.floatingHeightDp)
            putInt(KEY_FLOATING_X, next.floatingXDp)
            putInt(KEY_FLOATING_Y, next.floatingYDp)
            putInt(KEY_ONE_HANDED_WIDTH, next.oneHandedWidthDp)
            putBoolean(KEY_SWAP_EMOJI_LANGUAGE, next.swapEmojiAndLanguage)
        }
    }

    fun close() = store.close()

    private fun load(prefs: SharedPreferences): LayoutSettings = LayoutSettings(
        keyboardHeightDp = prefs.getInt(KEY_HEIGHT, LayoutSettings.DEFAULT_HEIGHT_DP),
        showKeyBackgrounds = prefs.getBoolean(KEY_KEY_BACKGROUNDS, false),
        showMiddleRowStripe = prefs.getBoolean(KEY_MIDDLE_STRIPE, true),
        bottomOffsetDp = prefs.getInt(KEY_BOTTOM_OFFSET, 0),
        alwaysShowUppercaseLetters = prefs.getBoolean(KEY_ALWAYS_UPPERCASE, true),
        showTapPreview = prefs.getBoolean(KEY_SHOW_TAP_PREVIEW, true),
        edgePadding = prefs.getBoolean(KEY_EDGE_PADDING, false),
        // Stored by name, and an unrecognised one falls back to DOCKED rather than throwing —
        // a downgrade after this enum ever gains a case must not brick the keyboard.
        placement = runCatching { KeyboardPlacement.valueOf(prefs.getString(KEY_PLACEMENT, null) ?: "") }
            .getOrDefault(KeyboardPlacement.DOCKED),
        floatingWidthDp = prefs.getInt(KEY_FLOATING_WIDTH, LayoutSettings.DEFAULT_FLOATING_WIDTH_DP),
        floatingHeightDp = prefs.getInt(KEY_FLOATING_HEIGHT, LayoutSettings.DEFAULT_FLOATING_HEIGHT_DP),
        floatingXDp = prefs.getInt(KEY_FLOATING_X, LayoutSettings.UNSET_POSITION),
        floatingYDp = prefs.getInt(KEY_FLOATING_Y, KeyboardPlacementGeometry.DEFAULT_FLOATING_Y_DP),
        oneHandedWidthDp = prefs.getInt(KEY_ONE_HANDED_WIDTH, LayoutSettings.DEFAULT_ONE_HANDED_WIDTH_DP),
        swapEmojiAndLanguage = prefs.getBoolean(KEY_SWAP_EMOJI_LANGUAGE, false),
    )

    private companion object {
        const val PREFS_NAME = "omakey_layout_prefs"
        const val KEY_SWAP_EMOJI_LANGUAGE = "swap_emoji_and_language"
        const val KEY_HEIGHT = "keyboard_height_dp"
        const val KEY_KEY_BACKGROUNDS = "show_key_backgrounds"
        const val KEY_MIDDLE_STRIPE = "show_middle_row_stripe"
        const val KEY_BOTTOM_OFFSET = "keyboard_bottom_offset_dp"
        const val KEY_ALWAYS_UPPERCASE = "always_show_uppercase_letters"
        const val KEY_SHOW_TAP_PREVIEW = "show_tap_preview"
        const val KEY_EDGE_PADDING = "edge_padding"
        const val KEY_PLACEMENT = "placement"
        const val KEY_FLOATING_WIDTH = "floating_width_dp"
        const val KEY_FLOATING_HEIGHT = "floating_height_dp"
        const val KEY_FLOATING_X = "floating_x_dp"
        const val KEY_FLOATING_Y = "floating_y_dp"
        const val KEY_ONE_HANDED_WIDTH = "one_handed_width_dp"
    }
}
