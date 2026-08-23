package dev.omakey.app.keyboard.ui

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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

/** Opacity for every suggestion-strip candidate except the currently-focused one (index 0) —
 * see [SuggestionsTabContent]. */
private const val SUGGESTION_FADED_ALPHA = 0.45f

/** Grid mode's border thickness — same everywhere, cell-to-cell and at the true outer edges.
 *
 * History, briefly: a "double-width outer border" approach (each cell drew a full 4-sided border,
 * adjacent cells doubling up on shared edges, so outer edges needed a manually-matched 2x-width
 * compensating border) kept needing new special cases every time another seam turned up thin or
 * double-thick. Replaced with a single-draw model — every cell ([gridCellBorder]) draws *only* its
 * right and bottom edges, so no seam can double up by construction — plus a region-level top+left
 * "outer edge" border on the wrapping ancestor for the two sides no cell ever draws. That
 * region-level border used `drawBehind`, which turned out to be the wrong tool: a `drawBehind` on
 * an *ancestor* draws before its children, so a child's own full-bounds opaque background (e.g.
 * `TopStrip`'s own `.background()`, or the top-left key's own fill) painted directly over it every
 * time, real bug — reported as "missing with no suggestions on screen," but it was actually always
 * covered regardless of content. `drawWithContent` (paint the border *after* `drawContent()`) was
 * the theoretically-correct fix and should have worked, but didn't reliably show up in practice
 * either, for reasons that weren't worth continuing to chase.
 *
 * Landed on the simplest reliable answer instead: skip the ancestor-level border entirely.
 * `TopStrip`, `KeyGrid`, `ExtensionPanelSlot`'s own Column/header Row, and the emoji panel's
 * bottom bar each call plain, official `Modifier.border()` **directly on themselves** (the exact
 * same element that also owns their own background) — `border()` is guaranteed to draw on top of
 * all of an element's own descendant content regardless of nesting depth, so there's no ordering
 * ambiguity left to get wrong. Each region's own cells still draw right+bottom only, so a region's
 * self-border only *actually* contributes new pixels on its top/left (its right/bottom edges just
 * redundantly overlap the rightmost/bottommost cell's own edge — same pixels, not a visible
 * doubling). The one accepted trade-off: two *different*, adjacent self-bordering regions (e.g.
 * `TopStrip`'s bottom against `KeyGrid`'s top) do genuinely double up at that shared seam, since
 * both are now separately, fully self-bordered — a real but minor, purely cosmetic inconsistency,
 * traded for a border that's actually guaranteed to render.
 *
 * 0.75dp also read as too thin once actually on-device (real user feedback) — bumped to 1.5dp. */
private val GRID_BORDER_WIDTH = 1.5.dp

/** Maps the user's SM/MD/LG choice (`OmakeyTheme.gridBorderWidth`) to an actual stroke width —
 * kept in the render layer, not `core`, since `Dp` is a Compose UI type (see `GridBorderWidth`'s
 * own doc in `OmakeyTheme.kt`). MD matches [GRID_BORDER_WIDTH], the value every border already
 * used before this became user-configurable. */
private fun dev.omakey.core.theme.GridBorderWidth.toDp(): androidx.compose.ui.unit.Dp = when (this) {
    dev.omakey.core.theme.GridBorderWidth.SM -> 1.dp
    dev.omakey.core.theme.GridBorderWidth.MD -> GRID_BORDER_WIDTH
    dev.omakey.core.theme.GridBorderWidth.LG -> 2.5.dp
}

/** [includeBottom] defaults to true (the normal case — a cell inside a multi-row grid needs to
 * own the seam to the row below it, since nothing else will). Pass false for a cell that's the
 * *only* row in its own region and sits directly above another self-bordering region (e.g. a
 * suggestion chip, tool button, or `NumbersTabContent`'s number key — all single-row content
 * inside `TopStrip`, which sits flush above `KeyGrid`) — real bug, fixed: those cells' own bottom
 * edge was an *extra*, uncoordinated contributor at that exact seam on top of `KeyGrid`'s own top
 * self-border, doubling it up specifically wherever a cell/chip/key actually existed in the strip
 * (reported as "the bottom border is thicker when there's a suggestion" — an empty strip has no
 * chips to contribute the extra stroke, so it looked correct only by having nothing there). */
private fun Modifier.gridCellBorder(color: Color, strokeWidth: androidx.compose.ui.unit.Dp = GRID_BORDER_WIDTH, includeBottom: Boolean = true): Modifier = this.drawBehind {
    val strokePx = strokeWidth.toPx()
    val half = strokePx / 2f
    drawLine(color, Offset(size.width - half, 0f), Offset(size.width - half, size.height), strokePx)
    if (includeBottom) drawLine(color, Offset(0f, size.height - half), Offset(size.width, size.height - half), strokePx)
}

/** `TopStrip` sits flush (zero gap) directly above `KeyGrid`, which already self-borders its own
 * top edge — a second full 4-sided border on `TopStrip` would double up at that one shared seam
 * (the exact inconsistency reported after landing the self-border fix). Skips the bottom edge for
 * exactly that reason. Still applied directly on `TopStrip`'s own element (not a separate
 * ancestor) via `drawWithContent`, same "must be the same element that owns the background" lesson
 * as everywhere else here — this one just also needs to leave one side out. */
private fun Modifier.gridBorderExceptBottom(color: Color, strokeWidth: androidx.compose.ui.unit.Dp = GRID_BORDER_WIDTH): Modifier = this.drawWithContent {
    drawContent()
    val strokePx = strokeWidth.toPx()
    val half = strokePx / 2f
    drawLine(color, Offset(half, 0f), Offset(half, size.height), strokePx)
    drawLine(color, Offset(size.width - half, 0f), Offset(size.width - half, size.height), strokePx)
    drawLine(color, Offset(0f, half), Offset(size.width, half), strokePx)
}

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
    // Long-pressing the emoji/extensions key now offers a Settings/Language drag-select menu
    // (see KeyGrid's long-press-timer branch and its accentDragState reuse) instead of opening
    // Settings directly — this is the "Language" option's action, threaded down the same way
    // onOpenSettings already is.
    onOpenLanguageSettings: () -> Unit = {},
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
        { key: KeyDefinition -> feedback.onKeyPress(); dispatchKeyTap(key, viewModel) }
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
            // underneath or flush against the system nav area. Bottom only (real bug, fixed): the
            // plain `.navigationBarsPadding()` this used to be applies whatever sides
            // WindowInsets.navigationBars reports, which on gesture-nav devices includes left/
            // right edge-gesture insets too — invisible in Normal mode (no border to reveal it)
            // but a very visible dark gutter down both sides once Grid mode's bordered cells make
            // "the keyboard isn't actually full-width" obvious.
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
    ) {
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
                        onPreviewKey = onPreviewKeyStable,
                        previewKey = previewKey,
                        onOpenSettings = onOpenSettings,
                        onOpenLanguageSettings = onOpenLanguageSettings,
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

/** Tracks a single continuous long-press-and-drag on a key with `popupChars` (accents/punctuation
 * variants) — see KeyGrid's long-press-timer branch for how this is entered and the MOVE/UP
 * handling right below it for how it's driven and resolved. [options] is `[key.label] +
 * key.popupChars`, extended in place with entries from [EXTENDED_POPUP_SYMBOLS] once the drag
 * goes past the curated set (Fleksy-style "keep dragging for more special characters").
 * [highlightedIndex] is whichever option currently sits under the finger — committed via
 * [dev.omakey.app.keyboard.KeyboardViewModel.onAccentSelected] on release. [baseOptionCount] is
 * `options.size` at creation time (before any extension) — options at/past that index are the
 * extended overflow tier, which `AccentDragPopup` fades in rather than popping in abruptly, so
 * dragging into "more symbols" territory reads as a subtle mode shift, not a jump cut.
 *
 * Also reused, unmodified, for the emoji/extensions key's long-press menu (Settings vs Language)
 * — same drag-to-select interaction and [SymbolModeOverlay] rendering, just with `options` set to
 * `["⚙", "🌐"]` instead of character variants, and the UP branch dispatching to
 * `onOpenSettings`/`onOpenLanguageSettings` instead of `onAccentSelected` when [key]'s code is
 * [SpecialKeyCode.EXTENSIONS] — see KeyGrid's long-press-timer branch and its UP handling. */
private data class AccentDragState(
    val key: KeyDefinition,
    val options: List<String>,
    val highlightedIndex: Int,
    val baseOptionCount: Int = options.size,
)

/** Curated overflow tier reached only by dragging past a key's own `popupChars` — not shown until
 * then, so the popup starts small (matching the key's actual accent/punctuation variants) and only
 * grows for someone deliberately dragging further, rather than opening every key's popup at this
 * same wide size. Pulled from the same everyday symbol set as [dev.omakey.core.layout.Layouts.Symbols1]'s
 * top rows. */
private val EXTENDED_POPUP_SYMBOLS = listOf(
    "@", "#", "$", "_", "&", "-", "+", "(", ")", "/", "*", "\"", ":", ";", "!", "?",
)

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
    onKeyTapStable: (KeyDefinition) -> Unit,
    ancestorCoordinatesStable: () -> androidx.compose.ui.layout.LayoutCoordinates?,
    onKeysAreaPositioned: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit,
    onPreviewKey: (Int) -> Unit,
    previewKey: Pair<KeyDefinition, Rect>?,
    onOpenSettings: () -> Unit,
    onOpenLanguageSettings: () -> Unit,
    feedback: KeyboardFeedback,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    pressedKeyCode: Int?,
    onPressedKeyChange: (Int?) -> Unit,
) {
    val gestureSettings = uiState.gestureSettings
    // Bumped on every swipe-left word delete — drives [shimmerProgress] below. A plain counter
    // rather than a boolean so repeated deletes in quick succession each restart the animation
    // (a `LaunchedEffect` keyed on an unchanging `true` wouldn't refire).
    var deleteShimmerTrigger by remember { mutableStateOf(0) }
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
                                    } else if (heldKey?.code == SpecialKeyCode.EXTENSIONS) {
                                        // Reuses the exact same drag-to-select interaction/overlay
                                        // as an accent popup (see AccentDragState's doc) — "long
                                        // press should show options like any other key long press"
                                        // — but the two options are actions (Settings, Language),
                                        // not characters. Index 0 (finger never leaves the key) is
                                        // Settings, matching what a plain long-press here used to
                                        // do unconditionally, before this menu existed — dragging
                                        // to index 1 reveals Language. See the UP branch below for
                                        // where that distinction actually dispatches, and the MOVE
                                        // branch for why this doesn't grow an EXTENDED_POPUP_SYMBOLS
                                        // overflow tier the way a real accent popup can.
                                        feedback.onKeyPress()
                                        accentDragState = AccentDragState(
                                            key = heldKey,
                                            options = listOf("⚙", "🌐"),
                                            highlightedIndex = 0,
                                        )
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
                                                val rolloverKey = keyLookupByCode(code)
                                                if (rolloverKey != null) dispatchKeyTap(rolloverKey, viewModel) else viewModel.onKeyTap(code)
                                            }
                                        }
                                    }
                                }

                                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                                val dragState = accentDragState
                                if (!change.pressed) {
                                    longPressJob.cancel()
                                    onPressedKeyChange(null)
                                    if (cursorDragActive) {
                                        settled = true
                                    } else if (dragState != null) {
                                        if (dragState.key.code == SpecialKeyCode.EXTENSIONS) {
                                            // See the long-press-timer branch's own doc: index 0
                                            // (no drag) is Settings, index 1 is Language.
                                            if (dragState.highlightedIndex == 1) onOpenLanguageSettings() else onOpenSettings()
                                        } else {
                                            // Commits whichever option the finger is currently over
                                            // — index 0 (the base letter) if the finger never left
                                            // the key, exactly like a plain long-press-then-release
                                            // with no popup would type the base character.
                                            dragState.options.getOrNull(dragState.highlightedIndex)
                                                ?.let { viewModel.onAccentSelected(it) }
                                        }
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
                                    // The emoji-key Settings/Language menu is a fixed 2-option
                                    // action list, not a curated-then-overflow character set — it
                                    // never grows past its own options.size, unlike a real accent
                                    // popup dragging into EXTENDED_POPUP_SYMBOLS below.
                                    if (dragState.key.code != SpecialKeyCode.EXTENSIONS && wantIndex >= options.size) {
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
            // Shrinks cells to fit as more options accumulate (the EXTENDED_POPUP_SYMBOLS overflow
            // tier can push option count well past what a fixed cell width would fit on one row),
            // clamped so cells never get too cramped or absurdly wide with only 2-3 options.
            val cellWidthDp = with(density) {
                (availableWidthPx / state.options.size.coerceAtLeast(1)).toDp()
            }.coerceIn(36.dp, 56.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                val isGridModeOverlay = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
                state.options.forEachIndexed { index, option ->
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
                    val key = keyLookupByCode(event.keyCode)
                    if (key != null) dispatchKeyTap(key, viewModel) else viewModel.onKeyTap(event.keyCode)
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
            // A swipe up/down starting on the period key cycles Fleksy-style through a fixed
            // punctuation sequence (see onPeriodKeySwipe's own doc) instead of the ordinary
            // suggestion-strip cycling every other key's up/down swipe does. English/Symbols'
            // period key is always the literal '.' codepoint (see Layouts.kt's charKey), so this
            // never fires for e.g. Nepali Traditional's danda key, which uses a synthetic code.
            val isPeriodKey = event.downKeyCode == '.'.code
            when (event.direction) {
                SwipeDirection.LEFT -> viewModel.onSwipeLeft()
                SwipeDirection.RIGHT -> viewModel.onSwipeRight()
                SwipeDirection.UP -> if (isPeriodKey) viewModel.onPeriodKeySwipe(forward = false) else viewModel.onSwipeUp()
                SwipeDirection.DOWN -> if (isPeriodKey) viewModel.onPeriodKeySwipe(forward = true) else viewModel.onSwipeDown()
            }
        }
        is GestureEvent.KeyLongPress -> {
            val key = keyLookupByCode(event.keyCode)
            when {
                // A key with popupChars, or the emoji/extensions key, is intercepted earlier, in
                // KeyGrid's own long-press-timer handling (accent-drag mode, or the Settings/
                // Language menu reusing that same mechanism) directly instead of ever emitting
                // this KeyLongPress event for it — so by the time one reaches here, it's
                // guaranteed to be a key with nothing to pop up (or popups disabled in Settings).
                event.keyCode != 0 -> {
                    // No accent variants (or popups disabled in Settings): a held key still types
                    // its base character rather than silently doing nothing once the tap-resolution
                    // window has passed.
                    feedback.onKeyPress()
                    onPreviewKey(event.keyCode)
                    if (key != null) dispatchKeyTap(key, viewModel) else viewModel.onKeyTap(event.keyCode)
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
    onKeyTap: (KeyDefinition) -> Unit,
    ancestorCoordinates: () -> androidx.compose.ui.layout.LayoutCoordinates?,
    onBoundsMeasured: (Int, KeyDefinition, Rect) -> Unit,
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
            .let { m -> if (isHomeRow) m.background(theme.middleRowStripeColor.toComposeColor()) else m }
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
                // A key with its own shiftedText (Nepali Traditional's conjunct keys — see
                // KeyDefinition's doc) shows that exact string while shift is engaged, not a
                // Unicode case transform of the unshifted label — shiftedText is never a case
                // pairing of key.label to begin with (e.g. "q"->त्र vs "Q"->त्त).
                val keyShiftedText = key.shiftedText
                val label = if (keyShiftedText != null) {
                    if (shiftOn || capsLockOn) keyShiftedText else key.label
                } else {
                    key.label.let {
                        if (it.length != 1) return@let it
                        if (alwaysShowUppercaseLetters || shiftOn || capsLockOn) it.uppercase() else it.lowercase()
                    }
                }
                val fontSize = if (key.label.length == 1) 24.sp else 15.sp
                val isSpace = key.code == SpecialKeyCode.SPACE
                val isCapsLockKey = key.code == SpecialKeyCode.SHIFT && capsLockOn
                val isPressed = pressedKeyCode == key.code
                val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
                val keyBackground = when {
                    // Grid mode's whole point is a solid fill on press, so it takes priority over
                    // every other background rule here (including the spacebar's own accent color
                    // and the "borderless" showKeyBackgrounds toggle, which is a Normal-mode-only
                    // preference — a borderless grid isn't a grid).
                    isGridMode && isPressed -> theme.keyBackgroundPressed.toComposeColor()
                    isSpace -> theme.spacebarAccentColor.toComposeColor()
                    // Caps lock gets its own persistent highlight, same visual language as a
                    // physical caps-lock LED — distinguishes "locked on" from a plain momentary
                    // press, which only brightens the icon (see isPressed below). Uses
                    // keyBackgroundPressed (the same accent-ish color isActiveShift's icon tint
                    // and the accent-drag popup's highlighted cell already use), not
                    // keySpecialBackground — on a custom theme, keySpecialBackground is only a
                    // small nudge off the base key color (see buildCustomTheme's smallNudge),
                    // which read as flat, barely-distinguishable "weird grey" instead of a clear
                    // locked-on indicator (real bug report).
                    isCapsLockKey -> theme.keyBackgroundPressed.toComposeColor()
                    !isGridMode && !showKeyBackgrounds -> Color.Transparent
                    // Grid mode deliberately has only 4 meaningfully distinct colors — background,
                    // border, spacebar accent, and the home-row tint below — not a separate "key
                    // color"/"special key color" on top. Real user feedback: keeping keyBackground
                    // and keySpecialBackground as distinct fields there just meant two theme
                    // settings doing the same visual job (every cell is "boxed" by its border
                    // regardless of key type), for no benefit.
                    isGridMode -> theme.keyboardBackground.toComposeColor()
                    key.keyType == dev.omakey.core.layout.KeyType.SPECIAL -> theme.keySpecialBackground.toComposeColor()
                    else -> theme.keyBackground.toComposeColor()
                }
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
                                m.clickable(onClickLabel = keyDescription) { onKeyTap(key) }
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
                        .let { m -> if (isGridMode && isHomeRow) m.background(theme.middleRowStripeColor.toComposeColor(), keyShapeForMode) else m }
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

    val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            // Grid mode fills with keyboardBackground (the same single "Background" field every
            // grid-mode cell fills with — see KeyRowView), not suggestionBarBackground, so the
            // strip reads consistently even with nothing in it (e.g. no suggestions to show)
            // instead of showing through to a separately-configured, possibly very different
            // color underneath the per-chip fills.
            .background(if (isGridMode) theme.keyboardBackground.toComposeColor() else theme.suggestionBarBackground.toComposeColor())
            // Applied directly on this same element as its own background (see GRID_BORDER_WIDTH's
            // doc for why that matters) — but skipping the bottom edge specifically, since
            // KeyGrid sits flush directly below with zero gap and already self-borders its own
            // top edge; a second full border here would double up right at that one seam (real
            // bug, fixed — reported as "the border above QWERTY is thicker than the others").
            .let { m -> if (isGridMode) m.gridBorderExceptBottom(theme.gridBorderColor.toComposeColor(), theme.gridBorderWidth.toDp()) else m },
    ) {
        // Only worth showing once there's actually something to switch *to* — with a single
        // enabled language the button would have no effect, same reasoning as every other
        // "hidden below 2 enabled languages" spot (see LanguageRow's picker in SettingsActivity).
        val showLanguageSwitch = uiState.enabledLanguages.size >= 2
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            // Inset so the switch button (aligned CenterEnd below) never sits on top of scrolling
            // suggestion chips underneath it.
            modifier = Modifier.fillMaxSize().let { m -> if (showLanguageSwitch) m.padding(end = LANGUAGE_SWITCH_WIDTH) else m },
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
                    emojiSuggestions = uiState.emojiSuggestions,
                    firstSuggestionKind = uiState.firstSuggestionKind,
                    activeSuggestionIndex = uiState.activeSuggestionIndex,
                    theme = theme,
                    fontFamily = fontFamily,
                    onAccept = viewModel::onSuggestionAccepted,
                    onAcceptEmoji = viewModel::onEmojiSuggestionAccepted,
                )
                1 -> NumbersTabContent(theme, fontFamily, viewModel, feedback, uiState.layout.id)
                else -> ToolsTabContent(
                    theme, fontFamily, viewModel, feedback, uiState.canUndo, uiState.canRedo,
                    clipboardModeActive = clipboardModeActive,
                )
            }
        }
        if (showLanguageSwitch) {
            LanguageSwitchButton(
                theme = theme,
                isGridMode = isGridMode,
                onClick = {
                    feedback.onKeyPress()
                    viewModel.cycleActiveLanguage()
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

private val LANGUAGE_SWITCH_WIDTH = 44.dp

/** Persistent button anchored to the right edge of the suggestion bar (not a key in the main
 * grid — keeps the bottom row's tuned `widthWeight` geometry, see Layouts.kt's own comments on
 * that, completely untouched) — cycles [KeyboardViewModel.cycleActiveLanguage] on tap. Only shown
 * once 2+ languages are enabled (see [TopStrip]); visible across all three tab pages, not just
 * Suggestions, since it's a persistent affordance rather than page-specific content. */
@Composable
private fun LanguageSwitchButton(
    theme: OmakeyTheme,
    isGridMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(LANGUAGE_SWITCH_WIDTH)
            .let { m -> if (isGridMode) m.gridCellBorder(theme.gridBorderColor.toComposeColor(), theme.gridBorderWidth.toDp(), includeBottom = false) else m }
            .background(theme.keySpecialBackground.toComposeColor())
            .clickable(onClickLabel = "Switch language", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "🌐", fontSize = 20.sp)
    }
}

@Composable
private fun SuggestionsTabContent(
    suggestions: List<String>,
    emojiSuggestions: List<String>,
    firstSuggestionKind: dev.omakey.app.keyboard.SuggestionKind,
    activeSuggestionIndex: Int,
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    onAccept: (String) -> Unit,
    onAcceptEmoji: (String) -> Unit,
) {
    val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
    // -1 means nothing's been cycled yet (via swipe up/down) — treat index 0 as active, same as
    // before cycling existed. Once cycling starts, KeyboardViewModel.applySuggestion() never
    // reorders this list — it only tracks *which* index is currently applied — so the highlighted
    // item must follow that index, not always sit at position 0 (real bug, fixed: swiping to,
    // say, "these" left "this" looking highlighted/quoted the whole time).
    val activeIndex = if (activeSuggestionIndex in suggestions.indices) activeSuggestionIndex else 0
    LazyRow(
        Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = if (isGridMode) 0.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(suggestions) { index, suggestion ->
            val isActive = index == activeIndex
            // Quoted, same as Gboard/Fleksy's convention for "this slot is a typo fix," not just
            // another word choice — lets the user tell at a glance that swiping/tapping it
            // corrects a word rather than merely completing or predicting one. Both correction
            // kinds (the word being typed right now, or the one just finished) render the same
            // way here — the difference is only in which text gets edited when accepted. Follows
            // whichever candidate is currently active (see activeIndex above), not always index 0.
            val isCorrection = isActive && firstSuggestionKind != dev.omakey.app.keyboard.SuggestionKind.PLAIN
            // The currently active candidate — the one swipe-up/down cycling moved to and space
            // would actually commit — renders at full opacity while the rest fade, letting the
            // user see at a glance what's about to be typed as they cycle instead of every
            // candidate looking equally "selected."
            val itemColor = theme.keyTextColor.toComposeColor().let { if (isActive) it else it.copy(alpha = SUGGESTION_FADED_ALPHA) }
            // Tracked so a tap fills the whole cell solid (keyBackgroundPressed), same as tapping
            // a letter key in the main grid — real user feedback: without this, tapping a
            // suggestion/tool/tab cell showed no press feedback at all next to keys that do.
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            Box(
                Modifier
                    .let { m -> if (isGridMode) m.fillMaxHeight() else m.padding(horizontal = 4.dp) }
                    .clickable(
                        interactionSource = interactionSource,
                        // Grid mode's own solid press-fill (below) already gives clear feedback —
                        // a ripple on top of that reads as a redundant second effect.
                        indication = if (isGridMode) null else androidx.compose.foundation.LocalIndication.current,
                    ) { onAccept(suggestion) }
                    .let { m ->
                        // Same grid-cell treatment as KeyRowView: edge-to-edge, square, bordered,
                        // AND filled with keyboardBackground (Grid mode's single "Background"
                        // field — see KeyRowView) — matches the boxed look of the key rows
                        // underneath instead of floating pill-like chips. Real bug, fixed: this had
                        // a border but no background fill of its own, so it stayed transparent and
                        // showed TopStrip's own suggestionBarBackground through instead — on a
                        // custom theme where that differs, every suggestion chip looked like it
                        // was ignoring the theme entirely, while the Numbers row (which reuses
                        // KeyRowView, always filling per cell) looked correct right next to it.
                        if (isGridMode) {
                            m.background(if (isPressed) theme.keyBackgroundPressed.toComposeColor() else theme.keyboardBackground.toComposeColor())
                                // No bottom edge — this chip is the only row inside TopStrip,
                                // which sits flush above KeyGrid; KeyGrid's own top self-border
                                // already owns that seam (see gridCellBorder's includeBottom doc).
                                .gridCellBorder(theme.gridBorderColor.toComposeColor(), theme.gridBorderWidth.toDp(), includeBottom = false)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        } else {
                            m.padding(horizontal = 10.dp, vertical = 6.dp)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isCorrection) "“$suggestion”" else suggestion,
                    color = itemColor,
                    fontFamily = fontFamily,
                    fontSize = 16.sp,
                )
            }
        }
        // Independent of the word suggestions above — see KeyboardUiState.emojiSuggestions's own
        // doc — a plain, un-highlighted row of emoji chips that ride along at the end, tapping one
        // just inserts it rather than replacing/cycling anything.
        items(emojiSuggestions) { emoji ->
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            Box(
                Modifier
                    .let { m -> if (isGridMode) m.fillMaxHeight() else m.padding(horizontal = 4.dp) }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = if (isGridMode) null else androidx.compose.foundation.LocalIndication.current,
                    ) { onAcceptEmoji(emoji) }
                    .let { m ->
                        if (isGridMode) {
                            m.background(if (isPressed) theme.keyBackgroundPressed.toComposeColor() else theme.keyboardBackground.toComposeColor())
                                .gridCellBorder(theme.gridBorderColor.toComposeColor(), theme.gridBorderWidth.toDp(), includeBottom = false)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        } else {
                            m.padding(horizontal = 8.dp, vertical = 6.dp)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = emoji, fontSize = 20.sp)
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
    val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
    if (isGridMode) {
        // Fixed, non-scrolling Row with every tool given equal weight — fills the whole strip
        // width edge-to-edge like every other bordered grid row, instead of a left-aligned
        // scrollable LazyRow leaving empty space on the right when there's fewer tools than fit
        // (real user feedback: this specifically only happened in Grid mode, since Normal mode's
        // floating chips never needed to visually "fill" anything). No group dividers here —
        // ToolGroupDivider already no-ops in Grid mode, so there'd be nothing to fit a slot for
        // anyway.
        data class ToolAction(val description: String, val icon: ImageVector, val enabled: Boolean, val onClick: () -> Unit)
        val actions = buildList {
            add(ToolAction("Undo", PhosphorUndo, canUndo && !clipboardModeActive) { feedback.onKeyPress(); viewModel.undo() })
            add(ToolAction("Redo", PhosphorRedo, canRedo && !clipboardModeActive) { feedback.onKeyPress(); viewModel.redo() })
            editTools.forEach { (description, icon, action) ->
                add(ToolAction(description, icon, !clipboardModeActive) { feedback.onKeyPress(); action() })
            }
            add(
                ToolAction(
                    if (clipboardModeActive) "Close clipboard" else "Clipboard",
                    PhosphorClipboardHistory,
                    true,
                ) {
                    feedback.onKeyPress()
                    if (clipboardModeActive) viewModel.extensionHost.close() else viewModel.selectExtension("builtin.clipboard")
                },
            )
        }
        Row(Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            actions.forEach { action ->
                ToolButton(
                    description = action.description,
                    icon = action.icon,
                    enabled = action.enabled,
                    theme = theme,
                    onClick = action.onClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        return
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
    // A free-floating line sitting inside an already-bordered grid cell reads as a stray mark,
    // not an intentional grouping divider — Grid mode's own per-button borders already separate
    // every tool, including across these group boundaries, so this is redundant there.
    if (dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID) return
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .fillMaxHeight(0.5f)
            .background(theme.middleRowStripeColor.toComposeColor()),
    )
}

@Composable
private fun ToolButton(
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    theme: OmakeyTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier
            .let { m -> if (isGridMode) m.fillMaxHeight() else m.padding(horizontal = 4.dp) }
            .let { m ->
                if (enabled) {
                    m.clickable(
                        interactionSource = interactionSource,
                        // Grid mode's own solid press-fill (below) already gives clear feedback —
                        // a ripple on top of that reads as a redundant second effect.
                        indication = if (isGridMode) null else androidx.compose.foundation.LocalIndication.current,
                        onClick = onClick,
                    )
                } else {
                    m
                }
            }
            .let { m ->
                // Filled with keyboardBackground (Grid mode's single "Background" field), same
                // real-bug fix as SuggestionsTabContent's chips — this used to have only a
                // border, staying transparent over ToolsTabContent's own (potentially very
                // different, on a custom theme) background color. Also fills solid with
                // keyBackgroundPressed while held, matching the main key grid's own press
                // feedback (real user feedback: tapping these showed nothing next to keys that
                // visibly fill on press).
                if (isGridMode) {
                    m.background(if (isPressed) theme.keyBackgroundPressed.toComposeColor() else theme.keyboardBackground.toComposeColor())
                        // Same includeBottom = false reasoning as SuggestionsTabContent's chips —
                        // this button is the only row inside TopStrip.
                        .gridCellBorder(theme.gridBorderColor.toComposeColor(), theme.gridBorderWidth.toDp(), includeBottom = false)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                } else {
                    m.padding(horizontal = 12.dp, vertical = 8.dp)
                }
            }
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
    // reverting to plain digits the instant the user switches back to letters. Nepali Traditional
    // gets its own Devanagari-digit/extra-punctuation counterparts (see NepaliLayouts) rather than
    // falling back to the Latin ones here — the same split Layouts.QwertyEnUS itself uses.
    val isNepaliTraditional = currentLayoutId == dev.omakey.core.layout.NepaliLayouts.Traditional.id
    val rowKeys = when {
        currentLayoutId == Layouts.Symbols1.id || currentLayoutId == Layouts.Symbols2.id -> Layouts.SymbolsExtraRow.keys
        isNepaliTraditional -> dev.omakey.core.layout.NepaliLayouts.NumberRow.keys
        else -> Layouts.NumberRow.keys
    }
    val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
    Box(Modifier.fillMaxWidth().fillMaxHeight().let { m -> if (isGridMode) m else m.padding(horizontal = 4.dp) }) {
        KeyRowView(
            rowKeys = rowKeys,
            rowHeightDp = 44,
            shiftOn = false,
            theme = theme,
            accessibleMode = true,
            showKeyBackgrounds = false,
            isHomeRow = false,
            onKeyTap = { key -> feedback.onKeyPress(); dispatchKeyTap(key, viewModel) },
            ancestorCoordinates = noOpAncestor,
            onBoundsMeasured = { _, _, _ -> },
            fontFamily = fontFamily,
            // This row sits directly above KeyGrid inside TopStrip — see gridCellBottomBorder's
            // own doc on KeyRowView.
            gridCellBottomBorder = false,
        )
    }
}

/** Dispatches a tap on [key] to the ViewModel. Every call site that taps by [KeyDefinition] (not
 * just the main gesture handler in [handleGestureEvent] — [KeyRowView]'s own accessibility click
 * action and the Numbers tab's row both do too) needs this, not a raw `viewModel.onKeyTap(code)`:
 * a key carrying [KeyDefinition.text]/[KeyDefinition.shiftedText] (Nepali Traditional's conjunct
 * keys — see that layout's own doc) has a synthetic, non-codepoint `code` that only
 * [KeyboardViewModel.onCharacterKeyTap] knows how to resolve correctly. */
private fun dispatchKeyTap(key: KeyDefinition, viewModel: KeyboardViewModel) {
    if (key.text != null || key.shiftedText != null) {
        viewModel.onCharacterKeyTap(key)
    } else {
        viewModel.onKeyTap(key.code)
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
