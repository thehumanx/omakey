package dev.omakey.app.keyboard.ui

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

@Composable
fun KeyboardRoot(
    viewModel: KeyboardViewModel,
    accessibilityPreferences: AccessibilityPreferences? = null,
    onHideKeyboard: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    feedback: KeyboardFeedback = NoOpKeyboardFeedback,
) {
    val uiState by viewModel.uiState.collectAsState()
    val theme = uiState.theme
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
        if (uiState.activeExtensionId != null && popupKey == null) {
            // Replaces the suggestion strip AND the key grid entirely — matches how emoji/GIF
            // pickers behave on every mainstream keyboard, instead of stacking above the keys and
            // making the keyboard taller.
            ExtensionPanelSlot(
                viewModel = viewModel,
                heightDp = SUGGESTION_STRIP_HEIGHT_DP + gridHeightDp,
            )
        } else {
            if (popupKey != null) {
                AccentPickerBar(
                    key = popupKey,
                    theme = theme,
                    onSelect = { char ->
                        viewModel.onAccentSelected(char)
                        accentPopupKey = null
                    },
                    onDismiss = { accentPopupKey = null },
                )
            } else {
                TopStrip(
                    viewModel = viewModel,
                    uiState = uiState,
                    theme = theme,
                    fontFamily = fontFamily,
                    feedback = feedback,
                )
            }

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
            )
        }

        // A dedicated strip for dismissing the keyboard (Fleksy-style), rather than relying only
        // on the system's own IME-switcher affordance in the nav area.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clickable(onClick = onHideKeyboard),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "⌄", color = theme.keyTextColor.toComposeColor(), fontSize = 14.sp)
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
                            machine.onTouch(TouchSample(down.position.x, down.position.y, downTime, TouchAction.DOWN))

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
                                    } else {
                                        handleGestureEvent(
                                            event, viewModel, keyLookupByCode, gestureSettings.showKeyPopup,
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

                                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                                val now = System.currentTimeMillis()
                                if (!change.pressed) {
                                    longPressJob.cancel()
                                    val gestureEvent = machine.onTouch(
                                        TouchSample(change.position.x, change.position.y, now, TouchAction.UP),
                                    )
                                    handleGestureEvent(
                                        gestureEvent, viewModel, keyLookupByCode, gestureSettings.showKeyPopup,
                                        onShowAccentPopup, onPreviewKey, onOpenSettings, feedback,
                                    )
                                    settled = true
                                } else {
                                    val gestureEvent = machine.onTouch(
                                        TouchSample(change.position.x, change.position.y, now, TouchAction.MOVE),
                                    )
                                    if (gestureEvent != null) longPressJob.cancel()
                                    handleGestureEvent(
                                        gestureEvent, viewModel, keyLookupByCode, gestureSettings.showKeyPopup,
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

private fun describeKey(key: KeyDefinition): String = when (key.code) {
    SpecialKeyCode.SHIFT -> "Shift"
    SpecialKeyCode.BACKSPACE -> "Backspace"
    SpecialKeyCode.SPACE -> "Space"
    SpecialKeyCode.ENTER -> "Enter"
    SpecialKeyCode.SYMBOLS -> "Symbols"
    SpecialKeyCode.LETTERS -> "Letters"
    SpecialKeyCode.EXTENSIONS -> "Emoji and extensions"
    else -> key.label
}

private fun handleGestureEvent(
    event: GestureEvent?,
    viewModel: KeyboardViewModel,
    keyLookupByCode: (Int) -> KeyDefinition?,
    showKeyPopup: Boolean,
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
                onPreviewKey(event.keyCode)
                viewModel.onKeyTap(event.keyCode)
            }
        }
        is GestureEvent.Swipe -> {
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
                val keyBackground = when {
                    isSpace -> theme.spacebarAccentColor.toComposeColor()
                    !showKeyBackgrounds -> Color.Transparent
                    key.keyType == dev.omakey.core.layout.KeyType.SPECIAL -> theme.keySpecialBackground.toComposeColor()
                    else -> theme.keyBackground.toComposeColor()
                }
                Box(
                    modifier = Modifier
                        .weight(key.widthWeight)
                        .fillMaxHeight()
                        .let { m ->
                            if (accessibleMode) {
                                m.clickable(onClickLabel = describeKey(key)) { onKeyTap(key.code) }
                                    .semantics { contentDescription = describeKey(key) }
                            } else {
                                m.semantics { contentDescription = describeKey(key) }
                            }
                        }
                        .padding(horizontal = 1.5.dp, vertical = 1.5.dp)
                        .background(keyBackground, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val isActiveShift = key.code == SpecialKeyCode.SHIFT && shiftOn
                    Text(
                        text = label,
                        color = if (isActiveShift) {
                            theme.keyBackgroundPressed.toComposeColor()
                        } else {
                            theme.keyTextColor.toComposeColor()
                        },
                        fontFamily = fontFamily,
                        fontSize = fontSize,
                    )
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
    dev.omakey.app.keyboard.TopStripTab.TOOLS -> 1
    dev.omakey.app.keyboard.TopStripTab.NUMBERS -> 2
}

private fun pageToTab(page: Int): dev.omakey.app.keyboard.TopStripTab = when (page) {
    1 -> dev.omakey.app.keyboard.TopStripTab.TOOLS
    2 -> dev.omakey.app.keyboard.TopStripTab.NUMBERS
    else -> dev.omakey.app.keyboard.TopStripTab.SUGGESTIONS
}

/**
 * The strip above the key grid — one shared slot with three horizontally swipeable pages
 * (Fleksy-style, not tap-driven tabs): word suggestions (default), text-editing tools +
 * clipboard, and a numbers row. Small dots at the right edge hint that more pages exist and show
 * which one is active.
 */
@Composable
private fun TopStrip(
    viewModel: KeyboardViewModel,
    uiState: dev.omakey.app.keyboard.KeyboardUiState,
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    feedback: KeyboardFeedback,
) {
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = tabToPage(uiState.topStripTab),
    ) { 3 }

    // Two-way sync with the ViewModel: a swipe here updates topStripTab (so other logic — e.g.
    // resetForNewField snapping back to Suggestions on a new text field — has one source of
    // truth), and an external change to topStripTab (not currently triggered from elsewhere, but
    // keeps the pager honest if something ever does) scrolls the pager to match.
    androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
        viewModel.selectTopStripTab(pageToTab(pagerState.currentPage))
    }
    androidx.compose.runtime.LaunchedEffect(uiState.topStripTab) {
        val target = tabToPage(uiState.topStripTab)
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
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
        ) { page ->
            when (page) {
                0 -> SuggestionsTabContent(
                    suggestions = uiState.suggestions,
                    theme = theme,
                    fontFamily = fontFamily,
                    onAccept = viewModel::onSuggestionAccepted,
                )
                1 -> ToolsTabContent(theme, fontFamily, viewModel, feedback)
                else -> NumbersTabContent(theme, fontFamily, viewModel, feedback)
            }
        }
        Row(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
        ) {
            repeat(3) { index ->
                Box(
                    Modifier
                        .size(5.dp)
                        .background(
                            theme.keyTextColor.toComposeColor().copy(alpha = if (pagerState.currentPage == index) 0.9f else 0.3f),
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                )
            }
        }
    }
}

@Composable
private fun SuggestionsTabContent(
    suggestions: List<String>,
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    onAccept: (String) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(suggestions) { suggestion ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .clickable { onAccept(suggestion) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = suggestion, color = theme.keyTextColor.toComposeColor(), fontFamily = fontFamily, fontSize = 16.sp)
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
) {
    val tools = remember(viewModel) {
        listOf(
            "Select all" to viewModel::onSelectAll,
            "Copy" to viewModel::onCopy,
            "Cut" to viewModel::onCut,
            "Paste" to viewModel::onPaste,
            "Clipboard" to { viewModel.selectExtension("builtin.clipboard") },
        )
    }
    LazyRow(
        Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tools) { (label, action) ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .clickable { feedback.onKeyPress(); action() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = label, color = theme.keyTextColor.toComposeColor(), fontFamily = fontFamily, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun NumbersTabContent(
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    viewModel: KeyboardViewModel,
    feedback: KeyboardFeedback,
) {
    Row(
        Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Layouts.NumberRow.keys.forEach { key ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { feedback.onKeyPress(); viewModel.onKeyTap(key.code) }
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = key.label, color = theme.keyTextColor.toComposeColor(), fontFamily = fontFamily, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun ExtensionPanelSlot(viewModel: KeyboardViewModel, heightDp: Int) {
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
                // Returns to the normal keyboard — lives in the panel's own header row rather than
                // needing to double up with the ?123 key at the bottom, which used to be the only
                // way back and sat awkwardly close to the system nav area.
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
