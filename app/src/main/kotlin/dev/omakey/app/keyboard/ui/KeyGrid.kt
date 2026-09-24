package dev.omakey.app.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.app.keyboard.KeyboardFeedback
import dev.omakey.app.keyboard.KeyboardViewModel
import dev.omakey.core.theme.toComposeColor
import dev.omakey.core.theme.toDp
import dev.omakey.core.theme.gridCellBorder
import dev.omakey.core.icons.PhosphorArrowLeft
import dev.omakey.core.icons.PhosphorArrowRight
import dev.omakey.core.icons.PhosphorBackspace
import dev.omakey.core.icons.PhosphorCheck
import dev.omakey.core.icons.PhosphorEnter
import dev.omakey.core.icons.PhosphorGear
import dev.omakey.core.icons.PhosphorGlobe
import dev.omakey.core.icons.PhosphorSearch
import dev.omakey.core.icons.PhosphorSend
import dev.omakey.core.icons.PhosphorShift
import dev.omakey.core.icons.PhosphorShiftLocked
import dev.omakey.core.gesture.GestureEvent
import dev.omakey.core.gesture.GestureStateMachine
import dev.omakey.core.gesture.GestureThresholds
import dev.omakey.core.gesture.KeyHitTester
import dev.omakey.core.gesture.SwipeDirection
import dev.omakey.core.gesture.TouchAction
import dev.omakey.core.gesture.TouchSample
import dev.omakey.core.layout.KeyDefinition
import dev.omakey.core.layout.KeyRow as LayoutKeyRow
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.layout.Layouts
import dev.omakey.core.layout.SpecialKeyCode
import dev.omakey.core.layout.computeKeyWidthsPx
import dev.omakey.core.theme.ColorSpec
import dev.omakey.core.theme.OmakeyTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * The key grid: rows, per-key rendering, the long-press accent popup, and the gesture dispatch that
 * turns a swipe into a KeyboardViewModel call.
 *
 * Split out of KeyboardRoot.kt. This is the hot path — everything here runs per keystroke or per
 * pointer event, so prefer changes that avoid allocating in the gesture loop.
 */

/** Curated overflow tier reached only by dragging past a key's own `popupChars` — not shown until
 * then, so the popup starts small (matching the key's actual accent/punctuation variants) and only
 * grows for someone deliberately dragging further, rather than opening every key's popup at this
 * same wide size. Pulled from the same everyday symbol set as [dev.omakey.core.layout.Layouts.Symbols1]'s
 * top rows. */
internal val EXTENDED_POPUP_SYMBOLS = listOf(
    "@", "#", "$", "_", "&", "-", "+", "(", ")", "/", "*", "\"", ":", ";", "!", "?",
)

@Composable
internal fun KeyGrid(
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
    onPreviewKey: (Int) -> Unit,
    previewKey: Pair<KeyDefinition, Rect>?,
    onOpenSettings: () -> Unit,
    feedback: KeyboardFeedback,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    pressedKeyCode: Int?,
    onPressedKeyChange: (Int?) -> Unit,
) {
    val gestureSettings = uiState.gestureSettings
    // Bumped on every swipe-left word delete — drives [shimmerProgress] below. A plain counter
    // rather than a boolean so repeated deletes in quick succession each restart the animation
    // (a `LaunchedEffect` keyed on an unchanging `true` wouldn't refire).
    var deleteShimmerTrigger by remember { mutableIntStateOf(0) }
    val onSwipeDeleteTriggeredStable = remember { { deleteShimmerTrigger += 1 } }
    // Owned here (not inside KeyRowView) specifically so it survives a layout switch — KeyGrid
    // itself never leaves composition when the layout swaps between ABC/Symbols (only the row
    // contents underneath change), unlike the old per-row `if (isHomeRow) LaunchedEffect(...)`
    // this replaced, which got torn down and remounted whenever `isHomeRow` flipped, causing the
    // shimmer to misfire on every trip back to ABC (real bug, see [KeyRowView]'s own doc on the
    // `shimmerProgress` param for the full explanation).
    val shimmerProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(deleteShimmerTrigger) {
        if (deleteShimmerTrigger == 0) return@LaunchedEffect
        shimmerProgress.snapTo(1f)
        shimmerProgress.animateTo(
            0f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 225,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
        )
    }
    // Long-press-and-drag special-character popup (see AccentDragState's own doc) — this whole
    // touch's "held key" and which of its options is currently under the finger, or null when no
    // long-press-with-popup-chars is in progress. Local to KeyGrid (not hoisted to KeyboardRoot)
    // since it's scoped to a single continuous touch that starts and ends inside this composable's
    // own pointerInput loop.
    var accentDragState by remember { mutableStateOf<AccentDragState?>(null) }
    // Symbol-mode fade (replaces the old floating popup/tooltip): a long-press-with-popupChars
    // now fades the whole key grid from ABC into a full-width symbol strip instead of popping a
    // small box above the held key, and fades back to ABC on release — see [SymbolModeOverlay].
    // [lastAccentDragState] retains the final state through the fade-*out* half of that animation,
    // since [accentDragState] itself already goes back to null the instant the finger lifts (see
    // the UP branch below) but the overlay still needs something to render while animating away.
    var lastAccentDragState by remember { mutableStateOf<AccentDragState?>(null) }
    if (accentDragState != null) lastAccentDragState = accentDragState
    val symbolModeAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(accentDragState != null) {
        symbolModeAlpha.animateTo(
            if (accentDragState != null) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(160),
        )
    }
    val isGridModeOuter = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(gridHeightDp.dp)
            .onGloballyPositioned(onKeysAreaPositioned)
            // Plain, official Modifier.border() directly on this same Box — real bug, fixed: a
            // region-level border on an *ancestor* Column kept getting silently covered by this
            // Box's own descendants' opaque key backgrounds (e.g. the top-left key's own fill
            // sits exactly at this Box's top-left corner, painting over anything an ancestor drew
            // there via drawBehind). border() draws on top of all descendant content regardless
            // of nesting depth, guaranteed, so applying it directly here has no such ambiguity.
            // Its own cells already draw right+bottom only (gridCellBorder), so this border's own
            // right/bottom edges just redundantly overlap those (same pixels, no visible
            // doubling) while its top/left edges are the only real contributor there.
            .let { m -> if (isGridModeOuter) m.border(theme.gridBorderWidth.toDp(), theme.gridBorderColor.toComposeColor()) else m }
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

                            // Swipe-left-and-hold repeats the delete-word gesture, matching held-
                            // BACKSPACE's repeat-delete convention — started the instant the swipe
                            // itself commits (see the MOVE branch below), cancelled the moment the
                            // finger lifts or this touch resolves some other way, same lifecycle as
                            // [longPressJob].
                            var swipeDeleteHoldJob: Job? = null

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
                                    } else if (gestureSettings.showKeyPopup && heldKey != null && heldKey.popupChars.isNotEmpty()) {
                                        // Enters accent-drag mode instead of the generic
                                        // KeyLongPress fallback below — see AccentDragState's doc
                                        // and the MOVE/UP handling further down for how the rest
                                        // of this same touch drives it.
                                        feedback.onKeyPress()
                                        accentDragState = AccentDragState(
                                            key = heldKey,
                                            options = listOf(heldKey.label) + heldKey.popupChars,
                                            highlightedIndex = 0,
                                        )
                                    } else {
                                        handleGestureEvent(
                                            event, viewModel, keyLookupByCode, gestureSettings.swipeRightForSpace,
                                            onPreviewKey, onOpenSettings, feedback,
                                            onSwipeDeleteTriggered = onSwipeDeleteTriggeredStable,
                                        )
                                    }
                                }
                            }

                            var settled = false
                            while (!settled) {
                                val event = awaitPointerEvent()

                                val now = System.currentTimeMillis()

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
                                if (!cursorDragActive && accentDragState == null) {
                                    event.changes.forEach { other ->
                                        if (other.id != down.id && other.changedToDownIgnoreConsumed()) {
                                            val code = hitTester.keyCodeAt(other.position.x, other.position.y)
                                            if (code != 0) {
                                                // The primary touch (down.id) was pressed *first*
                                                // but, unless finalized here, only commits its own
                                                // character at its own eventual UP — which, during a
                                                // fast rollover, can easily land after this second
                                                // key's tap and silently transpose the two (e.g.
                                                // typing "so" fast committing "os"). If the primary
                                                // is still ambiguous (hasn't already resolved into a
                                                // swipe or long-press), a second finger landing
                                                // elsewhere is an unambiguous signal that it was just
                                                // a tap — finalize it as one right now, before this
                                                // key, so commit order matches press order.
                                                if (machine.isPendingTap()) {
                                                    longPressJob.cancel()
                                                    onPressedKeyChange(null)
                                                    val primaryPosition = event.changes.firstOrNull { it.id == down.id }?.position
                                                        ?: down.position
                                                    val primaryEvent = machine.onTouch(
                                                        TouchSample(primaryPosition.x, primaryPosition.y, now, TouchAction.UP),
                                                    )
                                                    handleGestureEvent(
                                                        primaryEvent, viewModel, keyLookupByCode, gestureSettings.swipeRightForSpace,
                                                        onPreviewKey, onOpenSettings, feedback,
                                                        onSwipeDeleteTriggered = onSwipeDeleteTriggeredStable,
                                                    )
                                                }
                                                feedback.onKeyPress()
                                                onPreviewKey(code)
                                                viewModel.onKeyTap(code)
                                            }
                                        }
                                    }
                                }

                                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                                val dragState = accentDragState
                                if (!change.pressed) {
                                    longPressJob.cancel()
                                    swipeDeleteHoldJob?.cancel()
                                    onPressedKeyChange(null)
                                    if (cursorDragActive) {
                                        settled = true
                                    } else if (dragState != null) {
                                        // Commits whichever option the finger is currently over —
                                        // index 0 (the base letter) if the finger never left the
                                        // key, exactly like a plain long-press-then-release with no
                                        // popup would type the base character.
                                        dragState.options.getOrNull(dragState.highlightedIndex)
                                            ?.let { viewModel.onAccentSelected(it) }
                                        accentDragState = null
                                        machine.onTouch(TouchSample(change.position.x, change.position.y, now, TouchAction.UP))
                                        settled = true
                                    } else {
                                        val gestureEvent = machine.onTouch(
                                            TouchSample(change.position.x, change.position.y, now, TouchAction.UP),
                                        )
                                        handleGestureEvent(
                                            gestureEvent, viewModel, keyLookupByCode, gestureSettings.swipeRightForSpace,
                                            onPreviewKey, onOpenSettings, feedback,
                                            onSwipeDeleteTriggered = onSwipeDeleteTriggeredStable,
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
                                } else if (dragState != null) {
                                    // Distance from the touch's *down* position (not the held
                                    // key's own on-screen bounds/center), and unsigned — either
                                    // direction advances the index equally. Real bug, fixed: this
                                    // used to be signed distance rightward from the held key's own
                                    // center, which meant a key sitting near the right edge of the
                                    // row (P, L, M) had nowhere on-screen to drag *into* — there
                                    // was no room to the right, and dragging left just clamped back
                                    // to index 0. Since the popup itself no longer visually anchors
                                    // to the held key either (see SymbolModeOverlay, a full-width
                                    // fade over the whole grid), there's no reason the selection
                                    // math still has to — any key can now be reached by dragging in
                                    // whichever direction actually has screen room, symmetric cell
                                    // width for the whole keyboard's key spacing.
                                    val cellWidthPx = 40.dp.toPx()
                                    val distancePx = kotlin.math.abs(change.position.x - down.position.x)
                                    val wantIndex = (distancePx / cellWidthPx).toInt()
                                    var options = dragState.options
                                    if (wantIndex >= options.size) {
                                        // Dragged past the last popup character — "keep
                                        // dragging" reveals more special characters beyond the
                                        // curated per-key set, the same idea as Fleksy's drag-
                                        // into-symbols-mode, scoped here to extending this same
                                        // popup rather than swapping the whole keyboard layout
                                        // underneath the finger.
                                        val extra = EXTENDED_POPUP_SYMBOLS
                                            .filterNot { it in options }
                                            .take(wantIndex - options.size + 1)
                                        options = options + extra
                                    }
                                    val clampedIndex = wantIndex.coerceIn(0, options.size - 1)
                                    if (options !== dragState.options || clampedIndex != dragState.highlightedIndex) {
                                        accentDragState = dragState.copy(options = options, highlightedIndex = clampedIndex)
                                    }
                                } else {
                                    val gestureEvent = machine.onTouch(
                                        TouchSample(change.position.x, change.position.y, now, TouchAction.MOVE),
                                    )
                                    if (gestureEvent != null) longPressJob.cancel()
                                    handleGestureEvent(
                                        gestureEvent, viewModel, keyLookupByCode, gestureSettings.swipeRightForSpace,
                                        onPreviewKey, onOpenSettings, feedback,
                                        onSwipeDeleteTriggered = onSwipeDeleteTriggeredStable,
                                    )
                                    // Swipe commits (and its single delete-word already fired,
                                    // above) the instant the gesture state machine crosses the
                                    // swipe-distance threshold — further MOVEs on this same touch
                                    // are ignored by the machine itself (SWIPE_COMMITTED). Keeping
                                    // the finger down past that point without lifting is "hold to
                                    // keep deleting," same convention as held-BACKSPACE.
                                    if (gestureEvent is GestureEvent.Swipe &&
                                        gestureEvent.direction == SwipeDirection.LEFT &&
                                        gestureEvent.downKeyCode != SpecialKeyCode.SPACE
                                    ) {
                                        swipeDeleteHoldJob = scope.launch {
                                            delay(400)
                                            while (isActive) {
                                                feedback.onSwipeDelete()
                                                onSwipeDeleteTriggeredStable()
                                                viewModel.onSwipeLeft()
                                                delay(150)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
    ) {
        val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
        Column(
            Modifier
                .fillMaxWidth()
                // Grid mode is meant to be edge-to-edge — this 4dp gutter (harmless/invisible in
                // Normal mode, which has no border to reveal it) left a visible sliver of
                // unbordered background down both sides of the bordered grid otherwise.
                .let { m -> if (isGridMode) m else m.padding(PaddingValues(horizontal = 4.dp)) }
                // Fades out as symbol mode fades in (see symbolModeAlpha above) — alpha only, not
                // removed from composition, so keyBoundsState (needed by the drag-select math in
                // the pointer loop above, which reads the held key's own bounds throughout) keeps
                // updating underneath the overlay the whole time.
                .alpha(1f - symbolModeAlpha.value),
        ) {
            effectiveRows.forEachIndexed { rowIndex, row ->
                val onBoundsMeasuredStable = remember(rowIndex) {
                    { measured: List<Triple<Int, KeyDefinition, Rect>> ->
                        keyBoundsState.value = keyBoundsState.value + measured.associate { (keyIndex, key, rect) ->
                            rowIndex * 1000 + keyIndex to (key to rect)
                        }
                    }
                }
                KeyRowView(
                    rowKeys = row.keys,
                    rowHeightDp = rowHeightDp,
                    shiftOn = uiState.shiftOn,
                    theme = theme,
                    accessibleMode = accessibleMode,
                    showKeyBackgrounds = layoutSettings.showKeyBackgrounds,
                    isHomeRow = rowIndex == homeRowIndex,
                    homeRowTinted = layoutSettings.showMiddleRowStripe,
                    onKeyTap = onKeyTapStable,
                    ancestorCoordinates = ancestorCoordinatesStable,
                    onBoundsMeasured = onBoundsMeasuredStable,
                    fontFamily = fontFamily,
                    pressedKeyCode = pressedKeyCode,
                    capsLockOn = uiState.capsLockOn,
                    enterAction = uiState.enterAction,
                    shimmerProgress = shimmerProgress.value,
                    alwaysShowUppercaseLetters = layoutSettings.alwaysShowUppercaseLetters,
                )
            }
        }

        // Gated on the render side, not inside onPreviewKeyStable — that lambda is deliberately
        // `remember`ed with no keys (see its own doc, this is what keeps typing from recomposing
        // every key row), so it would otherwise capture a stale first-composition value of this
        // setting instead of staying live if the user changes it from Settings mid-session.
        if (previewKey != null && layoutSettings.showTapPreview) {
            val (key, rect) = previewKey
            val density = androidx.compose.ui.platform.LocalDensity.current
            val bubbleWidthPx = with(density) { 44.dp.toPx() }.coerceAtLeast(rect.width)
            val bubbleHeightPx = with(density) { 52.dp.toPx() }
            val gapPx = with(density) { 6.dp.toPx() }
            val parentWidthPx = ancestorCoordinatesStable()?.size?.width?.toFloat() ?: (rect.right)
            val x = ((rect.left + rect.right) / 2f - bubbleWidthPx / 2f)
                .coerceIn(0f, (parentWidthPx - bubbleWidthPx).coerceAtLeast(0f))
            val y = (rect.top - bubbleHeightPx - gapPx).coerceAtLeast(0f)
            val isGridModeBubble = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
            val bubbleShape = if (isGridModeBubble) {
                androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            } else {
                androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            }
            val bubbleBackground = if (isGridModeBubble) theme.keyBackgroundPressed.toComposeColor() else theme.keySpecialBackground.toComposeColor()
            Box(
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) }
                    .size(with(density) { bubbleWidthPx.toDp() }, with(density) { bubbleHeightPx.toDp() })
                    .background(bubbleBackground, bubbleShape)
                    .let { m ->
                        if (isGridModeBubble) {
                            m.border(theme.gridBorderWidth.toDp(), theme.gridBorderColor.toComposeColor(), bubbleShape)
                        } else {
                            m
                        }
                    },
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

        if (symbolModeAlpha.value > 0f) {
            lastAccentDragState?.let { state ->
                SymbolModeOverlay(
                    state = state,
                    alpha = symbolModeAlpha.value,
                    theme = theme,
                    fontFamily = fontFamily,
                    gridHeightDp = gridHeightDp,
                )
            }
        }
    }
}

/** Full-width symbol-mode overlay for [AccentDragState] — supersedes the old small floating
 * popup/tooltip (real user feedback: "instead of a popup or tooltip, fade into symbol mode").
 * Rendered over the *entire* key grid (not anchored above the held key) and cross-fades against
 * the ABC keys underneath via [alpha], driven by KeyGrid's `symbolModeAlpha` — 0 at rest (this
 * composable isn't even called), fades to 1 across the whole gesture's long-press-triggers-it
 * moment, and back to 0 as the finger lifts, at which point KeyGrid stops calling this entirely.
 * Purely a rendering of [state]; all the actual drag-to-select tracking (which option is under the
 * finger) still lives in KeyGrid's own gesture loop, unchanged — only the visual presentation
 * moved from "small popup pointing at the key" to "the whole keyboard turns into symbol mode." */
@Composable
private fun SymbolModeOverlay(
    state: AccentDragState,
    alpha: Float,
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    gridHeightDp: Int,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(gridHeightDp.dp)
            .alpha(alpha)
            .background(theme.keyboardBackground.toComposeColor()),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val availableWidthPx = with(density) { maxWidth.toPx() }
            // state.options[0] is always the held key's own base label (see AccentDragState's
            // doc) — kept in the underlying list so the drag-distance math and "release without
            // dragging types the base char" behavior in KeyGrid's gesture loop stay unchanged, but
            // not worth a cell here: the user is already looking at that exact key, so redundantly
            // re-showing it in its own popup is just visual noise. Only the special characters
            // past it get a cell.
            val displayOptions = state.options.drop(1)
            // Shrinks cells to fit as more options accumulate (the EXTENDED_POPUP_SYMBOLS overflow
            // tier can push option count well past what a fixed cell width would fit on one row),
            // clamped so cells never get too cramped or absurdly wide with only 2-3 options.
            val cellWidthDp = with(density) {
                (availableWidthPx / displayOptions.size.coerceAtLeast(1)).toDp()
            }.coerceIn(36.dp, 56.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                val isGridModeOverlay = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
                displayOptions.forEachIndexed { rawIndex, option ->
                    val index = rawIndex + 1
                    val isHighlighted = index == state.highlightedIndex
                    // Cells beyond the key's own curated popupChars (the EXTENDED_POPUP_SYMBOLS
                    // overflow tier, reached only by dragging past the curated set) fade in the
                    // first time they're composed instead of appearing instantly — a subtle "this
                    // is a different tier now" cue for the mode shift, rather than a jump cut.
                    // Cells within the curated set (index < baseOptionCount, present from the
                    // moment symbol mode opens) skip the animation entirely.
                    val optionAlpha = if (index >= state.baseOptionCount) {
                        val animatable = remember(option, index) { androidx.compose.animation.core.Animatable(0f) }
                        androidx.compose.runtime.LaunchedEffect(animatable) {
                            animatable.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(200))
                        }
                        animatable.value
                    } else {
                        1f
                    }
                    val cellShape = if (isGridModeOverlay) {
                        androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                    } else {
                        androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                    }
                    Box(
                        modifier = Modifier
                            .width(cellWidthDp)
                            .height(56.dp)
                            .let { m -> if (isGridModeOverlay) m else m.padding(3.dp) }
                            .alpha(optionAlpha)
                            .let {
                                if (isHighlighted) {
                                    it.background(theme.keyBackgroundPressed.toComposeColor(), cellShape)
                                } else {
                                    it.background(theme.keySpecialBackground.toComposeColor(), cellShape)
                                }
                            }
                            .let { m ->
                                if (isGridModeOverlay) {
                                    m.border(theme.gridBorderWidth.toDp(), theme.gridBorderColor.toComposeColor(), cellShape)
                                } else {
                                    m
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option,
                            color = theme.keyTextColor.toComposeColor(),
                            fontFamily = fontFamily,
                            fontSize = 20.sp,
                        )
                    }
                }
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
    SpecialKeyCode.LANGUAGE -> PhosphorGlobe
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
    SpecialKeyCode.ENTER -> enterDescription(enterAction)
    SpecialKeyCode.SYMBOLS -> "Symbols"
    SpecialKeyCode.LETTERS -> "Letters"
    SpecialKeyCode.EXTENSIONS -> "Emoji and extensions"
    SpecialKeyCode.SETTINGS -> "Settings"
    SpecialKeyCode.LANGUAGE -> "Switch language"
    // The spacebar carries the language name while several are enabled; say both.
    SpecialKeyCode.SPACE -> if (key.label.isBlank()) "Space" else "Space, ${key.label}"
    else -> key.label
}

private fun handleGestureEvent(
    event: GestureEvent?,
    viewModel: KeyboardViewModel,
    keyLookupByCode: (Int) -> KeyDefinition?,
    swipeRightForSpace: Boolean,
    onPreviewKey: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    feedback: KeyboardFeedback,
    onSwipeDeleteTriggered: () -> Unit = {},
) {
    when (event) {
        is GestureEvent.KeyTap -> {
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
            if (event.direction == SwipeDirection.LEFT) {
                feedback.onSwipeDelete()
                onSwipeDeleteTriggered()
            } else {
                feedback.onSwipe()
            }
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
                key?.code == SpecialKeyCode.LANGUAGE -> viewModel.openLanguagePicker()
                // A key with popupChars is intercepted earlier, in KeyGrid's own long-press-timer
                // handling, which enters accent-drag mode directly instead of ever emitting this
                // KeyLongPress event for it — so by the time one reaches here, it's guaranteed to
                // be a key with nothing to pop up (or popups disabled in Settings).
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
    // Structural: is this *the* home row of this layout. Deliberately separate from
    // [homeRowTinted], which is the user's "Home row highlight" setting. Conflating the two meant
    // turning the highlight off also silenced the swipe-left delete shimmer below, which is a
    // different feature that merely happens to be drawn on the same row — real bug, fixed.
    isHomeRow: Boolean,
    homeRowTinted: Boolean = true,
    onKeyTap: (Int) -> Unit,
    ancestorCoordinates: () -> androidx.compose.ui.layout.LayoutCoordinates?,
    onBoundsMeasured: (List<Triple<Int, KeyDefinition, Rect>>) -> Unit,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    pressedKeyCode: Int? = null,
    capsLockOn: Boolean = false,
    enterAction: Int = android.view.inputmethod.EditorInfo.IME_ACTION_NONE,
    // Right-to-left gradient shimmer along the home row's top/bottom borders — a quick, purely
    // decorative "something got swept away" cue timed with swipe-left's delete-word gesture (see
    // feedback.onSwipeDelete's matching swoosh sound). 1f = just-triggered (band at the right
    // edge), animates down to 0f (band swept off the left edge); 0 also means "not currently
    // shimmering." Owned and animated by [KeyGrid] (see its own `shimmerProgress`), just drawn
    // here — it used to be owned locally in this composable via its own `remember`d `Animatable`
    // + `LaunchedEffect(deleteShimmerTrigger)`, gated behind `if (isHomeRow)`. That was a real bug
    // (fixed): switching to the Symbols layout makes `isHomeRow` false for every row (no row is
    // "home" there), which unmounted that conditional `LaunchedEffect` entirely; switching back to
    // ABC remounted it fresh, and a `LaunchedEffect` keyed on an already-nonzero trigger value
    // fires immediately just from *entering* composition — so the shimmer visibly replayed on
    // every trip back to ABC, even with no delete in between. Hoisting the animation up to
    // KeyGrid (which never unmounts across a layout switch) keeps its `LaunchedEffect` alive the
    // whole time, so it only ever restarts on a real new trigger.
    // True (default, matches omakey's original look) keeps letter keycaps uppercase no matter
    // what; false switches to the conventional mobile-keyboard behavior — keycaps track actual
    // case (shiftOn/capsLockOn), same as what's about to be typed. See LayoutSettings's doc.
    alwaysShowUppercaseLetters: Boolean = true,
    shimmerProgress: Float = 0f,
    // False only for NumbersTabContent — a single row sitting directly above KeyGrid inside
    // TopStrip, whose own top self-border already owns that shared seam (see gridCellBorder's
    // own doc on includeBottom). Every other caller is a row inside a real multi-row grid, where
    // each row still needs to own the seam to the row below it.
    gridCellBottomBorder: Boolean = true,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(rowHeightDp.dp)
            .let { m -> if (isHomeRow && homeRowTinted) m.background(theme.middleRowStripeColor.toComposeColor()) else m }
            .let { m ->
                if (isHomeRow && shimmerProgress > 0f) {
                    m.then(
                        Modifier.drawWithContent {
                            drawContent()
                            val barHeightPx = 2.dp.toPx()
                            val bandWidth = 0.28f
                            // Gradient's fraction axis runs start(right)->end(left) below, so
                            // fraction = 1 - progress puts the bright band at the right edge when
                            // progress is 1 (just triggered) and sweeps it to the left edge as
                            // progress falls to 0.
                            val bandFraction = 1f - shimmerProgress
                            // spacebarAccentColor is deliberately neutral (same as keyBackground)
                            // on most dark themes/presets unless the user opts into a system
                            // accent color (see OmakeyTheme's own doc on the field) — great for the
                            // spacebar itself, but it made this decorative shimmer nearly invisible
                            // against an equally dark row background (real bug report). Lightened
                            // toward white on dark themes only, so the shimmer stays visible without
                            // touching the spacebar's own neutral-by-default look.
                            val shimmerColor = theme.spacebarAccentColor.toComposeColor().let { base ->
                                if (theme.isDark) {
                                    androidx.compose.ui.graphics.lerp(base, Color.White, 0.55f)
                                } else {
                                    base
                                }
                            }
                            val brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colorStops = arrayOf(
                                    (bandFraction - bandWidth).coerceIn(0f, 1f) to Color.Transparent,
                                    bandFraction.coerceIn(0f, 1f) to shimmerColor,
                                    (bandFraction + bandWidth).coerceIn(0f, 1f) to Color.Transparent,
                                ),
                                start = Offset(size.width, 0f),
                                end = Offset(0f, 0f),
                            )
                            drawRect(
                                brush = brush,
                                topLeft = Offset(0f, 0f),
                                size = androidx.compose.ui.geometry.Size(size.width, barHeightPx),
                            )
                            drawRect(
                                brush = brush,
                                topLeft = Offset(0f, size.height - barHeightPx),
                                size = androidx.compose.ui.geometry.Size(size.width, barHeightPx),
                            )
                        },
                    )
                } else {
                    m
                }
            }
            .onGloballyPositioned { coordinates ->
                val ancestor = ancestorCoordinates() ?: return@onGloballyPositioned
                val originInAncestor = ancestor.localPositionOf(coordinates, Offset.Zero)
                val widths = LayoutKeyRow(rowKeys).computeKeyWidthsPx(coordinates.size.width.toFloat())
                var x = originInAncestor.x
                val top = originInAncestor.y
                val bottom = top + coordinates.size.height.toFloat()
                // Collected into one list and reported in a single onBoundsMeasured call rather
                // than once per key — the caller folds this into keyBoundsState with one map
                // copy per row instead of one per key (was O(keysInRow) separate MutableState
                // writes + map copies per row layout pass, each copy O(current map size); real
                // cost on rotation/keyboard-resize/first show, not per-keystroke, but still
                // avoidable work on slower devices).
                val measured = ArrayList<Triple<Int, KeyDefinition, Rect>>(rowKeys.size)
                rowKeys.forEachIndexed { index, key ->
                    val w = widths[index]
                    measured += Triple(index, key, Rect(x, top, x + w, bottom))
                    x += w
                }
                onBoundsMeasured(measured)
            },
    ) {
        // Borderless/flat is the default (matches Fleksy's style); showKeyBackgrounds opts back
        // into a boxed-key look for users who prefer it. The spacebar always gets its accent
        // color regardless of this setting, so it stays visually distinguishable either way.
        Row(Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            rowKeys.forEach { key ->
                val label = key.label.let {
                    if (it.length != 1) return@let it
                    if (alwaysShowUppercaseLetters || shiftOn || capsLockOn) it.uppercase() else it.lowercase()
                }
                val fontSize = if (key.label.length == 1) 24.sp else 15.sp
                val isSpace = key.code == SpecialKeyCode.SPACE
                val isCapsLockKey = key.code == SpecialKeyCode.SHIFT && capsLockOn
                val isPressed = pressedKeyCode == key.code
                val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
                // Kept as a ColorSpec rather than resolved straight to a Compose Color, because
                // the label colour below has to be chosen against whatever this works out to —
                // see theme.labelOn. Null means the key paints no fill of its own.
                val keyBackgroundSpec = when {
                    // Grid mode's whole point is a solid fill on press, so it takes priority over
                    // every other background rule here (including the spacebar's own accent color
                    // and the "borderless" showKeyBackgrounds toggle, which is a Normal-mode-only
                    // preference — a borderless grid isn't a grid).
                    isGridMode && isPressed -> theme.keyBackgroundPressed
                    isSpace -> theme.spacebarAccentColor
                    // Caps lock gets its own persistent highlight, same visual language as a
                    // physical caps-lock LED — distinguishes "locked on" from a plain momentary
                    // press, which only brightens the icon (see isPressed below). Uses
                    // keyBackgroundPressed (the same accent-ish color isActiveShift's icon tint
                    // and the accent-drag popup's highlighted cell already use), not
                    // keySpecialBackground — on a custom theme, keySpecialBackground is only a
                    // small nudge off the base key color (see buildCustomTheme's smallNudge),
                    // which read as flat, barely-distinguishable "weird grey" instead of a clear
                    // locked-on indicator (real bug report).
                    isCapsLockKey -> theme.keyBackgroundPressed
                    !isGridMode && !showKeyBackgrounds -> null
                    // Grid mode deliberately has only 4 meaningfully distinct colors — background,
                    // border, spacebar accent, and the home-row tint below — not a separate "key
                    // color"/"special key color" on top. Real user feedback: keeping keyBackground
                    // and keySpecialBackground as distinct fields there just meant two theme
                    // settings doing the same visual job (every cell is "boxed" by its border
                    // regardless of key type), for no benefit.
                    isGridMode -> theme.keyboardBackground
                    key.keyType == dev.omakey.core.layout.KeyType.SPECIAL -> theme.keySpecialBackground
                    else -> theme.keyBackground
                }
                val keyBackground = keyBackgroundSpec?.toComposeColor() ?: Color.Transparent
                val keyDescription = if (key.code == SpecialKeyCode.ENTER) describeKey(key, enterAction) else describeKey(key)
                // Grid mode: no gap between cells, square corners, and a hairline border in
                // theme.gridBorderColor — the same single color, at full opacity, on every key
                // regardless of type (shift/backspace/symbols/emoji/enter included). Previously
                // derived two different alpha levels from keyTextColor (dimmer for special keys),
                // which read as an inconsistency/bug rather than an intentional cue — real user
                // feedback, removed.
                val keyShapeForMode = if (isGridMode) {
                    androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                } else {
                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                }
                val gridBorderColor = theme.gridBorderColor.toComposeColor()
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
                        .padding(horizontal = if (isGridMode) 0.dp else 1.5.dp, vertical = if (isGridMode) 0.dp else 1.5.dp)
                        .background(keyBackground, keyShapeForMode)
                        // Home-row tint layered on top of the cell's own opaque fill, not
                        // instead of the row's own background behind it. Real bug, fixed:
                        // middleRowStripeColor previously only ever painted the row's own
                        // container Box, which every key's opaque fill completely covers once
                        // keys have solid backgrounds (Grid mode, or Normal mode's "Key
                        // backgrounds" toggle) — the home-row highlight was invisible in both
                        // cases. middleRowStripeColor is a low-alpha tint by design (see
                        // OmakeyTheme's own preset values), so drawing it as a second background
                        // layer composites correctly over whatever's already filled.
                        .let { m -> if (isGridMode && isHomeRow && homeRowTinted) m.background(theme.middleRowStripeColor.toComposeColor(), keyShapeForMode) else m }
                        .let { m -> if (isGridMode) m.gridCellBorder(gridBorderColor, theme.gridBorderWidth.toDp(), includeBottom = gridCellBottomBorder) else m },
                    contentAlignment = Alignment.Center,
                ) {
                    val isActiveShift = key.code == SpecialKeyCode.SHIFT && shiftOn
                    // Fleksy-style treatment: special (non-space) keys sit dimmed by default and
                    // light up to full brightness the moment they're actually held — makes the
                    // letter keys (always full brightness) read as the primary content and the
                    // control keys as secondary, plus gives a clear "yes, I registered your
                    // press" cue for keys like backspace that have no other visual feedback.
                    val isDimmable = key.keyType == dev.omakey.core.layout.KeyType.SPECIAL && !isSpace
                    // Resolved against whatever this key's background actually turned out to be:
                    // the spacebar, a pressed grid cell and the caps-lock key can all be carrying a
                    // system accent colour that theme.keyTextColor was never chosen against, and
                    // drawing the label in it regardless is how a label ends up invisible. For
                    // every preset this returns keyTextColor unchanged — see OmakeyTheme.labelOn.
                    val labelColor = theme.labelOn(keyBackgroundSpec ?: theme.keyboardBackground).toComposeColor()
                    val tint = when {
                        isActiveShift -> theme.keyBackgroundPressed.toComposeColor()
                        isDimmable && !isPressed -> labelColor.copy(alpha = 0.5f)
                        else -> labelColor
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
