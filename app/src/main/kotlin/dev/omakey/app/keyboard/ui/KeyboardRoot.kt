package dev.omakey.app.keyboard.ui

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.app.keyboard.KeyboardFeedback
import dev.omakey.app.keyboard.KeyboardViewModel
import dev.omakey.app.keyboard.NoOpKeyboardFeedback
import dev.omakey.app.keyboard.resolveEffectiveTheme
import dev.omakey.core.theme.toComposeColor
import dev.omakey.core.theme.toDp
import dev.omakey.core.theme.gridCellBorder
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.gesture.KeyHitTester
import dev.omakey.core.layout.KeyDefinition
import dev.omakey.core.layout.KeyboardPlacement
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.theme.AccessibilityPreferences
import kotlinx.coroutines.delay

/** Matches `EmojiPanelExtension.id` (`extensions-builtin`) and
 * `KeyboardViewModel.PREFERRED_EXTENSION_ID` — kept as a plain string constant here rather than a
 * shared reference since both existing call sites already hardcode the same id string. */
private const val EMOJI_EXTENSION_ID = "builtin.emoji"

/** Matches `ClipboardHistoryExtension.id` — kept as a plain string constant for the same reason
 * as [EMOJI_EXTENSION_ID] above. */
private const val CLIPBOARD_EXTENSION_ID = "builtin.clipboard"

@Composable
fun KeyboardRoot(
    viewModel: KeyboardViewModel,
    accessibilityPreferences: AccessibilityPreferences? = null,
    onOpenSettings: () -> Unit = {},
    feedback: KeyboardFeedback = NoOpKeyboardFeedback,
    /** Reports the keyboard's own rectangle, in window coordinates, every time it is laid out.
     * `OmakeyInputMethodService` needs it to mark exactly that region touchable while floating —
     * everything outside must fall through to the app.
     *
     * Deliberately **not** defaulted, unlike the parameters above. It was, and floating mode was
     * silently broken for it: the sole call site never passed it, so the service's bounds field
     * stayed null forever and `onComputeInsets` early-returned on every call. A no-op default on a
     * required collaboration compiles fine and fails only at runtime, in one placement mode. A
     * preview or mock that wants to host this composable can pass `{}` explicitly. */
    onKeyboardBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val theme = resolveEffectiveTheme(uiState.theme, uiState.useSystemAccent)
    val scope = rememberCoroutineScope()
    val fontFamily = remember(uiState.fontId) { FontCatalog.resolve(uiState.fontId) }

    // Edge-to-edge surface-wide swipe gestures inherently conflict with TalkBack's touch
    // exploration (both want to own raw touch events on the same surface). Accessible mode drops
    // gesture capture entirely and falls back to ordinary per-key taps with content descriptions —
    // documented v1 limitation, not a full accessible redesign. Triggers automatically when
    // TalkBack's touch exploration is on, or via the user's explicit Settings override.
    val context = LocalContext.current
    val forcedAccessible by (accessibilityPreferences?.forceAccessibleMode
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val talkBackActive = remember(context) {
        val am = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        am?.isTouchExplorationEnabled == true
    }
    val accessibleMode = forcedAccessible || talkBackActive

    // System-provided touch slop, respects the user's accessibility touch-target settings.
    val touchSlopPx = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop

    // Key hit-testing state: rowIndex*1000+keyIndex -> (KeyDefinition, boundsInKeysArea).
    // Bounds are stored relative to keysAreaCoordinates (the pointerInput surface below), which is
    // the same coordinate space touch samples arrive in — NOT each row's own local space, which
    // would put every row's y-range at [0, rowHeight] and only ever match the first row.
    val keyBoundsState = remember { mutableStateOf(emptyMap<Int, Pair<KeyDefinition, Rect>>()) }
    var keysAreaCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }

    val hitTester = remember {
        KeyHitTester { x, y ->
            keyBoundsState.value.values.firstOrNull { (_, rect) -> rect.contains(Offset(x, y)) }?.first?.code ?: 0
        }
    }

    // Which key (if any) is currently held down — special keys (shift, backspace, ?123, emoji,
    // enter) render dimmed by default and "light up" to full brightness while pressed, matching
    // the Fleksy reference the theme is modeled on. Separate from `previewKey`'s enlarged bubble
    // above, which is character-keys-only.
    var pressedKeyCode by remember { mutableStateOf<Int?>(null) }
    val keyLookupByCode = remember {
        { code: Int -> keyBoundsState.value.values.firstOrNull { (key, _) -> key.code == code }?.first }
    }

    // Per-key tap preview: a brief enlarged-character bubble above the pressed key, distinct from
    // the long-press accent-drag popup (AccentDragPopup, inside KeyGrid) — this fires on every
    // ordinary tap, not just held punctuation/vowel keys. Only character keys get one; control keys (shift, space,
    // backspace, etc.) already show their own icon at full size, so a preview adds nothing there.
    var previewKey by remember { mutableStateOf<Pair<KeyDefinition, Rect>?>(null) }
    var previewToken by remember { mutableIntStateOf(0) }
    val onPreviewKeyStable = remember {
        { code: Int ->
            val match = keyBoundsState.value.values.firstOrNull { (key, _) -> key.code == code }
                ?.takeIf { (key, _) -> key.keyType == dev.omakey.core.layout.KeyType.CHARACTER }
            if (match != null) {
                previewKey = match
                previewToken++
            }
        }
    }
    if (previewKey != null) {
        val token = previewToken
        androidx.compose.runtime.LaunchedEffect(token) {
            delay(180)
            if (previewToken == token) previewKey = null
        }
    }

    // Compose can only skip recomposing a KeyRowView call if ALL of its parameters are stable
    // across the recomposition. Per-keystroke suggestion updates were previously recreating these
    // two lambdas as fresh objects every time (new identity each recomposition), which made every
    // key row recompose on every keystroke even though nothing about the rows themselves changed —
    // the main source of typing lag. Memoizing them (stable identity) lets Compose skip the whole
    // key grid on state changes that don't actually affect it, like suggestions or theme.
    val onKeyTapStable = remember(viewModel, feedback) {
        { code: Int -> feedback.onKeyPress(); viewModel.onKeyTap(code) }
    }
    val ancestorCoordinatesStable = remember { { keysAreaCoordinates } }

    val layoutSettings = uiState.layoutSettings
    val effectiveRows = uiState.layout.rows

    // --- placement ---------------------------------------------------------------------------
    // Size and position live here as *local* state seeded from preferences, not read straight off
    // them, so a resize drag repaints every frame without writing to SharedPreferences every frame.
    // Committed on drag end, exactly like Settings' own KeyboardSizePositionOverlay.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val screenHeightDp = configuration.screenHeightDp
    val placement = layoutSettings.placement
    val place = rememberPlacementState(viewModel, layoutSettings, screenWidthDp, screenHeightDp)

    // Row height is derived from each layout's own BASE row count (always 4, for both letters and
    // symbols — QwertyEnUS.rows.size), so keys are always the same size regardless of which
    // layout is active. The *height* it divides is the current placement's own — a floating
    // keyboard has its own height, which is what makes "resize in the current mode" work without
    // any mode-specific code down here.
    val rowHeightDp = place.keyboardHeightDp / KeyboardLocale.Default.letterLayout.rows.size
    val gridHeightDp = rowHeightDp * effectiveRows.size

    /** Zero unless floating, because only a floating keyboard carries a move handle. */
    val handleHeightDp = if (placement == KeyboardPlacement.FLOATING) FLOATING_HANDLE_HEIGHT_DP else 0

    /**
     * How tall the keyboard actually is, handle included.
     *
     * Named once and used everywhere rather than re-added per site, because the handle previously
     * had to be remembered in four separate places and was missed in one of them — the floating
     * window's own height. The keyboard Column is bottom-aligned inside that window, so the 22dp it
     * was short overflowed past the top edge and took the handle off screen with it: laid out,
     * present in the hierarchy, invisible and untappable.
     *
     * Deliberately *not* used for the extension panel or the one-handed gutter — those size the
     * content that sits below the handle, not the keyboard as a whole.
     */
    val keyboardTotalHeightDp = handleHeightDp + SUGGESTION_STRIP_HEIGHT_DP + gridHeightDp
    // The "home row" (asdfghjkl) is always the second row of the base QWERTY layout.
    val homeRowIndex = if (uiState.layout.id == KeyboardLocale.Default.letterLayout.id) 1 else -1

    // Everything below sits inside a placement container. Docked, it is a plain wrapper and the
    // keyboard fills it exactly as before. Floating, it is a tall transparent area the keyboard is
    // positioned within — which only works because onComputeInsets tells the system the IME
    // occupies no space and only the keyboard's own bounds are touchable (see
    // OmakeyInputMethodService.onComputeInsets). One-handed, it is full width with the keyboard
    // pushed to one side and the gutter holding its side buttons.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (placement == KeyboardPlacement.FLOATING) {
                    // Fixed at the screen height rather than "exactly tall enough for the keyboard
                    // at its current position", which is what this used to be.
                    //
                    // That version made the window height a function of floatingBottomDp — so
                    // every frame of a vertical drag resized the IME window itself, which means a
                    // full measure/layout pass plus onComputeInsets plus whatever the host app does
                    // in response, sixty times a second. It was the main reason dragging felt
                    // laggy.
                    //
                    // A fixed-height window is safe here in a way it would not have been before
                    // onComputeInsets started working: contentTopInsets already tells the host the
                    // IME occupies no space, and touchableRegion is set to the keyboard's own
                    // rectangle, so the extra window area is neither reserved nor touch-absorbing.
                    // The keyboard Column inside is bottom-aligned and offset upward, so its
                    // position is unaffected.
                    Modifier.height(screenHeightDp.dp)
                } else {
                    Modifier.wrapContentHeight()
                },
            ),
    ) {
        if (placement.isOneHanded) {
            OneHandedGutter(
                viewModel = viewModel,
                theme = theme,
                placement = placement,
                heightDp = SUGGESTION_STRIP_HEIGHT_DP + gridHeightDp,
                modifier = Modifier.align(
                    if (placement == KeyboardPlacement.ONE_HANDED_LEFT) Alignment.TopEnd else Alignment.TopStart,
                ),
            )
        }

    Column(
        modifier = Modifier
            .align(
                when {
                    placement == KeyboardPlacement.FLOATING -> Alignment.BottomStart
                    placement == KeyboardPlacement.ONE_HANDED_LEFT -> Alignment.TopStart
                    placement == KeyboardPlacement.ONE_HANDED_RIGHT -> Alignment.TopEnd
                    else -> Alignment.TopStart
                },
            )
            .then(
                when {
                    placement == KeyboardPlacement.FLOATING ->
                        Modifier
                            // Lambda overload: the position changes on every frame of a drag,
                            // and this form re-runs layout only, instead of recomposing the entire
                            // keyboard tree underneath. Reads the float fields directly so the
                            // keyboard moves with the finger rather than in whole-dp steps.
                            .offset {
                                IntOffset(
                                    x = place.floatingXDpFloat.dp.roundToPx(),
                                    y = -place.floatingBottomDpFloat.dp.roundToPx(),
                                )
                            }
                            .width(place.widthDp.dp)
                    placement.isOneHanded -> Modifier.width(place.widthDp.dp)
                    else -> Modifier.fillMaxWidth()
                },
            )
            // The keyboard's own bounds, reported to the service so it can mark exactly this
            // rectangle as the touchable region while floating. Everything outside it must reach
            // the app underneath, which is the whole point of floating.
            .onGloballyPositioned { onKeyboardBoundsChanged(it.boundsInWindow()) }
            .background(theme.keyboardBackground.toComposeColor())
            // Leaves a gap for the system gesture pill / 3-button nav bar instead of drawing under
            // it — without this the bottom row (and the extension panel's close button) can sit
            // underneath or flush against the system nav area. Bottom only (real bug, fixed): the
            // plain `.navigationBarsPadding()` this used to be applies whatever sides
            // WindowInsets.navigationBars reports, which on gesture-nav devices includes left/
            // right edge-gesture insets too — invisible in Normal mode (no border to reveal it)
            // but a very visible dark gutter down both sides once Grid mode's bordered cells make
            // "the keyboard isn't actually full-width" obvious.
            // Docked only: floating and one-handed keyboards are positioned by the user, and a
            // system-inset gap under a keyboard sitting in the middle of the screen is just a
            // stray band of keyboard colour.
            .then(
                if (placement == KeyboardPlacement.DOCKED) {
                    Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                } else {
                    Modifier
                },
            )
            // Optional gutters down both sides, so the outermost keys don't sit flush against a
            // curved/bezel-less edge. Applied here rather than per-row so the suggestion strip and
            // extension panels line up with the keys instead of running wider than them. Inside
            // the background above, not outside it, so the gutters are keyboard-coloured rather
            // than punching a hole through to the host app.
            // Edge padding compensates for a screen edge, so it only means anything when the
            // keyboard is actually against one.
            .padding(
                horizontal = if (layoutSettings.edgePadding && placement == KeyboardPlacement.DOCKED) {
                    LayoutSettings.EDGE_PADDING_DP.dp
                } else {
                    0.dp
                },
            ),
    ) {
        // A floating keyboard is the one placement with nowhere to grab: docked and one-handed
        // keyboards don't move, so their position needs no affordance. Dragging already worked in
        // resize mode, but only there — the user had to know to open Quick Access and enter a
        // separate mode before the keyboard could be moved at all, which is a discoverability
        // problem rather than a missing capability. This handle makes the common half of that
        // (reposition, not resize) directly available.
        if (placement == KeyboardPlacement.FLOATING) {
            FloatingMoveHandle(theme = theme, place = place)
        }

        // Which of the 3 "TopStrip is visible" bottom-content modes is active — used only to
        // drive the Crossfade below, not the branching itself (that's still the same explicit
        // if/else it always was). Keeping this a plain nullable key (not the bottom content
        // itself) means Crossfade briefly composes both the old and new mode's own composables
        // side by side during the ~150ms animation, which is safe here specifically because
        // KeyGrid and ExtensionPanelSlot each own entirely separate gesture/state — unlike
        // animating *within* KeyGrid across a layout change, which would risk two conflicting
        // sets of key-bounds being written into the same shared hit-testing map mid-transition.
        //
        // The long-press accent picker (`AccentDragPopup`, drawn inside KeyGrid itself) no longer
        // needs a slot here — it floats directly above the held key instead of replacing the
        // strip, and it's only ever visible for the same single continuous touch that opened it
        // (see KeyGrid's own doc), so there's no persistent "popup open" state to branch on here.
        val bottomContentMode = when {
            // Checked before the extension cases: the two share this slot and the view model
            // already clears one when the other opens, so this ordering only decides a race that
            // cannot happen — but it decides it the way the user's last tap intended.
            uiState.quickAccessOpen -> "quick-access"
            uiState.activeExtensionId == EMOJI_EXTENSION_ID -> "emoji"
            uiState.activeExtensionId == CLIPBOARD_EXTENSION_ID -> "clipboard"
            uiState.activeExtensionId == null -> "keys"
            else -> null // any other extension takes over the whole strip+grid area; see below
        }
        // Box (z-stacking), not Column, on purpose: the banner below needs to float *on top of*
        // whichever of TopStrip/KeyGrid/ExtensionPanelSlot is showing, not sit below it. The
        // actual vertical stacking of TopStrip above KeyGrid happens via the inner Column —
        // using this outer Box alone for that (real bug, fixed) silently painted KeyGrid's solid
        // background directly over TopStrip instead of below it, hiding the suggestion/tools/
        // numbers strip entirely.
        Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
        if (uiState.activeExtensionId != null && bottomContentMode == null) {
            // Replaces the suggestion strip AND the key grid entirely — matches how the
            // clipboard/GIF pickers behave on every mainstream keyboard, instead of stacking
            // above the keys and making the keyboard taller.
            ExtensionPanelSlot(
                viewModel = viewModel,
                heightDp = SUGGESTION_STRIP_HEIGHT_DP + gridHeightDp,
            )
        } else if (bottomContentMode != null) {
            when (bottomContentMode) {
                // The emoji panel already has its own alphabet-switch (ABC) and category tabs at
                // its bottom, so the extension switcher's own header row (clipboard/emoji/
                // keyboard icons) is redundant here — keep the normal suggestions/tools/numbers
                // top strip instead, exactly like the regular typing view.
                "emoji" -> TopStrip(viewModel = viewModel, uiState = uiState, theme = theme, fontFamily = fontFamily, feedback = feedback, quickAccessOpen = uiState.quickAccessOpen)
                // Locked to the Tools page with everything except Clipboard dimmed and swiping
                // between pages disabled — the top strip becomes a single-purpose "you're in
                // clipboard mode" bar. Tapping the (still enabled) Clipboard icon again exits.
                "clipboard" -> TopStrip(
                    viewModel = viewModel, uiState = uiState, theme = theme, fontFamily = fontFamily,
                    feedback = feedback, clipboardModeActive = true,
                    quickAccessOpen = uiState.quickAccessOpen,
                )
                else -> TopStrip(viewModel = viewModel, uiState = uiState, theme = theme, fontFamily = fontFamily, feedback = feedback, quickAccessOpen = uiState.quickAccessOpen)
            }
            // A short directional slide for the region below the top strip when switching between
            // the normal keyboard and the emoji/clipboard panels — entering a panel slides up,
            // returning to the keyboard slides back down, instead of a directionless cross-fade.
            // Pure slide, deliberately no accompanying fadeIn/fadeOut — combining a translation
            // layer with an alpha layer on top of KeyGrid/ExtensionPanelSlot (both non-trivial
            // composables: real pointer-input gesture detectors and hit-testing bounds, or a
            // LazyVerticalGrid of emoji) doubled the compositing work during the animation window.
            //
            // The real cause of the reported "laggy, stutters, still looks like slide up" bug
            // (confirmed via adb screenrecord + frame-diffing: the emoji->keys transition visibly
            // kept changing for ~700ms, versus ~200ms for keys->emoji) was neither of the above —
            // it was AnimatedContent's *default* SizeTransform, which nobody had overridden.
            // Whenever this ContentTransform's two children report even a slightly different
            // measured height (KeyGrid vs ExtensionPanelSlot, at different composition/measure
            // passes, both nominally driven by the same gridHeightDp but not guaranteed pixel-
            // identical), the default SizeTransform animates the container size with a
            // Spring.StiffnessMediumLow spring — which takes ~500-700ms to settle, is clipped to
            // that slowly-resizing container the whole time, and starts every transition over
            // again if it's still mid-flight when interrupted. That's the actual multi-hundred-ms
            // "stutter," not a direction bug or compositing cost. Since both children are always
            // meant to be exactly gridHeightDp tall, there's nothing to animate here — disabling
            // size animation entirely (snap, no clip) removes the spring altogether.
            val slideSpec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(180)
            // clip = false (tried in the previous attempt) let the sliding content paint outside
            // its own box while translated — which meant it could visually paint over TopStrip
            // above it instead of just sliding within its own area, making TopStrip look like it
            // "disappeared." Keeping clip = true (the safe default) but wrapping AnimatedContent
            // in its own fixed-height Box (exactly gridHeightDp, matching both children exactly)
            // is what actually removes the need for any size animation in the first place — the
            // container's size is now decided by the outer Box, never by AnimatedContent itself,
            // so its default spring-based SizeTransform has nothing to do regardless.
            val noSizeAnimation = androidx.compose.animation.SizeTransform(clip = true) { _, _ ->
                androidx.compose.animation.core.snap()
            }
            Box(Modifier.fillMaxWidth().height(gridHeightDp.dp)) {
                androidx.compose.animation.AnimatedContent(
                    targetState = bottomContentMode,
                    transitionSpec = {
                        if (targetState == "keys") {
                            (androidx.compose.animation.slideInVertically(slideSpec) { -it } togetherWith
                                androidx.compose.animation.slideOutVertically(slideSpec) { it })
                                .using(noSizeAnimation)
                        } else {
                            (androidx.compose.animation.slideInVertically(slideSpec) { it } togetherWith
                                androidx.compose.animation.slideOutVertically(slideSpec) { -it })
                                .using(noSizeAnimation)
                        }
                    },
                    label = "bottom-content-mode",
                ) { mode ->
                when (mode) {
                    "quick-access" -> QuickAccessPanel(
                        viewModel = viewModel,
                        theme = theme,
                        fontFamily = fontFamily,
                        feedback = feedback,
                        placement = placement,
                        heightDp = gridHeightDp,
                        onOpenSettings = onOpenSettings,
                    )
                    "emoji", "clipboard" -> ExtensionPanelSlot(viewModel = viewModel, heightDp = gridHeightDp, showHeaderRow = false)
                    else -> KeyGrid(
                        viewModel = viewModel,
                        uiState = uiState,
                        theme = theme,
                        layoutSettings = layoutSettings,
                        effectiveRows = effectiveRows,
                        rowHeightDp = rowHeightDp,
                        gridHeightDp = gridHeightDp,
                        homeRowIndex = homeRowIndex,
                        accessibleMode = accessibleMode,
                        touchSlopPx = touchSlopPx,
                        hitTester = hitTester,
                        keyLookupByCode = keyLookupByCode,
                        keyBoundsState = keyBoundsState,
                        scope = scope,
                        onKeyTapStable = onKeyTapStable,
                        ancestorCoordinatesStable = ancestorCoordinatesStable,
                        onKeysAreaPositioned = { keysAreaCoordinates = it },
                        onPreviewKey = onPreviewKeyStable,
                        previewKey = previewKey,
                        onOpenSettings = onOpenSettings,
                        feedback = feedback,
                        fontFamily = fontFamily,
                        pressedKeyCode = pressedKeyCode,
                        onPressedKeyChange = { pressedKeyCode = it },
                    )
                }
                }
            }
        }
        } // Column

        // Learn/unlearn confirmation ("hello learned") — an overlay independent of whichever
        // extension bar content (suggestions/numbers/tools, emoji, clipboard, or a third-party
        // extension's own ExtensionPanelSlot) happens to be active underneath, cleared
        // automatically after ~0.5s (see KeyboardViewModel.showBanner). Previously lived inside
        // TopStrip itself, so it silently never appeared while any non-suggestion extension panel
        // (anything routed through the `bottomContentMode == null` / ExtensionPanelSlot branch
        // above) was open.
        val banner = uiState.bannerMessage
        if (banner != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(SUGGESTION_STRIP_HEIGHT_DP.dp)
                    .background(theme.suggestionBarBackground.toComposeColor()),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = banner, color = theme.keyTextColor.toComposeColor(), fontFamily = fontFamily, fontSize = 14.sp)
            }
        }
        }

        // User-adjustable breathing room below the spacebar row, set via the drag-to-position
        // "placement mode" in Settings (see SettingsActivity's KeyboardPlacementOverlay) rather
        // than a plain height slider — raises the whole keyboard for easier one-handed thumb
        // reach. Zero by default (no visual change for anyone who hasn't opted in).
        //
        // Docked only: floating and one-handed keyboards are already positioned by the user, and
        // a second, invisible offset fighting with that position is how "I moved it and it didn't
        // go where I put it" bugs happen.
        // place.bottomOffsetDp, not layoutSettings — the former tracks a drag in progress, so the
        // keyboard rises under the finger instead of jumping when the drag ends.
        if (place.bottomOffsetDp > 0 && placement == KeyboardPlacement.DOCKED) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(place.bottomOffsetDp.dp)
                    .background(theme.keyboardBackground.toComposeColor()),
            )
        }
    } // keyboard Column

        // Drawn over the keyboard, inside the placement container so its handles sit on the
        // keyboard's real edges whatever mode it is in.
        if (uiState.resizing) {
            ResizeOverlay(
                viewModel = viewModel,
                theme = theme,
                placement = placement,
                place = place,
                totalHeightDp = keyboardTotalHeightDp,
                edgePaddingDp = if (layoutSettings.edgePadding && placement == KeyboardPlacement.DOCKED) {
                    LayoutSettings.EDGE_PADDING_DP
                } else {
                    0
                },
            )
        }
    } // placement container
}

/** Tracks a single continuous long-press-and-drag on a key with `popupChars` (accents/punctuation
 * variants) — see KeyGrid's long-press-timer branch for how this is entered and the MOVE/UP
 * handling right below it for how it's driven and resolved. [options] is `[key.label] +
 * key.popupChars`, extended in place with entries from [EXTENDED_POPUP_SYMBOLS] once the drag
 * goes past the curated set (Fleksy-style "keep dragging for more special characters").
 * [highlightedIndex] is whichever option currently sits under the finger — committed via
 * [dev.omakey.app.keyboard.KeyboardViewModel.onAccentSelected] on release. [baseOptionCount] is
 * `options.size` at creation time (before any extension) — options at/past that index are the
 * extended overflow tier, which `AccentDragPopup` fades in rather than popping in abruptly, so
 * dragging into "more symbols" territory reads as a subtle mode shift, not a jump cut. */
internal data class AccentDragState(
    val key: KeyDefinition,
    val options: List<String>,
    val highlightedIndex: Int,
    val baseOptionCount: Int = options.size,
)

@Composable
internal fun ExtensionPanelSlot(viewModel: KeyboardViewModel, heightDp: Int, showHeaderRow: Boolean = true) {
    val uiState by viewModel.uiState.collectAsState()
    val activeId = uiState.activeExtensionId ?: return
    // A misbehaving third-party-style extension must not be able to take down the whole IME
    // process. This catches instantiation/onAttach failures and first-composition failures —
    // it does not cover exceptions thrown during later recomposition, which would need a full
    // compose-runtime error boundary; documented v1 limitation, not a complete solution.
    val extension = runCatching { viewModel.extensionRegistry.getById(activeId) }.getOrNull() ?: return
    val allExtensions = runCatching { viewModel.extensionRegistry.all() }.getOrDefault(emptyList())

    androidx.compose.runtime.CompositionLocalProvider(
        dev.omakey.core.theme.LocalOmakeyTheme provides uiState.theme,
    ) {
        val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
        Column(
            Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .background(if (isGridMode) uiState.theme.keyboardBackground.toComposeColor() else uiState.theme.suggestionBarBackground.toComposeColor())
                // Directly on this same element as its own background — see KeyGrid's identical
                // fix/doc in KeyboardRoot for why a border on an ancestor kept getting silently
                // covered by this element's own descendants' opaque fills.
                .let { m -> if (isGridMode) m.border(uiState.theme.gridBorderWidth.toDp(), uiState.theme.gridBorderColor.toComposeColor()) else m },
        ) {
            if (showHeaderRow) {
                val gridBorderColor = uiState.theme.gridBorderColor.toComposeColor()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        // Plain border() directly on this Row, same fix/reasoning as KeyGrid's own
                        // doc — its own tab cells already draw right+bottom only.
                        .let { m -> if (isGridMode) m.border(uiState.theme.gridBorderWidth.toDp(), gridBorderColor) else m },
                ) {
                    allExtensions.forEach { ext ->
                        val glyph = (ext.icon as? dev.omakey.extapi.ExtensionIcon.Emoji)?.glyph ?: "•"
                        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = if (isGridMode) null else androidx.compose.foundation.LocalIndication.current,
                                ) { viewModel.selectExtension(ext.id) }
                                .background(
                                    when {
                                        isGridMode && (ext.id == activeId || isPressed) -> uiState.theme.keyBackgroundPressed.toComposeColor()
                                        // Real bug, fixed: inactive tabs fell through to
                                        // Color.Transparent in Grid mode too, same as every other
                                        // unfilled grid-mode cell — showing the header row's own
                                        // (potentially very different, on a custom theme)
                                        // background through instead of keyboardBackground.
                                        isGridMode -> uiState.theme.keyboardBackground.toComposeColor()
                                        ext.id == activeId -> uiState.theme.keySpecialBackground.toComposeColor()
                                        else -> Color.Transparent
                                    },
                                )
                                .let { m -> if (isGridMode) m.gridCellBorder(gridBorderColor, uiState.theme.gridBorderWidth.toDp()) else m },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = glyph, fontSize = 18.sp)
                        }
                    }
                    // Returns to the normal keyboard — lives in the panel's own header row rather
                    // than needing to double up with the ?123 key at the bottom, which used to be
                    // the only way back and sat awkwardly close to the system nav area.
                    val closeInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val closeIsPressed by closeInteractionSource.collectIsPressedAsState()
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = closeInteractionSource,
                                indication = if (isGridMode) null else androidx.compose.foundation.LocalIndication.current,
                            ) { viewModel.extensionHost.close() }
                            .let { m ->
                                if (isGridMode) {
                                    m.background(if (closeIsPressed) uiState.theme.keyBackgroundPressed.toComposeColor() else uiState.theme.keyboardBackground.toComposeColor())
                                        .gridCellBorder(gridBorderColor, uiState.theme.gridBorderWidth.toDp())
                                } else {
                                    m
                                }
                            }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "⌨", color = uiState.theme.keyTextColor.toComposeColor(), fontSize = 18.sp)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                var failed by remember(activeId) { mutableStateOf(false) }
                if (failed) {
                    Text(
                        text = "This extension couldn't be loaded.",
                        color = uiState.theme.keyTextColor.toComposeColor(),
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    runCatching {
                        extension.PanelContent(host = viewModel.extensionHost)
                    }.onFailure { failed = true }
                }
            }
        }
    }
}
