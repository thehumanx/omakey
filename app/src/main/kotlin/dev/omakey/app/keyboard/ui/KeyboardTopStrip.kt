package dev.omakey.app.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import dev.omakey.core.icons.PhosphorGlobe
import dev.omakey.core.icons.PhosphorSmiley
import dev.omakey.core.icons.PhosphorPaste
import dev.omakey.core.icons.PhosphorQuickAccess
import dev.omakey.core.icons.PhosphorRedo
import dev.omakey.core.icons.PhosphorSelectAll
import dev.omakey.core.icons.PhosphorUndo
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.layout.Layouts
import dev.omakey.core.layout.withDigits
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
 * (Fleksy-style, not tap-driven tabs):
 *  1. **Suggestion bar** — quick access on the left, suggested words and emoji in the middle, and
 *     the language button (or the emoji button, if the user swapped the two) on the right.
 *  2. **Numbers.**
 *  3. **Text tools** — undo/redo, select/copy/cut/paste, clipboard.
 *
 * The quick-access and language buttons live on the suggestion page only, not pinned across all
 * three: the numbers and tools pages need the full width, and the suggestion page is the one the
 * keyboard sits on by default.
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

    // Two-way sync with the ViewModel: a swipe here updates topStripTab (persisted, so the strip
    // reopens on the page it was left on), and an external change scrolls the pager to match.
    // Suppressed in clipboard mode, where the pager is locked to the tools page.
    if (!clipboardModeActive) {
        androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
            viewModel.selectTopStripTab(pageToTab(pagerState.currentPage))
        }
        androidx.compose.runtime.LaunchedEffect(uiState.topStripTab) {
            val target = tabToPage(uiState.topStripTab)
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        }
    } else {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (pagerState.currentPage != 2) pagerState.scrollToPage(2)
        }
    }

    val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            // Grid mode fills with keyboardBackground (the single "Background" field every grid
            // cell uses), not suggestionBarBackground, so an empty strip reads the same as a full one.
            .background(if (isGridMode) theme.keyboardBackground.toComposeColor() else theme.suggestionBarBackground.toComposeColor())
            // Skips the bottom edge: KeyGrid sits flush below and already borders its own top, and
            // drawing both doubled the seam (real bug: "the border above QWERTY is thicker").
            .let { m -> if (isGridMode) m.gridBorderExceptBottom(theme.gridBorderColor.toComposeColor(), theme.gridBorderWidth.toDp()) else m },
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !clipboardModeActive,
            // The default 0.5 threshold needed a near-full-width flick ("not seamless").
            flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                state = pagerState,
                snapPositionalThreshold = 0.2f,
            ),
        ) { page ->
            // Same "Show key backgrounds" toggle the letter grid uses, so the strip's controls stay
            // legible on a theme whose suggestion-bar and text colours are close.
            val showKeyBackgrounds = uiState.layoutSettings.showKeyBackgrounds
            when (page) {
                0 -> SuggestionBar(viewModel, uiState, theme, fontFamily, feedback, quickAccessOpen, showKeyBackgrounds)
                1 -> NumbersTabContent(theme, fontFamily, viewModel, feedback, uiState.layout.id, uiState.digits, showKeyBackgrounds)
                else -> ToolsTabContent(
                    theme, fontFamily, viewModel, feedback, uiState.canUndo, uiState.canRedo,
                    clipboardModeActive = clipboardModeActive,
                    showKeyBackgrounds = showKeyBackgrounds,
                )
            }
        }
    }
}

/** Page one of the strip: quick access, then suggestions, then the language (or emoji) button. */
@Composable
private fun SuggestionBar(
    viewModel: KeyboardViewModel,
    uiState: dev.omakey.app.keyboard.KeyboardUiState,
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    feedback: KeyboardFeedback,
    quickAccessOpen: Boolean,
    showKeyBackgrounds: Boolean,
) {
    val multilingual = uiState.languages.size > 1
    val swapped = uiState.layoutSettings.swapEmojiAndLanguage
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        StripButton(
            theme = theme,
            active = quickAccessOpen,
            description = if (quickAccessOpen) "Close quick access" else "Quick access",
            onClick = { feedback.onKeyPress(); viewModel.toggleQuickAccess() },
        ) { tint -> Icon(PhosphorQuickAccess, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
        // In Grid mode the suggestion area draws its own right edge. The suggestion cells draw theirs
        // too, but with no suggestions (or only emoji, which don't fill the width) nothing did, and
        // the button on the right lost its left border (real bug). Both edges fall on the same
        // pixels when cells are present, so it doesn't thicken.
        val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
        Box(
            Modifier.weight(1f).fillMaxHeight().let { m ->
                if (isGridMode && multilingual) {
                    m.gridCellBorder(theme.gridBorderColor.toComposeColor(), theme.gridBorderWidth.toDp(), includeBottom = false)
                } else {
                    m
                }
            },
        ) {
            SuggestionsTabContent(
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
        }
        // With one language there is no language button, and the emoji key stays in the bottom row
        // whichever way round the setting is, so there is nothing to put here.
        if (multilingual && !swapped) {
            StripButton(
                theme = theme,
                active = uiState.languagePickerOpen,
                description = "Switch language",
                onClick = { feedback.onKeyPress(); viewModel.nextLanguage() },
                onLongClick = { feedback.onKeyPress(); viewModel.openLanguagePicker() },
            ) { tint -> Icon(PhosphorGlobe, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
        } else if (multilingual) {
            StripButton(
                theme = theme,
                active = uiState.activeExtensionId != null,
                description = "Emoji and extensions",
                onClick = { feedback.onKeyPress(); viewModel.onKeyTap(dev.omakey.core.layout.SpecialKeyCode.EXTENSIONS) },
            ) { tint -> Icon(PhosphorSmiley, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
        }
    }
}

/**
 * A square icon button at either end of the suggestion bar. Filled with the theme's accent while
 * [active] (its panel is open), so it reads as a toggle — tapping it again closes the panel.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StripButton(
    theme: OmakeyTheme,
    active: Boolean,
    description: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable (tint: androidx.compose.ui.graphics.Color) -> Unit,
) {
    val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val background = when {
        active -> theme.accent
        isPressed -> theme.keyBackgroundPressed
        isGridMode -> theme.keyboardBackground
        else -> null
    }
    val tint = when {
        active -> theme.onAccent
        background != null -> theme.labelOn(background)
        else -> theme.keyTextColor
    }.toComposeColor()
    Box(
        Modifier
            .fillMaxHeight()
            .width(44.dp)
            .padding(if (isGridMode) 0.dp else 6.dp)
            .let { m ->
                when {
                    isGridMode -> m.background(background!!.toComposeColor())
                        .gridCellBorder(theme.gridBorderColor.toComposeColor(), theme.gridBorderWidth.toDp(), includeBottom = false)
                    background != null -> m.background(background.toComposeColor(), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    else -> m
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content(tint) }
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

    // A plain Row with the words sharing the width equally, not a scrolling list: at most three
    // words (SUGGESTION_LIMIT) fit, and fixed slots put each candidate in the same place every time.
    Row(
        Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = if (isGridMode) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        suggestions.forEachIndexed { index, suggestion ->
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
                    .weight(1f)
                    .let { m -> if (isGridMode) m.fillMaxHeight() else m.padding(horizontal = 2.dp) }
                    .clickable(
                        interactionSource = interactionSource,
                        // Grid mode's own solid press-fill (below) already gives clear feedback —
                        // a ripple on top of that reads as a redundant second effect.
                        indication = null,
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
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                        } else if (showKeyBackgrounds) {
                            // Same "Show key backgrounds" toggle the main key grid uses —
                            // keySpecialBackground (not keyBackground) since a suggestion chip is
                            // a control, not a literal character key, matching how backspace/
                            // shift/etc. render when this toggle is on.
                            m.background(
                                if (isPressed) theme.keyBackgroundPressed.toComposeColor() else theme.keySpecialBackground.toComposeColor(),
                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            ).padding(horizontal = 4.dp, vertical = 6.dp)
                        } else {
                            // Borderless, but still fills with the key tap colour while held.
                            m.background(if (isPressed) theme.keyBackgroundPressed.toComposeColor() else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 4.dp, vertical = 6.dp)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isCorrection) "“$suggestion”" else suggestion,
                    color = itemColor,
                    fontFamily = fontFamily,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        // Independent of the word suggestions above — see KeyboardUiState.emojiSuggestions's own
        // doc — a plain, un-highlighted row of emoji chips that ride along at the end, tapping one
        // just inserts it rather than replacing/cycling anything.
        emojiSuggestions.forEach { emoji ->
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            Box(
                Modifier
                    .let { m -> if (isGridMode) m.fillMaxHeight() else m.padding(horizontal = 4.dp) }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
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
                            // Borderless, but still fills with the key tap colour while held.
                            m.background(if (isPressed) theme.keyBackgroundPressed.toComposeColor() else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp)
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
                        indication = null,
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
                    // Borderless, but still fills with the key tap colour while held.
                    m.background(if (isPressed) theme.keyBackgroundPressed.toComposeColor() else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
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
    digits: String,
    showKeyBackgrounds: Boolean = false,
) {
    val noOpAncestor: () -> androidx.compose.ui.layout.LayoutCoordinates? = remember { { null } }
    // Digits are already the Symbols grid's own first row, so repeating them here while a Symbols
    // layout is active is redundant — extra special characters are more useful in that slot,
    // reverting to plain digits the instant the user switches back to letters.
    val rowKeys = if (currentLayoutId == Layouts.Symbols1.id || currentLayoutId == Layouts.Symbols2.id) {
        Layouts.SymbolsExtraRow.keys
    } else {
        remember(digits) { Layouts.NumberRow.withDigits(digits).keys }
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
