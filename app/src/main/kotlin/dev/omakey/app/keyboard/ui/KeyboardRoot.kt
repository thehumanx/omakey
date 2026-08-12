package dev.omakey.app.keyboard.ui

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.app.keyboard.KeyboardFeedback
import dev.omakey.app.keyboard.KeyboardViewModel
import dev.omakey.app.keyboard.NoOpKeyboardFeedback
import dev.omakey.app.keyboard.resolveEffectiveTheme
import dev.omakey.core.icons.*
import dev.omakey.core.gesture.GestureEvent
import dev.omakey.core.gesture.GestureStateMachine
import dev.omakey.core.gesture.GestureThresholds
import dev.omakey.core.gesture.KeyHitTester
import dev.omakey.core.gesture.SwipeDirection
import dev.omakey.core.gesture.TouchAction
import dev.omakey.core.gesture.TouchSample
import dev.omakey.core.layout.KeyDefinition
import dev.omakey.core.layout.KeyRow as LayoutKeyRow
import dev.omakey.core.layout.Layouts
import dev.omakey.core.layout.SpecialKeyCode
import dev.omakey.core.layout.computeKeyWidthsPx
import dev.omakey.core.theme.AccessibilityPreferences
import dev.omakey.core.theme.ColorSpec
import dev.omakey.core.theme.OmakeyTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private fun ColorSpec.toComposeColor() = Color(argb.toInt())

private const val SUGGESTION_STRIP_HEIGHT_DP = 44

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

    var accentPopupKey by remember { mutableStateOf<KeyDefinition?>(null) }
    // Which key (if any) is currently held down — special keys (shift, backspace, ?123, emoji,
    // enter) render dimmed by default and "light up" to full brightness while pressed, matching
    // the Fleksy reference the theme is modeled on. Separate from `previewKey`'s enlarged bubble
    // above, which is character-keys-only.
    var pressedKeyCode by remember { mutableStateOf<Int?>(null) }
    val keyLookupByCode = remember {
        { code: Int -> keyBoundsState.value.values.firstOrNull { (key, _) -> key.code == code }?.first }
    }

    // Per-key tap preview: a brief enlarged-character bubble above the pressed key, distinct from
    // the long-press accent picker above (AccentPickerBar) — this fires on every ordinary tap, not
    // just held punctuation/vowel keys. Only character keys get one; control keys (shift, space,
    // backspace, etc.) already show their own icon at full size, so a preview adds nothing there.
    var previewKey by remember { mutableStateOf<Pair<KeyDefinition, Rect>?>(null) }
    var previewToken by remember { mutableStateOf(0) }
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
    // Row height is derived from each layout's own BASE row count (always 4, for both letters and
    // symbols — QwertyEnUS.rows.size), so keys are always the same size regardless of which
    // layout is active.
    val rowHeightDp = layoutSettings.keyboardHeightDp / Layouts.QwertyEnUS.rows.size
    val gridHeightDp = rowHeightDp * effectiveRows.size
    // The "home row" (asdfghjkl) is always the second row of the base QWERTY layout.
    val homeRowIndex = if (uiState.layout.id == Layouts.QwertyEnUS.id) 1 else -1

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.keyboardBackground.toComposeColor())
            // Leaves a gap for the system gesture pill / 3-button nav bar instead of drawing under
            // it — without this the bottom row (and the extension panel's close button) can sit
            // underneath or flush against the system nav area.
            .navigationBarsPadding(),
    ) {
        val popupKey = accentPopupKey
        // Which of the 3 "TopStrip is visible" bottom-content modes is active — used only to
        // drive the Crossfade below, not the branching itself (that's still the same explicit
        // if/else it always was). Keeping this a plain nullable key (not the bottom content
        // itself) means Crossfade briefly composes both the old and new mode's own composables
        // side by side during the ~150ms animation, which is safe here specifically because
        // KeyGrid and ExtensionPanelSlot each own entirely separate gesture/state — unlike
        // animating *within* KeyGrid across a layout change, which would risk two conflicting
        // sets of key-bounds being written into the same shared hit-testing map mid-transition.
        val bottomContentMode = when {
            popupKey != null -> null // accent popup replaces the strip entirely; no crossfade
            uiState.activeExtensionId == EMOJI_EXTENSION_ID -> "emoji"
            uiState.activeExtensionId == CLIPBOARD_EXTENSION_ID -> "clipboard"
            uiState.activeExtensionId == null -> "keys"
            else -> null // any other extension takes over the whole strip+grid area; see below
        }
        if (uiState.activeExtensionId != null && popupKey == null && bottomContentMode == null) {
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
                "emoji" -> TopStrip(viewModel = viewModel, uiState = uiState, theme = theme, fontFamily = fontFamily, feedback = feedback)
                // Locked to the Tools page with everything except Clipboard dimmed and swiping
                // between pages disabled — the top strip becomes a single-purpose "you're in
                // clipboard mode" bar. Tapping the (still enabled) Clipboard icon again exits.
                "clipboard" -> TopStrip(
                    viewModel = viewModel, uiState = uiState, theme = theme, fontFamily = fontFamily,
                    feedback = feedback, clipboardModeActive = true,
                )
                else -> TopStrip(viewModel = viewModel, uiState = uiState, theme = theme, fontFamily = fontFamily, feedback = feedback)
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
                        onShowAccentPopup = { accentPopupKey = it },
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
        } else if (popupKey != null) {
            AccentPickerBar(
                key = popupKey,
                theme = theme,
                onSelect = { char ->
                    viewModel.onAccentSelected(char)
                    accentPopupKey = null
                },
                onDismiss = { accentPopupKey = null },
            )
            KeyGrid(
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
                onShowAccentPopup = { accentPopupKey = it },
                onPreviewKey = onPreviewKeyStable,
                previewKey = previewKey,
                onOpenSettings = onOpenSettings,
                feedback = feedback,
                fontFamily = fontFamily,
                pressedKeyCode = pressedKeyCode,
                onPressedKeyChange = { pressedKeyCode = it },
            )
        }

        // User-adjustable breathing room below the spacebar row, set via the drag-to-position
        // "placement mode" in Settings (see SettingsActivity's KeyboardPlacementOverlay) rather
        // than a plain height slider — raises the whole keyboard for easier one-handed thumb
        // reach. Zero by default (no visual change for anyone who hasn't opted in).
        if (layoutSettings.bottomOffsetDp > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(layoutSettings.bottomOffsetDp.dp)
                    .background(theme.keyboardBackground.toComposeColor()),
            )
        }
    }
}

@Composable
private fun KeyGrid(
    viewModel: KeyboardViewModel,
    uiState: dev.omakey.app.keyboard.KeyboardUiState,
    theme: OmakeyTheme,
    layoutSettings: dev.omakey.core.layout.LayoutSettings,
    effectiveRows: List<LayoutKeyRow>,
    rowHeightDp: Int,
    gridHeightDp: Int,
    homeRowIndex: Int,
    accessibleMode: Boolean,
    touchSlopPx: Float,
    hitTester: KeyHitTester,
    keyLookupByCode: (Int) -> KeyDefinition?,
    keyBoundsState: androidx.compose.runtime.MutableState<Map<Int, Pair<KeyDefinition, Rect>>>,
    scope: kotlinx.coroutines.CoroutineScope,
    onKeyTapStable: (Int) -> Unit,
    ancestorCoordinatesStable: () -> androidx.compose.ui.layout.LayoutCoordinates?,
    onKeysAreaPositioned: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit,
    onShowAccentPopup: (KeyDefinition?) -> Unit,
    onPreviewKey: (Int) -> Unit,
    previewKey: Pair<KeyDefinition, Rect>?,
    onOpenSettings: () -> Unit,
    feedback: KeyboardFeedback,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    pressedKeyCode: Int?,
    onPressedKeyChange: (Int?) -> Unit,
) {
    val gestureSettings = uiState.gestureSettings
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(gridHeightDp.dp)
            .onGloballyPositioned(onKeysAreaPositioned)
            .let { base ->
                if (accessibleMode) {
                    base // gesture capture skipped entirely — keys below are individually clickable
                } else {
                    // sensitivity/showKeyPopup are keys here (not just captured) so a change made
                    // in Settings while the keyboard is open takes effect on the very next gesture,
                    // not only after the layout itself changes.
                    base.pointerInput(uiState.layout.id, touchSlopPx, gestureSettings.swipeSensitivity, gestureSettings.showKeyPopup) {
                        val thresholds = GestureThresholds(
                            // Was previously hardcoded to 12f — smaller than the system's real
                            // touch slop, which meant ordinary finger movement during normal
                            // (esp. fast) typing crossed out of "pure tap" territory too easily,
                            // landing in SWIPE_CANDIDATE more often than intended.
                            touchSlopPx = touchSlopPx,
                            // Base fractions lowered from 0.18/0.25 — still tunable per-user via
                            // the sensitivity multiplier, but the out-of-the-box default should
                            // not require a near-full-width swipe just to delete a word.
                            minSwipeDistancePxHorizontal = size.width * 0.13f * gestureSettings.swipeSensitivity,
                            minSwipeDistancePxVertical = size.height * 0.18f * gestureSettings.swipeSensitivity,
                        )
                        val machine = GestureStateMachine(thresholds, hitTester)

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downTime = System.currentTimeMillis()
                            onPressedKeyChange(hitTester.keyCodeAt(down.position.x, down.position.y).takeIf { it != 0 })
                            machine.onTouch(TouchSample(down.position.x, down.position.y, downTime, TouchAction.DOWN))

                            // Long-press-and-drag-to-move-cursor (spacebar only): once engaged,
                            // this whole gesture is "consumed" by cursor dragging — the normal
                            // GestureStateMachine tap/swipe path below is skipped entirely for the
                            // rest of this touch (see the `if (!cursorDragActive)` guards), same
                            // idea as how held-BACKSPACE-repeat/held-SHIFT-caps-lock already
                            // short-circuit the generic long-press fallback.
                            var cursorDragActive = false
                            var cursorDragAnchorX = down.position.x
                            val cursorDragStepPx = 10.dp.toPx()

                            val longPressJob: Job = scope.launch {
                                delay(400)
                                if (isActive) {
                                    val event = machine.onLongPressTimerFired(down.position.x, down.position.y)
                                    val heldKey = (event as? GestureEvent.KeyLongPress)?.let { keyLookupByCode(it.keyCode) }
                                    if (heldKey?.code == SpecialKeyCode.BACKSPACE) {
                                        // Repeat-delete while held, matching standard keyboard
                                        // convention — stops as soon as the finger lifts, since
                                        // that cancels this whole job (see the UP branch below).
                                        while (isActive) {
                                            feedback.onKeyPress()
                                            viewModel.onKeyTap(SpecialKeyCode.BACKSPACE)
                                            delay(60)
                                        }
                                    } else if (heldKey?.code == SpecialKeyCode.SHIFT) {
                                        // Holding shift engages caps lock (stays on until shift is
                                        // tapped again), distinct from a plain tap's one-shot
                                        // "capitalize just the next letter" — matches standard
                                        // mobile keyboard convention. Routing this through the
                                        // generic KeyLongPress -> onKeyTap fallback below would
                                        // just toggle one-shot shift a second time instead.
                                        feedback.onKeyPress()
                                        viewModel.enableCapsLock()
                                    } else if (heldKey?.code == SpecialKeyCode.SPACE) {
                                        feedback.onKeyPress()
                                        cursorDragActive = true
                                        cursorDragAnchorX = down.position.x
                                    } else {
                                        handleGestureEvent(
                                            event, viewModel, keyLookupByCode, gestureSettings.showKeyPopup, gestureSettings.swipeRightForSpace,
                                            onShowAccentPopup, onPreviewKey, onOpenSettings, feedback,
                                        )
                                    }
                                }
                            }

                            var settled = false
                            while (!settled) {
                                val event = awaitPointerEvent()

                                // Multi-touch key rollover: this loop only tracks `down.id`, the
                                // pointer that started this gesture. Fast typists' fingers overlap
                                // in time — a second finger can land on another key before the
                                // first one lifts. Without this, that second touch is never seen as
                                // a "first down" by the next awaitEachGesture iteration (it already
                                // transitioned to pressed in the past) and its key press is silently
                                // dropped. Any other pointer that goes down mid-gesture is typed
                                // immediately as a plain tap — simultaneous secondary fingers during
                                // typing are never meant as swipes, so no need to route them through
                                // the swipe/long-press machine.
                                if (!cursorDragActive) {
                                    event.changes.forEach { other ->
                                        if (other.id != down.id && other.changedToDownIgnoreConsumed()) {
                                            val code = hitTester.keyCodeAt(other.position.x, other.position.y)
                                            if (code != 0) {
                                                feedback.onKeyPress()
                                                onPreviewKey(code)
                                                viewModel.onKeyTap(code)
                                            }
                                        }
                                    }
                                }

                                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                                val now = System.currentTimeMillis()
                                if (!change.pressed) {
                                    longPressJob.cancel()
                                    onPressedKeyChange(null)
                                    if (cursorDragActive) {
                                        settled = true
                                    } else {
                                        val gestureEvent = machine.onTouch(
                                            TouchSample(change.position.x, change.position.y, now, TouchAction.UP),
                                        )
                                        handleGestureEvent(
                                            gestureEvent, viewModel, keyLookupByCode, gestureSettings.showKeyPopup, gestureSettings.swipeRightForSpace,
                                            onShowAccentPopup, onPreviewKey, onOpenSettings, feedback,
                                        )
                                        settled = true
                                    }
                                } else if (cursorDragActive) {
                                    val delta = change.position.x - cursorDragAnchorX
                                    if (delta >= cursorDragStepPx) {
                                        feedback.onKeyPress()
                                        viewModel.moveCursor(forward = true)
                                        cursorDragAnchorX = change.position.x
                                    } else if (delta <= -cursorDragStepPx) {
                                        feedback.onKeyPress()
                                        viewModel.moveCursor(forward = false)
                                        cursorDragAnchorX = change.position.x
                                    }
                                } else {
                                    val gestureEvent = machine.onTouch(
                                        TouchSample(change.position.x, change.position.y, now, TouchAction.MOVE),
                                    )
                                    if (gestureEvent != null) longPressJob.cancel()
                                    handleGestureEvent(
                                        gestureEvent, viewModel, keyLookupByCode, gestureSettings.showKeyPopup, gestureSettings.swipeRightForSpace,
                                        onShowAccentPopup, onPreviewKey, onOpenSettings, feedback,
                                    )
                                }
                            }
                        }
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxWidth().padding(PaddingValues(horizontal = 4.dp))) {
            effectiveRows.forEachIndexed { rowIndex, row ->
                val onBoundsMeasuredStable = remember(rowIndex) {
                    { keyIndex: Int, key: KeyDefinition, rect: Rect ->
                        keyBoundsState.value = keyBoundsState.value + (rowIndex * 1000 + keyIndex to (key to rect))
                    }
                }
                KeyRowView(
                    rowKeys = row.keys,
                    rowHeightDp = rowHeightDp,
                    shiftOn = uiState.shiftOn,
                    theme = theme,
                    accessibleMode = accessibleMode,
                    showKeyBackgrounds = layoutSettings.showKeyBackgrounds,
                    isHomeRow = layoutSettings.showMiddleRowStripe && rowIndex == homeRowIndex,
                    onKeyTap = onKeyTapStable,
                    ancestorCoordinates = ancestorCoordinatesStable,
                    onBoundsMeasured = onBoundsMeasuredStable,
                    fontFamily = fontFamily,
                    pressedKeyCode = pressedKeyCode,
                    capsLockOn = uiState.capsLockOn,
                    enterAction = uiState.enterAction,
                )
            }
        }

        if (previewKey != null) {
            val (key, rect) = previewKey
            val density = androidx.compose.ui.platform.LocalDensity.current
            val bubbleWidthPx = with(density) { 44.dp.toPx() }.coerceAtLeast(rect.width)
            val bubbleHeightPx = with(density) { 52.dp.toPx() }
            val gapPx = with(density) { 6.dp.toPx() }
            val parentWidthPx = ancestorCoordinatesStable()?.size?.width?.toFloat() ?: (rect.right)
            val x = ((rect.left + rect.right) / 2f - bubbleWidthPx / 2f)
                .coerceIn(0f, (parentWidthPx - bubbleWidthPx).coerceAtLeast(0f))
            val y = (rect.top - bubbleHeightPx - gapPx).coerceAtLeast(0f)
            Box(
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) }
                    .size(with(density) { bubbleWidthPx.toDp() }, with(density) { bubbleHeightPx.toDp() })
                    .background(theme.keySpecialBackground.toComposeColor(), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = key.label.let { if (it.length == 1) it.uppercase() else it },
                    color = theme.keyTextColor.toComposeColor(),
                    fontFamily = fontFamily,
                    fontSize = 26.sp,
                )
            }
        }
    }
}

/** Every key gets an icon now (shift/backspace always; Enter always, reflecting whatever the
 * focused field's [android.view.inputmethod.EditorInfo] action actually is — "Go" in a URL bar,
 * "Send" in a chat compose box, a plain return glyph otherwise) — a full, consistent Phosphor
 * "fill" icon family (see `PhosphorIcons.kt`) rather than the old mix of raw unicode glyphs
 * (`⇧`/`⌫`/`⏎`) plus ad-hoc emoji/text substitutions per Enter action ("🔍", "➤", "Go", "Next",
 * "Prev"). Every other key (letters, symbols, `?123`, emoji) keeps its plain text/emoji label —
 * only these two/three logical keys get icon treatment. */
private fun keyIcon(key: KeyDefinition, capsLockOn: Boolean, enterAction: Int): ImageVector? = when (key.code) {
    SpecialKeyCode.SHIFT -> if (capsLockOn) PhosphorShiftLocked else PhosphorShift
    SpecialKeyCode.BACKSPACE -> PhosphorBackspace
    SpecialKeyCode.ENTER -> enterIcon(enterAction)
    SpecialKeyCode.SETTINGS -> PhosphorGear
    else -> null
}

private fun enterIcon(enterAction: Int): ImageVector = when (enterAction) {
    android.view.inputmethod.EditorInfo.IME_ACTION_GO,
    android.view.inputmethod.EditorInfo.IME_ACTION_NEXT,
    -> PhosphorArrowRight
    android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> PhosphorSearch
    android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> PhosphorSend
    android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> PhosphorCheck
    android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS -> PhosphorArrowLeft
    else -> PhosphorEnter // NONE/UNSPECIFIED — plain newline.
}

private fun enterDescription(enterAction: Int): String = when (enterAction) {
    android.view.inputmethod.EditorInfo.IME_ACTION_GO -> "Go"
    android.view.inputmethod.EditorInfo.IME_ACTION_NEXT -> "Next"
    android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> "Search"
    android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> "Send"
    android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> "Done"
    android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS -> "Previous"
    else -> "Enter"
}

private fun describeKey(key: KeyDefinition, enterAction: Int = android.view.inputmethod.EditorInfo.IME_ACTION_NONE): String = when (key.code) {
    SpecialKeyCode.SHIFT -> "Shift"
    SpecialKeyCode.BACKSPACE -> "Backspace"
    SpecialKeyCode.SPACE -> "Space"
    SpecialKeyCode.ENTER -> enterDescription(enterAction)
    SpecialKeyCode.SYMBOLS -> "Symbols"
    SpecialKeyCode.LETTERS -> "Letters"
    SpecialKeyCode.EXTENSIONS -> "Emoji and extensions"
    SpecialKeyCode.SETTINGS -> "Settings"
    else -> key.label
}

private fun handleGestureEvent(
    event: GestureEvent?,
    viewModel: KeyboardViewModel,
    keyLookupByCode: (Int) -> KeyDefinition?,
    showKeyPopup: Boolean,
    swipeRightForSpace: Boolean,
    onShowAccentPopup: (KeyDefinition?) -> Unit,
    onPreviewKey: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    feedback: KeyboardFeedback,
) {
    when (event) {
        is GestureEvent.KeyTap -> {
            onShowAccentPopup(null)
            if (event.keyCode != 0) {
                feedback.onKeyPress()
                if (event.keyCode == SpecialKeyCode.SETTINGS) {
                    // Opens Settings directly, same as long-pressing the emoji/extensions key
                    // does — intercepted here rather than routed through KeyboardViewModel.
                    // onKeyTap(), which has no concept of "open the host Activity" (that
                    // callback lives at the UI layer, passed down from OmakeyInputMethodService).
                    onOpenSettings()
                } else {
                    onPreviewKey(event.keyCode)
                    viewModel.onKeyTap(event.keyCode)
                }
            }
        }
        is GestureEvent.Swipe -> {
            // Off by default (see GestureSettings.swipeRightForSpace) — a disabled swipe-right is
            // a full no-op, not just a suppressed action, so it doesn't fire haptic/popup-dismiss
            // feedback for a gesture that visibly did nothing.
            if (event.direction == SwipeDirection.RIGHT && !swipeRightForSpace) return
            // A swipe-left starting on the spacebar is reserved exclusively for the long-press-
            // and-drag cursor-move gesture (see KeyGrid's `cursorDragActive`) — without this, a
            // fast left-drag on the spacebar could win the race and delete a word before the
            // 400ms long-press timer had a chance to engage cursor-drag mode instead.
            if (event.direction == SwipeDirection.LEFT && event.downKeyCode == SpecialKeyCode.SPACE) return
            onShowAccentPopup(null)
            feedback.onSwipe()
            when (event.direction) {
                SwipeDirection.LEFT -> viewModel.onSwipeLeft()
                SwipeDirection.RIGHT -> viewModel.onSwipeRight()
                SwipeDirection.UP -> viewModel.onSwipeUp()
                SwipeDirection.DOWN -> viewModel.onSwipeDown()
            }
        }
        is GestureEvent.KeyLongPress -> {
            val key = keyLookupByCode(event.keyCode)
            when {
                key?.code == SpecialKeyCode.EXTENSIONS -> onOpenSettings()
                showKeyPopup && key != null && key.popupChars.isNotEmpty() -> onShowAccentPopup(key)
                event.keyCode != 0 -> {
                    // No accent variants (or popups disabled in Settings): a held key still types
                    // its base character rather than silently doing nothing once the tap-resolution
                    // window has passed.
                    feedback.onKeyPress()
                    onPreviewKey(event.keyCode)
                    viewModel.onKeyTap(event.keyCode)
                }
            }
        }
        GestureEvent.GestureCancelled, null -> Unit
    }
}

// internal (not private) so the Settings height editor can render real keys instead of a mock
// preview — visual accuracy matters there, and this composable has no dependency on the IME's
// InputConnection/prediction/extension graph, only on layout+theme data, so it's safe to reuse
// outside the keyboard service.
@Composable
internal fun KeyRowView(
    rowKeys: List<KeyDefinition>,
    rowHeightDp: Int,
    shiftOn: Boolean,
    theme: OmakeyTheme,
    accessibleMode: Boolean,
    showKeyBackgrounds: Boolean,
    isHomeRow: Boolean,
    onKeyTap: (Int) -> Unit,
    ancestorCoordinates: () -> androidx.compose.ui.layout.LayoutCoordinates?,
    onBoundsMeasured: (Int, KeyDefinition, Rect) -> Unit,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    pressedKeyCode: Int? = null,
    capsLockOn: Boolean = false,
    enterAction: Int = android.view.inputmethod.EditorInfo.IME_ACTION_NONE,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(rowHeightDp.dp)
            .let { m -> if (isHomeRow) m.background(theme.middleRowStripeColor.toComposeColor()) else m }
            .onGloballyPositioned { coordinates ->
                val ancestor = ancestorCoordinates() ?: return@onGloballyPositioned
                val originInAncestor = ancestor.localPositionOf(coordinates, Offset.Zero)
                val widths = LayoutKeyRow(rowKeys).computeKeyWidthsPx(coordinates.size.width.toFloat())
                var x = originInAncestor.x
                val top = originInAncestor.y
                val bottom = top + coordinates.size.height.toFloat()
                rowKeys.forEachIndexed { index, key ->
                    val w = widths[index]
                    onBoundsMeasured(index, key, Rect(x, top, x + w, bottom))
                    x += w
                }
            },
    ) {
        // Borderless/flat is the default (matches Fleksy's style); showKeyBackgrounds opts back
        // into a boxed-key look for users who prefer it. The spacebar always gets its accent
        // color regardless of this setting, so it stays visually distinguishable either way.
        Row(Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            rowKeys.forEach { key ->
                val label = key.label.let { if (it.length == 1) it.uppercase() else it }
                val fontSize = if (key.label.length == 1) 24.sp else 15.sp
                val isSpace = key.code == SpecialKeyCode.SPACE
                val isCapsLockKey = key.code == SpecialKeyCode.SHIFT && capsLockOn
                val keyBackground = when {
                    isSpace -> theme.spacebarAccentColor.toComposeColor()
                    // Caps lock gets its own persistent highlight, same visual language as a
                    // physical caps-lock LED — distinguishes "locked on" from a plain momentary
                    // press, which only brightens the icon (see isPressed below).
                    isCapsLockKey -> theme.keySpecialBackground.toComposeColor()
                    !showKeyBackgrounds -> Color.Transparent
                    key.keyType == dev.omakey.core.layout.KeyType.SPECIAL -> theme.keySpecialBackground.toComposeColor()
                    else -> theme.keyBackground.toComposeColor()
                }
                val keyDescription = if (key.code == SpecialKeyCode.ENTER) describeKey(key, enterAction) else describeKey(key)
                Box(
                    modifier = Modifier
                        .weight(key.widthWeight)
                        .fillMaxHeight()
                        .let { m ->
                            if (accessibleMode) {
                                m.clickable(onClickLabel = keyDescription) { onKeyTap(key.code) }
                                    .semantics { contentDescription = keyDescription }
                            } else {
                                m.semantics { contentDescription = keyDescription }
                            }
                        }
                        .padding(horizontal = 1.5.dp, vertical = 1.5.dp)
                        .background(keyBackground, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val isActiveShift = key.code == SpecialKeyCode.SHIFT && shiftOn
                    // Fleksy-style treatment: special (non-space) keys sit dimmed by default and
                    // light up to full brightness the moment they're actually held — makes the
                    // letter keys (always full brightness) read as the primary content and the
                    // control keys as secondary, plus gives a clear "yes, I registered your
                    // press" cue for keys like backspace that have no other visual feedback.
                    val isDimmable = key.keyType == dev.omakey.core.layout.KeyType.SPECIAL && !isSpace
                    val isPressed = pressedKeyCode == key.code
                    val tint = when {
                        isActiveShift -> theme.keyBackgroundPressed.toComposeColor()
                        isDimmable && !isPressed -> theme.keyTextColor.toComposeColor().copy(alpha = 0.5f)
                        else -> theme.keyTextColor.toComposeColor()
                    }
                    val icon = keyIcon(key, capsLockOn, enterAction)
                    if (icon != null) {
                        Icon(imageVector = icon, contentDescription = keyDescription, tint = tint, modifier = Modifier.size(20.dp))
                    } else {
                        Text(
                            text = label,
                            color = tint,
                            fontFamily = fontFamily,
                            fontSize = fontSize,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Long-press accent picker. Renders in the strip above the keys (replacing the suggestion strip
 * while active) rather than as a floating popup anchored to the key — simpler to position
 * correctly and avoids Compose Popup offset math relative to a non-window-root coordinate space,
 * at the cost of not visually pointing at the held key. Documented v1 simplification.
 */
@Composable
private fun AccentPickerBar(key: KeyDefinition, theme: OmakeyTheme, onSelect: (Char) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(key.label) + key.popupChars
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(theme.suggestionBarBackground.toComposeColor()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { option.firstOrNull()?.let(onSelect) },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = option, color = theme.keyTextColor.toComposeColor(), fontSize = 18.sp)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable(onClick = onDismiss)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "✕", color = theme.keyTextColor.toComposeColor(), fontSize = 16.sp)
        }
    }
}

private fun tabToPage(tab: dev.omakey.app.keyboard.TopStripTab): Int = when (tab) {
    dev.omakey.app.keyboard.TopStripTab.SUGGESTIONS -> 0
    dev.omakey.app.keyboard.TopStripTab.NUMBERS -> 1
    dev.omakey.app.keyboard.TopStripTab.TOOLS -> 2
}

private fun pageToTab(page: Int): dev.omakey.app.keyboard.TopStripTab = when (page) {
    1 -> dev.omakey.app.keyboard.TopStripTab.NUMBERS
    2 -> dev.omakey.app.keyboard.TopStripTab.TOOLS
    else -> dev.omakey.app.keyboard.TopStripTab.SUGGESTIONS
}

/**
 * The strip above the key grid — one shared slot with three horizontally swipeable pages
 * (Fleksy-style, not tap-driven tabs): word suggestions (default), a numbers row, and
 * text-editing tools + clipboard.
 */
@Composable
private fun TopStrip(
    viewModel: KeyboardViewModel,
    uiState: dev.omakey.app.keyboard.KeyboardUiState,
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    feedback: KeyboardFeedback,
    clipboardModeActive: Boolean = false,
) {
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = if (clipboardModeActive) 2 else tabToPage(uiState.topStripTab),
    ) { 3 }

    // Two-way sync with the ViewModel: a swipe here updates topStripTab (so other logic — e.g.
    // resetForNewField snapping back to Suggestions on a new text field — has one source of
    // truth), and an external change to topStripTab (not currently triggered from elsewhere, but
    // keeps the pager honest if something ever does) scrolls the pager to match. Suppressed
    // entirely in clipboard mode — see below, the pager is locked to the Tools page there anyway.
    if (!clipboardModeActive) {
        androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
            viewModel.selectTopStripTab(pageToTab(pagerState.currentPage))
        }
        androidx.compose.runtime.LaunchedEffect(uiState.topStripTab) {
            val target = tabToPage(uiState.topStripTab)
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        }
    } else {
        // Force (and keep forcing) the Tools page while clipboard mode is active, regardless of
        // whatever tab was last active — entering clipboard mode always shows the dimmed Tools
        // row, never leaves the user on Suggestions/Numbers underneath it.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (pagerState.currentPage != 2) pagerState.scrollToPage(2)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(theme.suggestionBarBackground.toComposeColor()),
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Locked to the current page in clipboard mode — swiping between suggestions/numbers/
            // tools makes no sense while a full-screen clipboard picker is open underneath.
            userScrollEnabled = !clipboardModeActive,
            // Default snap threshold (0.5f — needs a near-full-width flick to commit to the next
            // page) read as "swipe for extension bar is not seamless." Lowered so a shorter,
            // easier swipe still lands on the next/previous page.
            flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                state = pagerState,
                snapPositionalThreshold = 0.2f,
            ),
        ) { page ->
            when (page) {
                0 -> SuggestionsTabContent(
                    suggestions = uiState.suggestions,
                    firstSuggestionKind = uiState.firstSuggestionKind,
                    theme = theme,
                    fontFamily = fontFamily,
                    onAccept = viewModel::onSuggestionAccepted,
                )
                1 -> NumbersTabContent(theme, fontFamily, viewModel, feedback, uiState.layout.id)
                else -> ToolsTabContent(
                    theme, fontFamily, viewModel, feedback, uiState.canUndo, uiState.canRedo,
                    clipboardModeActive = clipboardModeActive,
                )
            }
        }
        // Learn/unlearn confirmation ("hello learnt") — briefly covers whichever page is showing,
        // same visual slot as the suggestions themselves, cleared automatically after ~0.5s (see
        // KeyboardViewModel.showBanner).
        val banner = uiState.bannerMessage
        if (banner != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(theme.suggestionBarBackground.toComposeColor()),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = banner, color = theme.keyTextColor.toComposeColor(), fontFamily = fontFamily, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SuggestionsTabContent(
    suggestions: List<String>,
    firstSuggestionKind: dev.omakey.app.keyboard.SuggestionKind,
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    onAccept: (String) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(suggestions) { index, suggestion ->
            // Quoted, same as Gboard/Fleksy's convention for "this slot is a typo fix," not just
            // another word choice — lets the user tell at a glance that swiping/tapping it
            // corrects a word rather than merely completing or predicting one. Both correction
            // kinds (the word being typed right now, or the one just finished) render the same
            // way here — the difference is only in which text gets edited when accepted.
            val isCorrection = index == 0 && firstSuggestionKind != dev.omakey.app.keyboard.SuggestionKind.PLAIN
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .clickable { onAccept(suggestion) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isCorrection) "“$suggestion”" else suggestion,
                    color = theme.keyTextColor.toComposeColor(),
                    fontFamily = fontFamily,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun ToolsTabContent(
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    viewModel: KeyboardViewModel,
    feedback: KeyboardFeedback,
    canUndo: Boolean,
    canRedo: Boolean,
    // Non-null while the clipboard panel is open — every icon except Clipboard itself renders
    // dimmed and non-interactive, and Clipboard becomes a toggle-back-to-keys button instead of
    // an open action (see item 7: clipboard-mode top bar redesign).
    clipboardModeActive: Boolean = false,
) {
    val editTools = remember(viewModel) {
        listOf(
            Triple("Select all", PhosphorSelectAll, viewModel::onSelectAll),
            Triple("Copy", PhosphorCopy, viewModel::onCopy),
            Triple("Cut", PhosphorCut, viewModel::onCut),
            Triple("Paste", PhosphorPaste, viewModel::onPaste),
        )
    }
    LazyRow(
        Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp),
    ) {
        // Group 1: Undo / Redo.
        item {
            ToolButton(
                description = "Undo",
                icon = PhosphorUndo,
                enabled = canUndo && !clipboardModeActive,
                theme = theme,
                onClick = { feedback.onKeyPress(); viewModel.undo() },
            )
        }
        item {
            ToolButton(
                description = "Redo",
                icon = PhosphorRedo,
                enabled = canRedo && !clipboardModeActive,
                theme = theme,
                onClick = { feedback.onKeyPress(); viewModel.redo() },
            )
        }
        item { ToolGroupDivider(theme) }
        // Group 2: Select all / Copy / Cut / Paste.
        items(editTools) { (description, icon, action) ->
            ToolButton(
                description = description,
                icon = icon,
                enabled = !clipboardModeActive,
                theme = theme,
                onClick = { feedback.onKeyPress(); action() },
            )
        }
        item { ToolGroupDivider(theme) }
        // Group 3: Clipboard — always enabled, since it's also how clipboard mode is exited.
        item {
            ToolButton(
                description = if (clipboardModeActive) "Close clipboard" else "Clipboard",
                icon = PhosphorClipboardHistory,
                enabled = true,
                theme = theme,
                onClick = {
                    feedback.onKeyPress()
                    if (clipboardModeActive) viewModel.extensionHost.close() else viewModel.selectExtension("builtin.clipboard")
                },
            )
        }
    }
}

@Composable
private fun ToolGroupDivider(theme: OmakeyTheme) {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .fillMaxHeight(0.5f)
            .background(theme.middleRowStripeColor.toComposeColor()),
    )
}

@Composable
private fun ToolButton(description: String, icon: ImageVector, enabled: Boolean, theme: OmakeyTheme, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .let { m -> if (enabled) m.clickable(onClick = onClick) else m }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val tint = theme.keyTextColor.toComposeColor().let { if (enabled) it else it.copy(alpha = 0.35f) }
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

// Reuses KeyRowView (the exact same composable the main key grid renders) instead of a bespoke
// Box+Text row, so number keys get pixel-identical sizing/font/padding — and the same Fleksy
// dim/press-brighten treatment — as every other key, rather than the smaller mismatched look a
// hand-rolled version drifted into. `accessibleMode = true` is what makes each key directly
// `.clickable` here (KeyRowView normally leaves hit-testing to the main key grid's own gesture
// loop, which this standalone strip isn't part of — see KeyRowView's own doc comment).
@Composable
private fun NumbersTabContent(
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    viewModel: KeyboardViewModel,
    feedback: KeyboardFeedback,
    currentLayoutId: String,
) {
    val noOpAncestor: () -> androidx.compose.ui.layout.LayoutCoordinates? = remember { { null } }
    // Digits are already the Symbols grid's own first row, so repeating them here while a Symbols
    // layout is active is redundant — extra special characters are more useful in that slot,
    // reverting to plain digits the instant the user switches back to letters.
    val rowKeys = if (currentLayoutId == Layouts.Symbols1.id || currentLayoutId == Layouts.Symbols2.id) {
        Layouts.SymbolsExtraRow.keys
    } else {
        Layouts.NumberRow.keys
    }
    Box(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 4.dp)) {
        KeyRowView(
            rowKeys = rowKeys,
            rowHeightDp = 44,
            shiftOn = false,
            theme = theme,
            accessibleMode = true,
            showKeyBackgrounds = false,
            isHomeRow = false,
            onKeyTap = { code -> feedback.onKeyPress(); viewModel.onKeyTap(code) },
            ancestorCoordinates = noOpAncestor,
            onBoundsMeasured = { _, _, _ -> },
            fontFamily = fontFamily,
        )
    }
}

@Composable
private fun ExtensionPanelSlot(viewModel: KeyboardViewModel, heightDp: Int, showHeaderRow: Boolean = true) {
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
        Column(
            Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .background(uiState.theme.suggestionBarBackground.toComposeColor()),
        ) {
            if (showHeaderRow) {
                Row(Modifier.fillMaxWidth().height(36.dp)) {
                    allExtensions.forEach { ext ->
                        val glyph = (ext.icon as? dev.omakey.extapi.ExtensionIcon.Emoji)?.glyph ?: "•"
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f)
                                .clickable { viewModel.selectExtension(ext.id) }
                                .background(
                                    if (ext.id == activeId) {
                                        uiState.theme.keySpecialBackground.toComposeColor()
                                    } else {
                                        Color.Transparent
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = glyph, fontSize = 18.sp)
                        }
                    }
                    // Returns to the normal keyboard — lives in the panel's own header row rather
                    // than needing to double up with the ?123 key at the bottom, which used to be
                    // the only way back and sat awkwardly close to the system nav area.
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clickable { viewModel.extensionHost.close() }
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
