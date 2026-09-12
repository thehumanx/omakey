package dev.omakey.app.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.app.keyboard.KeyboardFeedback
import dev.omakey.app.keyboard.KeyboardViewModel
import dev.omakey.core.theme.toComposeColor
import dev.omakey.core.theme.toDp
import dev.omakey.core.theme.gridCellBorder
import dev.omakey.core.icons.PhosphorClipboardHistory
import dev.omakey.core.icons.PhosphorCopy
import dev.omakey.core.icons.PhosphorCut
import dev.omakey.core.icons.PhosphorIncognito
import dev.omakey.core.icons.PhosphorPaste
import dev.omakey.core.icons.PhosphorQuickAccess
import dev.omakey.core.icons.PhosphorRedo
import dev.omakey.core.icons.PhosphorSelectAll
import dev.omakey.core.icons.PhosphorUndo
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.layout.Layouts
import dev.omakey.core.theme.OmakeyTheme
import kotlinx.coroutines.isActive

/*
 * The strip above the keys: the suggestions / numbers / tools pager and everything drawn inside it.
 * Split out of KeyboardRoot.kt.
 */

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
internal fun TopStrip(
    viewModel: KeyboardViewModel,
    uiState: dev.omakey.app.keyboard.KeyboardUiState,
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    feedback: KeyboardFeedback,
    clipboardModeActive: Boolean = false,
    quickAccessOpen: Boolean = false,
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
        // Row, not the bare pager it used to be: the quick-access button is pinned at the left and
        // the three swipeable pages take the rest. Pinned rather than being a fourth page, because
        // it must be reachable in one tap from whichever page the user is on — a page you have to
        // swipe to is not quick access. Costs ~44dp of suggestion width, the same trade Gboard makes.
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            QuickAccessButton(theme = theme, viewModel = viewModel, feedback = feedback, active = quickAccessOpen)
            Box(Modifier.weight(1f).fillMaxHeight()) {
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
            // Same "Show key backgrounds" toggle KeyRowView already respects for the main letter
            // grid (LayoutSettings.showKeyBackgrounds) — extended here so the extension bar's own
            // controls (suggestion/emoji chips, number keys, text-editing tool icons) stay legible
            // against a custom theme too, instead of relying purely on text color contrast against
            // suggestionBarBackground. Real user feedback: with a light suggestion-bar background
            // and light key text, this row was unreadable until backgrounds were turned on here too.
            val showKeyBackgrounds = uiState.layoutSettings.showKeyBackgrounds
            when (page) {
                0 -> SuggestionsTabContent(
                    suggestions = uiState.suggestions,
                    emojiSuggestions = uiState.emojiSuggestions,
                    loading = uiState.suggestionsLoading,
                    firstSuggestionKind = uiState.firstSuggestionKind,
                    activeSuggestionIndex = uiState.activeSuggestionIndex,
                    theme = theme,
                    fontFamily = fontFamily,
                    showKeyBackgrounds = showKeyBackgrounds,
                    onAccept = viewModel::onSuggestionAccepted,
                    onAcceptEmoji = viewModel::onEmojiSuggestionAccepted,
                )
                1 -> NumbersTabContent(theme, fontFamily, viewModel, feedback, uiState.layout.id, showKeyBackgrounds)
                else -> ToolsTabContent(
                    theme, fontFamily, viewModel, feedback, uiState.canUndo, uiState.canRedo,
                    incognito = uiState.incognito,
                    clipboardModeActive = clipboardModeActive,
                    showKeyBackgrounds = showKeyBackgrounds,
                )
            }
        }
            }
        }
    }
}

/** The nine-dot button at the left of the top strip. Highlighted while its panel is open, so it
 * reads as a toggle rather than a one-way door — tapping it again is how the panel closes. */
@Composable
private fun QuickAccessButton(
    theme: OmakeyTheme,
    viewModel: KeyboardViewModel,
    feedback: KeyboardFeedback,
    active: Boolean,
) {
    val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val background = if (active || isPressed) theme.keyBackgroundPressed else theme.keySpecialBackground
    Box(
        Modifier
            .fillMaxHeight()
            .width(44.dp)
            .padding(if (isGridMode) 0.dp else 6.dp)
            .let { m ->
                if (isGridMode) {
                    m.background(background.toComposeColor())
                        .gridCellBorder(theme.gridBorderColor.toComposeColor(), theme.gridBorderWidth.toDp(), includeBottom = false)
                } else if (active || isPressed) {
                    m.background(background.toComposeColor(), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                } else {
                    m
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = if (isGridMode) null else androidx.compose.foundation.LocalIndication.current,
            ) { feedback.onKeyPress(); viewModel.toggleQuickAccess() }
            .semantics { contentDescription = if (active) "Close quick access" else "Quick access" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PhosphorQuickAccess,
            contentDescription = null,
            tint = if (active || isGridMode || isPressed) {
                theme.labelOn(background).toComposeColor()
            } else {
                theme.keyTextColor.toComposeColor()
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

// internal (not private) so the theme editor's live preview (SettingsActivity's ThemePreviewMock)
// can render a real extension-bar sample with the exact same chip styling — see that call site's
// own doc for why a static sample beats trying to host a full KeyboardViewModel/TopStrip in
// Settings.
@Composable
internal fun SuggestionsTabContent(
    suggestions: List<String>,
    emojiSuggestions: List<String>,
    /** Model still loading — see [dev.omakey.app.keyboard.KeyboardUiState.suggestionsLoading]. */
    loading: Boolean,
    firstSuggestionKind: dev.omakey.app.keyboard.SuggestionKind,
    activeSuggestionIndex: Int,
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    showKeyBackgrounds: Boolean,
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

    // Only while there is genuinely nothing to show. A cold start that already has suggestions
    // (because the word was typed after loading finished) must not flash a placeholder over them.
    if (loading && suggestions.isEmpty() && emojiSuggestions.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = if (isGridMode) 0.dp else 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "Loading suggestions…",
                // Faded to the same degree as an inactive candidate: this is a status note, not
                // something to read or tap, and it occupies a row the eye is trained to scan.
                color = theme.keyTextColor.toComposeColor().copy(alpha = SUGGESTION_FADED_ALPHA),
                fontFamily = fontFamily,
                maxLines = 1,
            )
        }
        return
    }

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
                        } else if (showKeyBackgrounds) {
                            // Same "Show key backgrounds" toggle the main key grid uses —
                            // keySpecialBackground (not keyBackground) since a suggestion chip is
                            // a control, not a literal character key, matching how backspace/
                            // shift/etc. render when this toggle is on.
                            m.background(
                                if (isPressed) theme.keyBackgroundPressed.toComposeColor() else theme.keySpecialBackground.toComposeColor(),
                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            ).padding(horizontal = 10.dp, vertical = 6.dp)
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
                        } else if (showKeyBackgrounds) {
                            m.background(
                                if (isPressed) theme.keyBackgroundPressed.toComposeColor() else theme.keySpecialBackground.toComposeColor(),
                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            ).padding(horizontal = 8.dp, vertical = 6.dp)
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
    incognito: Boolean,
    // Non-null while the clipboard panel is open — every icon except Clipboard itself renders
    // dimmed and non-interactive, and Clipboard becomes a toggle-back-to-keys button instead of
    // an open action (see item 7: clipboard-mode top bar redesign).
    clipboardModeActive: Boolean = false,
    showKeyBackgrounds: Boolean = false,
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
            // Reachable from the keyboard itself rather than only from Settings: the moment you
            // want it is the moment you are already typing something you'd rather not have
            // remembered, and leaving the field to find a toggle defeats the purpose.
            add(
                ToolAction(
                    if (incognito) "Stop incognito" else "Incognito",
                    PhosphorIncognito,
                    true,
                ) { feedback.onKeyPress(); viewModel.toggleIncognito() },
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
                    showKeyBackgrounds = showKeyBackgrounds,
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
                showKeyBackgrounds = showKeyBackgrounds,
            )
        }
        item {
            ToolButton(
                description = "Redo",
                icon = PhosphorRedo,
                enabled = canRedo && !clipboardModeActive,
                theme = theme,
                onClick = { feedback.onKeyPress(); viewModel.redo() },
                showKeyBackgrounds = showKeyBackgrounds,
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
                showKeyBackgrounds = showKeyBackgrounds,
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
                showKeyBackgrounds = showKeyBackgrounds,
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
    showKeyBackgrounds: Boolean = false,
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
                } else if (showKeyBackgrounds) {
                    // Same "Show key backgrounds" toggle SuggestionsTabContent's chips use —
                    // keySpecialBackground, matching how this is a control icon, not a letter key.
                    m.background(
                        if (isPressed) theme.keyBackgroundPressed.toComposeColor() else theme.keySpecialBackground.toComposeColor(),
                        androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    ).padding(horizontal = 12.dp, vertical = 8.dp)
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
internal fun NumbersTabContent(
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    viewModel: KeyboardViewModel,
    feedback: KeyboardFeedback,
    currentLayoutId: String,
    showKeyBackgrounds: Boolean = false,
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
    val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
    Box(Modifier.fillMaxWidth().fillMaxHeight().let { m -> if (isGridMode) m else m.padding(horizontal = 4.dp) }) {
        KeyRowView(
            rowKeys = rowKeys,
            rowHeightDp = 44,
            shiftOn = false,
            theme = theme,
            accessibleMode = true,
            // Real bug, fixed: this was hardcoded false regardless of the actual "Show key
            // backgrounds" setting — the Numbers tab's own keys never picked up the toggle,
            // unlike the main letter grid.
            showKeyBackgrounds = showKeyBackgrounds,
            isHomeRow = false,
            onKeyTap = { code -> feedback.onKeyPress(); viewModel.onKeyTap(code) },
            ancestorCoordinates = noOpAncestor,
            onBoundsMeasured = {},
            fontFamily = fontFamily,
            // This row sits directly above KeyGrid inside TopStrip — see gridCellBottomBorder's
            // own doc on KeyRowView.
            gridCellBottomBorder = false,
        )
    }
}
