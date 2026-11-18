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
    /** How a tapped letter is shown while the finger is on it: a bubble (classic or slim), the
     * key lifting, or nothing beyond the key's own press state. Distinct from the long-press
     * accent/punctuation popup, `GestureSettings.showKeyPopup`, which is a held-key thing. */
    val tapPreviewStyle: TapPreviewStyle = TapPreviewStyle.DEFAULT,
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
    /** What the key left of the spacebar is: the emoji key ([EMOJI_SLOT_DEFAULT]), the language key
     * ([EMOJI_SLOT_LANGUAGE], with two or more languages), or a key typing one character. When it
     * isn't the emoji key, the emoji button moves to the suggestion bar. See [withEmojiSlotKey]. */
    val emojiSlotKey: String = EMOJI_SLOT_DEFAULT,
    /** A small secondary character in the corner of each letter key — the digit or symbol its
     * long-press types ([KeyDefinition.secondarySymbol]). Off by default: it is visual noise for
     * anyone who doesn't use long-press. While on, a long-press preselects that symbol so the
     * hint is what a plain hold-and-release types, the way keyboards that show these behave. */
    val showSecondarySymbols: Boolean = false,
    /** Fleksy's first symbols page ([Layouts.Symbols1]), with each letter's long-press symbol
     * following it (`withSymbolsPage`). On by default; off gives omakey's original page
     * ([Layouts.ClassicSymbols1]). */
    val fleksySymbols: Boolean = true,
    /** The user's own long-press symbols ([parsePopupSymbols]); empty, the default, means each
     * key's own. When set, every letter's popup is exactly these ([withPopupSymbols]), the drag-on
     * overflow row is gone, and so are the corner hints — the same symbol on every key is no hint. */
    val popupSymbols: List<String> = emptyList(),
    /** A number pad ([NumberPads]) instead of letters in fields that only take numbers. On by
     * default, with each kind of field switchable on its own below it — an app that tags a text
     * field as a number field is the reason to be able to turn one off. */
    val numberPad: Boolean = true,
    val numberPadNumber: Boolean = true,
    val numberPadPhone: Boolean = true,
    val numberPadDateTime: Boolean = true,
    /** What the key right of the spacebar types: one character, or [DEFAULT_SPACEBAR_SIDE_KEY] for
     * the language's own ("." / "।"). See [withSpacebarSideKey]. */
    val spacebarSideKey: String = DEFAULT_SPACEBAR_SIDE_KEY,
) {
    /** Whether corner hints are drawn and preselected: see [popupSymbols]. */
    val secondarySymbolsShown: Boolean get() = showSecondarySymbols && popupSymbols.isEmpty()

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
    fun setTapPreviewStyle(style: TapPreviewStyle) = update { it.copy(tapPreviewStyle = style) }
    fun setEdgePadding(enabled: Boolean) = update { it.copy(edgePadding = enabled) }
    fun setEmojiSlotKey(value: String) = update { it.copy(emojiSlotKey = sanitizeEmojiSlotKey(value)) }
    fun setShowSecondarySymbols(show: Boolean) = update { it.copy(showSecondarySymbols = show) }
    fun setFleksySymbols(enabled: Boolean) = update { it.copy(fleksySymbols = enabled) }
    fun setPopupSymbols(input: String) = update { it.copy(popupSymbols = parsePopupSymbols(input)) }
    fun setSpacebarSideKey(value: String) = update { it.copy(spacebarSideKey = sanitizeSpacebarSideKey(value)) }
    fun setNumberPad(enabled: Boolean) = update { it.copy(numberPad = enabled) }
    fun setNumberPadFor(kind: NumberPadKind, enabled: Boolean) = update {
        when (kind) {
            NumberPadKind.NUMBER -> it.copy(numberPadNumber = enabled)
            NumberPadKind.PHONE -> it.copy(numberPadPhone = enabled)
            NumberPadKind.DATETIME -> it.copy(numberPadDateTime = enabled)
        }
    }

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
            putString(KEY_TAP_PREVIEW_STYLE, next.tapPreviewStyle.name)
            // Still written, so a downgrade to a build with only the on/off switch keeps the
            // user's on/off choice.
            putBoolean(KEY_SHOW_TAP_PREVIEW, next.tapPreviewStyle != TapPreviewStyle.OFF)
            putBoolean(KEY_EDGE_PADDING, next.edgePadding)
            putString(KEY_PLACEMENT, next.placement.name)
            putInt(KEY_FLOATING_WIDTH, next.floatingWidthDp)
            putInt(KEY_FLOATING_HEIGHT, next.floatingHeightDp)
            putInt(KEY_FLOATING_X, next.floatingXDp)
            putInt(KEY_FLOATING_Y, next.floatingYDp)
            putInt(KEY_ONE_HANDED_WIDTH, next.oneHandedWidthDp)
            putString(KEY_EMOJI_SLOT, next.emojiSlotKey)
            // Still written, so a downgrade to a build with only the swap switch keeps it.
            putBoolean(KEY_SWAP_EMOJI_LANGUAGE, next.emojiSlotKey == EMOJI_SLOT_LANGUAGE)
            putBoolean(KEY_SECONDARY_SYMBOLS, next.showSecondarySymbols)
            putBoolean(KEY_FLEKSY_SYMBOLS, next.fleksySymbols)
            // Stored as typed-back text: parsePopupSymbols splits it into the same symbols again.
            putString(KEY_POPUP_SYMBOLS, next.popupSymbols.joinToString(""))
            putBoolean(KEY_NUMBER_PAD, next.numberPad)
            putBoolean(KEY_NUMBER_PAD_NUMBER, next.numberPadNumber)
            putBoolean(KEY_NUMBER_PAD_PHONE, next.numberPadPhone)
            putBoolean(KEY_NUMBER_PAD_DATETIME, next.numberPadDateTime)
            putString(KEY_SPACEBAR_SIDE_KEY, next.spacebarSideKey)
        }
    }

    fun close() = store.close()

    private fun load(prefs: SharedPreferences): LayoutSettings = LayoutSettings(
        keyboardHeightDp = prefs.getInt(KEY_HEIGHT, LayoutSettings.DEFAULT_HEIGHT_DP),
        showKeyBackgrounds = prefs.getBoolean(KEY_KEY_BACKGROUNDS, false),
        showMiddleRowStripe = prefs.getBoolean(KEY_MIDDLE_STRIPE, true),
        bottomOffsetDp = prefs.getInt(KEY_BOTTOM_OFFSET, 0),
        alwaysShowUppercaseLetters = prefs.getBoolean(KEY_ALWAYS_UPPERCASE, true),
        tapPreviewStyle = TapPreviewStyle.fromStored(
            prefs.getString(KEY_TAP_PREVIEW_STYLE, null),
            legacyShown = prefs.getBoolean(KEY_SHOW_TAP_PREVIEW, true),
        ),
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
        emojiSlotKey = emojiSlotKeyFromStored(
            prefs.getString(KEY_EMOJI_SLOT, null),
            legacySwap = prefs.getBoolean(KEY_SWAP_EMOJI_LANGUAGE, false),
        ),
        showSecondarySymbols = prefs.getBoolean(KEY_SECONDARY_SYMBOLS, false),
        fleksySymbols = prefs.getBoolean(KEY_FLEKSY_SYMBOLS, true),
        popupSymbols = parsePopupSymbols(prefs.getString(KEY_POPUP_SYMBOLS, null).orEmpty()),
        numberPad = prefs.getBoolean(KEY_NUMBER_PAD, true),
        numberPadNumber = prefs.getBoolean(KEY_NUMBER_PAD_NUMBER, true),
        numberPadPhone = prefs.getBoolean(KEY_NUMBER_PAD_PHONE, true),
        numberPadDateTime = prefs.getBoolean(KEY_NUMBER_PAD_DATETIME, true),
        spacebarSideKey = sanitizeSpacebarSideKey(prefs.getString(KEY_SPACEBAR_SIDE_KEY, null)),
    )

    private companion object {
        const val PREFS_NAME = "omakey_layout_prefs"
        const val KEY_SWAP_EMOJI_LANGUAGE = "swap_emoji_and_language"
        const val KEY_EMOJI_SLOT = "emoji_slot_key"
        const val KEY_SECONDARY_SYMBOLS = "show_secondary_symbols"
        const val KEY_FLEKSY_SYMBOLS = "fleksy_symbols"
        const val KEY_POPUP_SYMBOLS = "popup_symbols"
        const val KEY_NUMBER_PAD = "number_pad"
        const val KEY_NUMBER_PAD_NUMBER = "number_pad_number"
        const val KEY_NUMBER_PAD_PHONE = "number_pad_phone"
        const val KEY_NUMBER_PAD_DATETIME = "number_pad_datetime"
        const val KEY_SPACEBAR_SIDE_KEY = "spacebar_side_key"
        const val KEY_HEIGHT = "keyboard_height_dp"
        const val KEY_KEY_BACKGROUNDS = "show_key_backgrounds"
        const val KEY_MIDDLE_STRIPE = "show_middle_row_stripe"
        const val KEY_BOTTOM_OFFSET = "keyboard_bottom_offset_dp"
        const val KEY_ALWAYS_UPPERCASE = "always_show_uppercase_letters"
        const val KEY_SHOW_TAP_PREVIEW = "show_tap_preview"
        const val KEY_TAP_PREVIEW_STYLE = "tap_preview_style"
        const val KEY_EDGE_PADDING = "edge_padding"
        const val KEY_PLACEMENT = "placement"
        const val KEY_FLOATING_WIDTH = "floating_width_dp"
        const val KEY_FLOATING_HEIGHT = "floating_height_dp"
        const val KEY_FLOATING_X = "floating_x_dp"
        const val KEY_FLOATING_Y = "floating_y_dp"
        const val KEY_ONE_HANDED_WIDTH = "one_handed_width_dp"
    }
}
