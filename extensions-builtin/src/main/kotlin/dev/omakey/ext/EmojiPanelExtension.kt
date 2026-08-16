package dev.omakey.ext

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.core.icons.PhosphorBackspace
import dev.omakey.core.theme.LocalOmakeyTheme
import dev.omakey.extapi.ExtensionContext
import dev.omakey.extapi.ExtensionHost
import dev.omakey.extapi.ExtensionIcon
import dev.omakey.extapi.OmakeyExtension

private fun dev.omakey.core.theme.ColorSpec.toComposeColor() = Color(argb.toInt())

// Same single-draw border model as KeyboardRoot.kt's identically-named/documented GRID_BORDER_WIDTH
// doc comment (see there for the full history) — every cell draws only its own right+bottom edge
// (so adjacent cells never double up).
private fun dev.omakey.core.theme.GridBorderWidth.toDp(): androidx.compose.ui.unit.Dp = when (this) {
    dev.omakey.core.theme.GridBorderWidth.SM -> 1.dp
    dev.omakey.core.theme.GridBorderWidth.MD -> 1.5.dp
    dev.omakey.core.theme.GridBorderWidth.LG -> 2.5.dp
}

// includeLeft defaults to false — the standard right+bottom-only model already covers the left
// edge via whichever cell sits before it. Backspace is the one exception: it sits right after a
// *scrollable* LazyRow of category tabs, and got its own explicit left edge below rather than
// trust that the last (possibly partially visible) tab's own right edge lines up reliably (real
// bug, reported as "heart and X look like the same box").
private fun Modifier.gridCellBorder(color: Color, strokeWidth: androidx.compose.ui.unit.Dp = 1.5.dp, includeLeft: Boolean = false): Modifier = this.drawBehind {
    val strokePx = strokeWidth.toPx()
    val half = strokePx / 2f
    drawLine(color, androidx.compose.ui.geometry.Offset(size.width - half, 0f), androidx.compose.ui.geometry.Offset(size.width - half, size.height), strokePx)
    drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height - half), androidx.compose.ui.geometry.Offset(size.width, size.height - half), strokePx)
    if (includeLeft) drawLine(color, androidx.compose.ui.geometry.Offset(half, 0f), androidx.compose.ui.geometry.Offset(half, size.height), strokePx)
}

/** Bundled static emoji data, no network. Recently-used emoji are tracked via
 * ExtensionContext.emojiRecents and shown as a synthetic first category. */
class EmojiPanelExtension : OmakeyExtension {
    override val id = "builtin.emoji"
    override val displayName = "Emoji"
    override val icon = ExtensionIcon.Emoji("😊")

    private var extensionContext: ExtensionContext? = null

    override fun onAttach(context: ExtensionContext) {
        extensionContext = context
    }

    override fun onDetach() {
        extensionContext = null
    }

    @Composable
    override fun PanelContent(host: ExtensionHost) {
        val recentsRepository = extensionContext?.emojiRecents
        var recentEmojis by remember { mutableStateOf(recentsRepository?.recent() ?: emptyList()) }
        // Always present as the first category (even when empty) rather than only appearing once
        // something's been used — inserting it into the list later would shift every other
        // category's index out from under `categoryIndex`, which is remembered across recompositions.
        val categories = remember(recentEmojis) {
            listOf(EmojiCategory(icon = "🕒", emoji = recentEmojis)) + EmojiCategories.all
        }
        var categoryIndex by remember { mutableStateOf(0) }
        val category = categories[categoryIndex.coerceIn(0, categories.lastIndex)]
        // Text() with no explicit color falls back to LocalContentColor, which defaults to black
        // outside of a Material theming ancestor — invisible against the dark keyboard background.
        // ExtensionPanelSlot provides LocalOmakeyTheme around every extension's content, so this
        // is always available here.
        val textColor = LocalOmakeyTheme.current.keyTextColor.toComposeColor()
        val theme = LocalOmakeyTheme.current
        val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
        // Theme-editable (see OmakeyTheme.gridBorderColor) — same single color every other
        // grid-mode screen uses, not a locally-derived alpha tint.
        val gridBorderColor = theme.gridBorderColor.toComposeColor()
        var dragAccumX by remember { mutableStateOf(0f) }

        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // No padding in Grid mode — every other grid-mode surface reaches edge-to-edge
                    // (see KeyRowView etc), so this 8dp inset was the one place a bordered grid
                    // still floated away from the panel's true edges instead of matching.
                    .let { m -> if (isGridMode) m else m.padding(8.dp) }
                    // Left/right swipe on the grid itself steps to the previous/next category —
                    // same left="back"/right="forward" convention as the rest of the app's
                    // gesture surface (see GestureStateMachine). Lives on this stable outer Box,
                    // not inside the AnimatedContent below, so the gesture detector doesn't get
                    // torn down and restarted mid-transition. LazyVerticalGrid only scrolls
                    // vertically, so a horizontal drag detector here doesn't fight its own
                    // scrolling.
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragAccumX = 0f },
                            onDragEnd = {
                                if (dragAccumX <= -SWIPE_THRESHOLD_PX) {
                                    categoryIndex = (categoryIndex + 1).coerceAtMost(categories.lastIndex)
                                } else if (dragAccumX >= SWIPE_THRESHOLD_PX) {
                                    categoryIndex = (categoryIndex - 1).coerceAtLeast(0)
                                }
                                dragAccumX = 0f
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            dragAccumX += dragAmount
                        }
                    },
            ) {
                // A short (150ms) slide when the category changes — either by swipe or by tapping
                // a tab below — direction follows the same left="back"/right="forward" convention
                // as the swipe gesture itself, instead of a directionless cross-fade. Kept brief on
                // purpose: this is a keyboard, not a media app, and anything longer would feel
                // laggy mid-typing.
                // Real bug, fixed: LazyVerticalGrid below had no fillMaxSize(), so it sized to
                // its own content (wrap-height) instead of the available slot — a category with
                // few items (especially Recent, which can be nearly empty) reported a much
                // shorter measured height than a full category like Smileys. AnimatedContent's
                // *default* SizeTransform animates the container between those two different
                // sizes, which is what actually produced the "expand/reveal" look switching
                // to/from Recent instead of a clean left/right slide like every other category
                // pair (same root cause as the bottomContentMode stutter documented in
                // KeyboardRoot.kt — different children reporting different measured sizes to an
                // AnimatedContent that never had its size animation disabled). Explicit
                // SizeTransform(clip = true) { snap() } removes it outright, same fix.
                val noSizeAnimation = androidx.compose.animation.SizeTransform(clip = true) { _, _ -> androidx.compose.animation.core.snap() }
                AnimatedContent(
                    targetState = categoryIndex,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally(tween(150)) { it } + fadeIn(tween(150))) togetherWith
                                (slideOutHorizontally(tween(150)) { -it } + fadeOut(tween(150)))
                        } else {
                            (slideInHorizontally(tween(150)) { -it } + fadeIn(tween(150))) togetherWith
                                (slideOutHorizontally(tween(150)) { it } + fadeOut(tween(150)))
                        }.using(noSizeAnimation)
                    },
                    label = "emoji-category",
                ) { index ->
                    val animatedCategory = categories[index]
                    val isEmoticons = animatedCategory === EmojiCategories.Emoticons
                    // Kaomoji are multi-character strings, several times wider than a single
                    // unicode emoji glyph — reusing the emoji grid's fixed 8-column layout wraps
                    // them mid-string. Adaptive columns + a smaller font let each kaomoji claim
                    // only the width it actually needs.
                    LazyVerticalGrid(
                        columns = if (isEmoticons) GridCells.Adaptive(72.dp) else GridCells.Fixed(8),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(animatedCategory.emoji) { emoji ->
                            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            val isPressed by interactionSource.collectIsPressedAsState()
                            // Real bug, fixed: this cell used to size itself to the Text's own
                            // measured height, which varies by glyph — plain emoji, "Special"
                            // category symbols (№, ©, ▶) and kaomoji all fall back to different
                            // system fonts with different line-height metrics, so cells in the
                            // same row came out different heights and their borders didn't line
                            // up. Pinning every cell to the same fixed height (independent of
                            // whatever font the glyph happens to render in) keeps the grid even.
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(if (isEmoticons) 48.dp else 44.dp)
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = if (isGridMode) null else androidx.compose.foundation.LocalIndication.current,
                                    ) {
                                        host.insertText(emoji)
                                        recentsRepository?.recordUse(emoji)
                                        recentEmojis = recentsRepository?.recent() ?: recentEmojis
                                    }
                                    // Filled with keyboardBackground (Grid mode's single
                                    // "Background" field), solid-filling with keyBackgroundPressed
                                    // while held — same real-bug fix and press-feedback parity as
                                    // every other grid-mode cell (see KeyRowView's own doc).
                                    .let { m ->
                                        if (isGridMode) {
                                            m.background(if (isPressed) theme.keyBackgroundPressed.toComposeColor() else theme.keyboardBackground.toComposeColor())
                                                .gridCellBorder(gridBorderColor, theme.gridBorderWidth.toDp())
                                        } else {
                                            m
                                        }
                                    },
                            ) {
                                Text(
                                    text = emoji,
                                    // Real bug, not just the tab row: this Text() had no explicit
                                    // color at all, so every glyph (and the "Special"/#category's
                                    // actual symbols, which — unlike emoji — have no built-in color
                                    // of their own) rendered in the platform default (black),
                                    // invisible against a dark theme.
                                    color = textColor,
                                    fontSize = if (isEmoticons) 14.sp else 26.sp,
                                    maxLines = 1,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

            // ABC returns to the normal keyboard, category icons jump between emoji groups,
            // backspace deletes without needing to leave the panel — matches the standard layout
            // convention (Fleksy, Gboard, etc.) instead of only exposing this via the top tab bar.
            val abcInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val abcIsPressed by abcInteractionSource.collectIsPressedAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(if (isGridMode) theme.keyboardBackground.toComposeColor() else Color.Black.copy(alpha = 0.15f))
                    // Plain border() directly on this same Row as its own background — see
                    // KeyboardRoot.kt's GRID_BORDER_WIDTH doc for why a border on an ancestor
                    // (the old gridRegionOuterEdge) kept getting silently covered instead.
                    .let { m -> if (isGridMode) m.border(theme.gridBorderWidth.toDp(), gridBorderColor) else m },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = abcInteractionSource,
                            indication = if (isGridMode) null else androidx.compose.foundation.LocalIndication.current,
                        ) { host.close() }
                        .let { m ->
                            if (isGridMode) {
                                m.background(if (abcIsPressed) theme.keyBackgroundPressed.toComposeColor() else theme.keyboardBackground.toComposeColor())
                                    .gridCellBorder(gridBorderColor, theme.gridBorderWidth.toDp())
                            } else {
                                m
                            }
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "ABC", color = textColor, fontSize = 14.sp)
                }
                // Scrollable, not weighted — 10 categories (9 Unicode groups + Special
                // characters) no longer fit fixed-width in a single row without squeezing icons
                // down to illegibility on a phone-width keyboard.
                val categoryTabsState = androidx.compose.foundation.lazy.rememberLazyListState()
                // Keeps the active tab scrolled into view when categoryIndex changes via swiping
                // the emoji grid itself, not just tapping a tab directly — real bug, fixed:
                // swiping past whichever categories happened to be off-screen left the indicator
                // row not following along at all.
                androidx.compose.runtime.LaunchedEffect(categoryIndex) {
                    categoryTabsState.animateScrollToItem(categoryIndex.coerceIn(0, categories.lastIndex))
                }
                LazyRow(Modifier.weight(1f).fillMaxHeight(), state = categoryTabsState) {
                    lazyRowItems(categories) { cat ->
                        val isSelected = cat == category
                        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(36.dp)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = if (isGridMode) null else androidx.compose.foundation.LocalIndication.current,
                                ) { categoryIndex = categories.indexOf(cat) }
                                .background(
                                    when {
                                        isGridMode && (isSelected || isPressed) -> theme.keyBackgroundPressed.toComposeColor()
                                        isGridMode -> theme.keyboardBackground.toComposeColor()
                                        isSelected -> Color.White.copy(alpha = 0.12f)
                                        else -> Color.Transparent
                                    },
                                )
                                .let { m -> if (isGridMode) m.gridCellBorder(gridBorderColor, theme.gridBorderWidth.toDp()) else m },
                            contentAlignment = Alignment.Center,
                        ) {
                            // Missing color here made every tab icon (not just "#") default to
                            // black-on-dark and effectively disappear against the panel background.
                            Text(text = cat.icon, color = textColor, fontSize = 18.sp)
                        }
                    }
                }
                run {
                    // Same icon + dimmed-by-default/lit-on-press treatment as the alphabet
                    // keyboard's own backspace key (KeyRowView in KeyboardRoot.kt) — this used to
                    // be a plain "⌫" glyph, visually inconsistent with the rest of the keyboard.
                    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) { extensionContext?.textEditor?.deleteBackward(1) }
                            .let { m ->
                                if (isGridMode) {
                                    m.background(if (isPressed) theme.keyBackgroundPressed.toComposeColor() else theme.keyboardBackground.toComposeColor())
                                        .gridCellBorder(gridBorderColor, theme.gridBorderWidth.toDp(), includeLeft = true)
                                } else {
                                    m
                                }
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = PhosphorBackspace,
                            contentDescription = "Backspace",
                            tint = if (isPressed) textColor else textColor.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val SWIPE_THRESHOLD_PX = 80f
    }
}

private data class EmojiCategory(val icon: String, val emoji: List<String>)

private object EmojiCategories {
    val Smileys = EmojiCategory(
        icon = "😀",
        emoji = listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃",
            "🫠", "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "☺️",
            "😚", "😙", "🥲", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗",
            "🤭", "🫢", "🫣", "🤫", "🤔", "🫡", "🤐", "🤨", "😐", "😑",
            "😶", "🫥", "😶‍🌫️", "😏", "😒", "🙄", "😬", "😮‍💨", "🤥", "🫨",
            "🙂‍↔️", "🙂‍↕️", "😌", "😔", "😪", "🤤", "😴", "🫩", "😷", "🤒",
            "🤕", "🤢", "🤮", "🤧", "🥵", "🥶", "🥴", "😵", "😵‍💫", "🤯",
            "🤠", "🥳", "🥸", "😎", "🤓", "🧐", "😕", "🫤", "😟", "🙁",
            "☹️", "😮", "😯", "😲", "😳", "🥺", "🥹", "😦", "😧", "😨",
            "😰", "😥", "😢", "😭", "😱", "😖", "😣", "😞", "😓", "😩",
            "😫", "🥱", "😤", "😡", "😠", "🤬", "😈", "👿", "💀", "☠️",
            "💩", "🤡", "👹", "👺", "👻", "👽", "👾", "🤖", "😺", "😸",
            "😹", "😻", "😼", "😽", "🙀", "😿", "😾", "🙈", "🙉", "🙊",
            "💌", "💘", "💝", "💖", "💗", "💓", "💞", "💕", "💟", "❣️",
            "💔", "❤️‍🔥", "❤️‍🩹", "❤️", "🩷", "🧡", "💛", "💚", "💙", "🩵",
            "💜", "🤎", "🖤", "🩶", "🤍", "💋", "💯", "💢", "💥", "💫",
            "💦", "💨", "🕳️", "💬", "👁️‍🗨️", "🗨️", "🗯️", "💭", "💤",
        ),
    )
    val People = EmojiCategory(
        icon = "👋",
        emoji = listOf(
            "👋", "🤚", "🖐️", "✋", "🖖", "🫱", "🫲", "🫳", "🫴", "🫷",
            "🫸", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙",
            "👈", "👉", "👆", "🖕", "👇", "☝️", "🫵", "👍", "👎", "✊",
            "👊", "🤛", "🤜", "👏", "🙌", "🫶", "👐", "🤲", "🤝", "🙏",
            "✍️", "💅", "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👂", "🦻",
            "👃", "🧠", "🫀", "🫁", "🦷", "🦴", "👀", "👁️", "👅", "👄",
            "🫦", "👶", "🧒", "👦", "👧", "🧑", "👱", "👨", "🧔", "🧔‍♂️",
            "🧔‍♀️", "👨‍🦰", "👨‍🦱", "👨‍🦳", "👨‍🦲", "👩", "👩‍🦰", "🧑‍🦰", "👩‍🦱", "🧑‍🦱",
            "👩‍🦳", "🧑‍🦳", "👩‍🦲", "🧑‍🦲", "👱‍♀️", "👱‍♂️", "🧓", "👴", "👵", "🙍",
            "🙍‍♂️", "🙍‍♀️", "🙎", "🙎‍♂️", "🙎‍♀️", "🙅", "🙅‍♂️", "🙅‍♀️", "🙆", "🙆‍♂️",
            "🙆‍♀️", "💁", "💁‍♂️", "💁‍♀️", "🙋", "🙋‍♂️", "🙋‍♀️", "🧏", "🧏‍♂️", "🧏‍♀️",
            "🙇", "🙇‍♂️", "🙇‍♀️", "🤦", "🤦‍♂️", "🤦‍♀️", "🤷", "🤷‍♂️", "🤷‍♀️", "🧑‍⚕️",
            "👨‍⚕️", "👩‍⚕️", "🧑‍🎓", "👨‍🎓", "👩‍🎓", "🧑‍🏫", "👨‍🏫", "👩‍🏫", "🧑‍⚖️", "👨‍⚖️",
            "👩‍⚖️", "🧑‍🌾", "👨‍🌾", "👩‍🌾", "🧑‍🍳", "👨‍🍳", "👩‍🍳", "🧑‍🔧", "👨‍🔧", "👩‍🔧",
            "🧑‍🏭", "👨‍🏭", "👩‍🏭", "🧑‍💼", "👨‍💼", "👩‍💼", "🧑‍🔬", "👨‍🔬", "👩‍🔬", "🧑‍💻",
            "👨‍💻", "👩‍💻", "🧑‍🎤", "👨‍🎤", "👩‍🎤", "🧑‍🎨", "👨‍🎨", "👩‍🎨", "🧑‍✈️", "👨‍✈️",
            "👩‍✈️", "🧑‍🚀", "👨‍🚀", "👩‍🚀", "🧑‍🚒", "👨‍🚒", "👩‍🚒", "👮", "👮‍♂️", "👮‍♀️",
            "🕵️", "🕵️‍♂️", "🕵️‍♀️", "💂", "💂‍♂️", "💂‍♀️", "🥷", "👷", "👷‍♂️", "👷‍♀️",
            "🫅", "🤴", "👸", "👳", "👳‍♂️", "👳‍♀️", "👲", "🧕", "🤵", "🤵‍♂️",
            "🤵‍♀️", "👰", "👰‍♂️", "👰‍♀️", "🤰", "🫃", "🫄", "🤱", "👩‍🍼", "👨‍🍼",
            "🧑‍🍼", "👼", "🎅", "🤶", "🧑‍🎄", "🦸", "🦸‍♂️", "🦸‍♀️", "🦹", "🦹‍♂️",
            "🦹‍♀️", "🧙", "🧙‍♂️", "🧙‍♀️", "🧚", "🧚‍♂️", "🧚‍♀️", "🧛", "🧛‍♂️", "🧛‍♀️",
            "🧜", "🧜‍♂️", "🧜‍♀️", "🧝", "🧝‍♂️", "🧝‍♀️", "🧞", "🧞‍♂️", "🧞‍♀️", "🧟",
            "🧟‍♂️", "🧟‍♀️", "🧌", "💆", "💆‍♂️", "💆‍♀️", "💇", "💇‍♂️", "💇‍♀️", "🚶",
            "🚶‍♂️", "🚶‍♀️", "🚶‍➡️", "🚶‍♀️‍➡️", "🚶‍♂️‍➡️", "🧍", "🧍‍♂️", "🧍‍♀️", "🧎", "🧎‍♂️",
            "🧎‍♀️", "🧎‍➡️", "🧎‍♀️‍➡️", "🧎‍♂️‍➡️", "🧑‍🦯", "🧑‍🦯‍➡️", "👨‍🦯", "👨‍🦯‍➡️", "👩‍🦯", "👩‍🦯‍➡️",
            "🧑‍🦼", "🧑‍🦼‍➡️", "👨‍🦼", "👨‍🦼‍➡️", "👩‍🦼", "👩‍🦼‍➡️", "🧑‍🦽", "🧑‍🦽‍➡️", "👨‍🦽", "👨‍🦽‍➡️",
            "👩‍🦽", "👩‍🦽‍➡️", "🏃", "🏃‍♂️", "🏃‍♀️", "🏃‍➡️", "🏃‍♀️‍➡️", "🏃‍♂️‍➡️", "💃", "🕺",
            "🕴️", "👯", "👯‍♂️", "👯‍♀️", "🧖", "🧖‍♂️", "🧖‍♀️", "🧗", "🧗‍♂️", "🧗‍♀️",
            "🤺", "🏇", "⛷️", "🏂", "🏌️", "🏌️‍♂️", "🏌️‍♀️", "🏄", "🏄‍♂️", "🏄‍♀️",
            "🚣", "🚣‍♂️", "🚣‍♀️", "🏊", "🏊‍♂️", "🏊‍♀️", "⛹️", "⛹️‍♂️", "⛹️‍♀️", "🏋️",
            "🏋️‍♂️", "🏋️‍♀️", "🚴", "🚴‍♂️", "🚴‍♀️", "🚵", "🚵‍♂️", "🚵‍♀️", "🤸", "🤸‍♂️",
            "🤸‍♀️", "🤼", "🤼‍♂️", "🤼‍♀️", "🤽", "🤽‍♂️", "🤽‍♀️", "🤾", "🤾‍♂️", "🤾‍♀️",
            "🤹", "🤹‍♂️", "🤹‍♀️", "🧘", "🧘‍♂️", "🧘‍♀️", "🛀", "🛌", "🧑‍🤝‍🧑", "👭",
            "👫", "👬", "💏", "👩‍❤️‍💋‍👨", "👨‍❤️‍💋‍👨", "👩‍❤️‍💋‍👩", "💑", "👩‍❤️‍👨", "👨‍❤️‍👨", "👩‍❤️‍👩",
            "👨‍👩‍👦", "👨‍👩‍👧", "👨‍👩‍👧‍👦", "👨‍👩‍👦‍👦", "👨‍👩‍👧‍👧", "👨‍👨‍👦", "👨‍👨‍👧", "👨‍👨‍👧‍👦", "👨‍👨‍👦‍👦", "👨‍👨‍👧‍👧",
            "👩‍👩‍👦", "👩‍👩‍👧", "👩‍👩‍👧‍👦", "👩‍👩‍👦‍👦", "👩‍👩‍👧‍👧", "👨‍👦", "👨‍👦‍👦", "👨‍👧", "👨‍👧‍👦", "👨‍👧‍👧",
            "👩‍👦", "👩‍👦‍👦", "👩‍👧", "👩‍👧‍👦", "👩‍👧‍👧", "🗣️", "👤", "👥", "🫂", "👪",
            "🧑‍🧑‍🧒", "🧑‍🧑‍🧒‍🧒", "🧑‍🧒", "🧑‍🧒‍🧒", "👣", "🫆",
        ),
    )
    val Animals = EmojiCategory(
        icon = "🐶",
        emoji = listOf(
            "🐵", "🐒", "🦍", "🦧", "🐶", "🐕", "🦮", "🐕‍🦺", "🐩", "🐺",
            "🦊", "🦝", "🐱", "🐈", "🐈‍⬛", "🦁", "🐯", "🐅", "🐆", "🐴",
            "🫎", "🫏", "🐎", "🦄", "🦓", "🦌", "🦬", "🐮", "🐂", "🐃",
            "🐄", "🐷", "🐖", "🐗", "🐽", "🐏", "🐑", "🐐", "🐪", "🐫",
            "🦙", "🦒", "🐘", "🦣", "🦏", "🦛", "🐭", "🐁", "🐀", "🐹",
            "🐰", "🐇", "🐿️", "🦫", "🦔", "🦇", "🐻", "🐻‍❄️", "🐨", "🐼",
            "🦥", "🦦", "🦨", "🦘", "🦡", "🐾", "🦃", "🐔", "🐓", "🐣",
            "🐤", "🐥", "🐦", "🐧", "🕊️", "🦅", "🦆", "🦢", "🦉", "🦤",
            "🪶", "🦩", "🦚", "🦜", "🪽", "🐦‍⬛", "🪿", "🐦‍🔥", "🐸", "🐊",
            "🐢", "🦎", "🐍", "🐲", "🐉", "🦕", "🦖", "🐳", "🐋", "🐬",
            "🦭", "🐟", "🐠", "🐡", "🦈", "🐙", "🐚", "🪸", "🪼", "🦀",
            "🦞", "🦐", "🦑", "🦪", "🐌", "🦋", "🐛", "🐜", "🐝", "🪲",
            "🐞", "🦗", "🪳", "🕷️", "🕸️", "🦂", "🦟", "🪰", "🪱", "🦠",
            "💐", "🌸", "💮", "🪷", "🏵️", "🌹", "🥀", "🌺", "🌻", "🌼",
            "🌷", "🪻", "🌱", "🪴", "🌲", "🌳", "🌴", "🌵", "🌾", "🌿",
            "☘️", "🍀", "🍁", "🍂", "🍃", "🪹", "🪺", "🍄", "🪾",
        ),
    )
    val Food = EmojiCategory(
        icon = "🍔",
        emoji = listOf(
            "🍇", "🍈", "🍉", "🍊", "🍋", "🍋‍🟩", "🍌", "🍍", "🥭", "🍎",
            "🍏", "🍐", "🍑", "🍒", "🍓", "🫐", "🥝", "🍅", "🫒", "🥥",
            "🥑", "🍆", "🥔", "🥕", "🌽", "🌶️", "🫑", "🥒", "🥬", "🥦",
            "🧄", "🧅", "🥜", "🫘", "🌰", "🫚", "🫛", "🍄‍🟫", "🫜", "🍞",
            "🥐", "🥖", "🫓", "🥨", "🥯", "🥞", "🧇", "🧀", "🍖", "🍗",
            "🥩", "🥓", "🍔", "🍟", "🍕", "🌭", "🥪", "🌮", "🌯", "🫔",
            "🥙", "🧆", "🥚", "🍳", "🥘", "🍲", "🫕", "🥣", "🥗", "🍿",
            "🧈", "🧂", "🥫", "🍱", "🍘", "🍙", "🍚", "🍛", "🍜", "🍝",
            "🍠", "🍢", "🍣", "🍤", "🍥", "🥮", "🍡", "🥟", "🥠", "🥡",
            "🍦", "🍧", "🍨", "🍩", "🍪", "🎂", "🍰", "🧁", "🥧", "🍫",
            "🍬", "🍭", "🍮", "🍯", "🍼", "🥛", "☕", "🫖", "🍵", "🍶",
            "🍾", "🍷", "🍸", "🍹", "🍺", "🍻", "🥂", "🥃", "🫗", "🥤",
            "🧋", "🧃", "🧉", "🧊", "🥢", "🍽️", "🍴", "🥄", "🔪", "🫙",
            "🏺",
        ),
    )
    val Travel = EmojiCategory(
        icon = "✈️",
        emoji = listOf(
            "🌍", "🌎", "🌏", "🌐", "🗺️", "🗾", "🧭", "🏔️", "⛰️", "🌋",
            "🗻", "🏕️", "🏖️", "🏜️", "🏝️", "🏞️", "🏟️", "🏛️", "🏗️", "🧱",
            "🪨", "🪵", "🛖", "🏘️", "🏚️", "🏠", "🏡", "🏢", "🏣", "🏤",
            "🏥", "🏦", "🏨", "🏩", "🏪", "🏫", "🏬", "🏭", "🏯", "🏰",
            "💒", "🗼", "🗽", "⛪", "🕌", "🛕", "🕍", "⛩️", "🕋", "⛲",
            "⛺", "🌁", "🌃", "🏙️", "🌄", "🌅", "🌆", "🌇", "🌉", "♨️",
            "🎠", "🛝", "🎡", "🎢", "💈", "🎪", "🚂", "🚃", "🚄", "🚅",
            "🚆", "🚇", "🚈", "🚉", "🚊", "🚝", "🚞", "🚋", "🚌", "🚍",
            "🚎", "🚐", "🚑", "🚒", "🚓", "🚔", "🚕", "🚖", "🚗", "🚘",
            "🚙", "🛻", "🚚", "🚛", "🚜", "🏎️", "🏍️", "🛵", "🦽", "🦼",
            "🛺", "🚲", "🛴", "🛹", "🛼", "🚏", "🛣️", "🛤️", "🛢️", "⛽",
            "🛞", "🚨", "🚥", "🚦", "🛑", "🚧", "⚓", "🛟", "⛵", "🛶",
            "🚤", "🛳️", "⛴️", "🛥️", "🚢", "✈️", "🛩️", "🛫", "🛬", "🪂",
            "💺", "🚁", "🚟", "🚠", "🚡", "🛰️", "🚀", "🛸", "🛎️", "🧳",
            "⌛", "⏳", "⌚", "⏰", "⏱️", "⏲️", "🕰️", "🕛", "🕧", "🕐",
            "🕜", "🕑", "🕝", "🕒", "🕞", "🕓", "🕟", "🕔", "🕠", "🕕",
            "🕡", "🕖", "🕢", "🕗", "🕣", "🕘", "🕤", "🕙", "🕥", "🕚",
            "🕦", "🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘", "🌙",
            "🌚", "🌛", "🌜", "🌡️", "☀️", "🌝", "🌞", "🪐", "⭐", "🌟",
            "🌠", "🌌", "☁️", "⛅", "⛈️", "🌤️", "🌥️", "🌦️", "🌧️", "🌨️",
            "🌩️", "🌪️", "🌫️", "🌬️", "🌀", "🌈", "🌂", "☂️", "☔", "⛱️",
            "⚡", "❄️", "☃️", "⛄", "☄️", "🔥", "💧", "🌊",
        ),
    )
    val Activities = EmojiCategory(
        icon = "⚽",
        emoji = listOf(
            "🎃", "🎄", "🎆", "🎇", "🧨", "✨", "🎈", "🎉", "🎊", "🎋",
            "🎍", "🎎", "🎏", "🎐", "🎑", "🧧", "🎀", "🎁", "🎗️", "🎟️",
            "🎫", "🎖️", "🏆", "🏅", "🥇", "🥈", "🥉", "⚽", "⚾", "🥎",
            "🏀", "🏐", "🏈", "🏉", "🎾", "🥏", "🎳", "🏏", "🏑", "🏒",
            "🥍", "🏓", "🏸", "🥊", "🥋", "🥅", "⛳", "⛸️", "🎣", "🤿",
            "🎽", "🎿", "🛷", "🥌", "🎯", "🪀", "🪁", "🔫", "🎱", "🔮",
            "🪄", "🎮", "🕹️", "🎰", "🎲", "🧩", "🧸", "🪅", "🪩", "🪆",
            "♠️", "♥️", "♦️", "♣️", "♟️", "🃏", "🀄", "🎴", "🎭", "🖼️",
            "🎨", "🧵", "🪡", "🧶", "🪢",
        ),
    )
    val Objects = EmojiCategory(
        icon = "💡",
        emoji = listOf(
            "👓", "🕶️", "🥽", "🥼", "🦺", "👔", "👕", "👖", "🧣", "🧤",
            "🧥", "🧦", "👗", "👘", "🥻", "🩱", "🩲", "🩳", "👙", "👚",
            "🪭", "👛", "👜", "👝", "🛍️", "🎒", "🩴", "👞", "👟", "🥾",
            "🥿", "👠", "👡", "🩰", "👢", "🪮", "👑", "👒", "🎩", "🎓",
            "🧢", "🪖", "⛑️", "📿", "💄", "💍", "💎", "🔇", "🔈", "🔉",
            "🔊", "📢", "📣", "📯", "🔔", "🔕", "🎼", "🎵", "🎶", "🎙️",
            "🎚️", "🎛️", "🎤", "🎧", "📻", "🎷", "🪗", "🎸", "🎹", "🎺",
            "🎻", "🪕", "🥁", "🪘", "🪇", "🪈", "🪉", "📱", "📲", "☎️",
            "📞", "📟", "📠", "🔋", "🪫", "🔌", "💻", "🖥️", "🖨️", "⌨️",
            "🖱️", "🖲️", "💽", "💾", "💿", "📀", "🧮", "🎥", "🎞️", "📽️",
            "🎬", "📺", "📷", "📸", "📹", "📼", "🔍", "🔎", "🕯️", "💡",
            "🔦", "🏮", "🪔", "📔", "📕", "📖", "📗", "📘", "📙", "📚",
            "📓", "📒", "📃", "📜", "📄", "📰", "🗞️", "📑", "🔖", "🏷️",
            "💰", "🪙", "💴", "💵", "💶", "💷", "💸", "💳", "🧾", "💹",
            "✉️", "📧", "📨", "📩", "📤", "📥", "📦", "📫", "📪", "📬",
            "📭", "📮", "🗳️", "✏️", "✒️", "🖋️", "🖊️", "🖌️", "🖍️", "📝",
            "💼", "📁", "📂", "🗂️", "📅", "📆", "🗒️", "🗓️", "📇", "📈",
            "📉", "📊", "📋", "📌", "📍", "📎", "🖇️", "📏", "📐", "✂️",
            "🗃️", "🗄️", "🗑️", "🔒", "🔓", "🔏", "🔐", "🔑", "🗝️", "🔨",
            "🪓", "⛏️", "⚒️", "🛠️", "🗡️", "⚔️", "💣", "🪃", "🏹", "🛡️",
            "🪚", "🔧", "🪛", "🔩", "⚙️", "🗜️", "⚖️", "🦯", "🔗", "⛓️‍💥",
            "⛓️", "🪝", "🧰", "🧲", "🪜", "🪏", "⚗️", "🧪", "🧫", "🧬",
            "🔬", "🔭", "📡", "💉", "🩸", "💊", "🩹", "🩼", "🩺", "🩻",
            "🚪", "🛗", "🪞", "🪟", "🛏️", "🛋️", "🪑", "🚽", "🪠", "🚿",
            "🛁", "🪤", "🪒", "🧴", "🧷", "🧹", "🧺", "🧻", "🪣", "🧼",
            "🫧", "🪥", "🧽", "🧯", "🛒", "🚬", "⚰️", "🪦", "⚱️", "🧿",
            "🪬", "🗿", "🪧", "🪪",
        ),
    )
    val Symbols = EmojiCategory(
        icon = "❤️",
        emoji = listOf(
            "🏧", "🚮", "🚰", "♿", "🚹", "🚺", "🚻", "🚼", "🚾", "🛂",
            "🛃", "🛄", "🛅", "⚠️", "🚸", "⛔", "🚫", "🚳", "🚭", "🚯",
            "🚱", "🚷", "📵", "🔞", "☢️", "☣️", "⬆️", "↗️", "➡️", "↘️",
            "⬇️", "↙️", "⬅️", "↖️", "↕️", "↔️", "↩️", "↪️", "⤴️", "⤵️",
            "🔃", "🔄", "🔙", "🔚", "🔛", "🔜", "🔝", "🛐", "⚛️", "🕉️",
            "✡️", "☸️", "☯️", "✝️", "☦️", "☪️", "☮️", "🕎", "🔯", "🪯",
            "♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐", "♑",
            "♒", "♓", "⛎", "🔀", "🔁", "🔂", "▶️", "⏩", "⏭️", "⏯️",
            "◀️", "⏪", "⏮️", "🔼", "⏫", "🔽", "⏬", "⏸️", "⏹️", "⏺️",
            "⏏️", "🎦", "🔅", "🔆", "📶", "🛜", "📳", "📴", "♀️", "♂️",
            "⚧️", "✖️", "➕", "➖", "➗", "🟰", "♾️", "‼️", "⁉️", "❓",
            "❔", "❕", "❗", "〰️", "💱", "💲", "⚕️", "♻️", "⚜️", "🔱",
            "📛", "🔰", "⭕", "✅", "☑️", "✔️", "❌", "❎", "➰", "➿",
            "〽️", "✳️", "✴️", "❇️", "©️", "®️", "™️", "🫟", "#️⃣", "*️⃣",
            "0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣",
            "🔟", "🔠", "🔡", "🔢", "🔣", "🔤", "🅰️", "🆎", "🅱️", "🆑",
            "🆒", "🆓", "ℹ️", "🆔", "Ⓜ️", "🆕", "🆖", "🅾️", "🆗", "🅿️",
            "🆘", "🆙", "🆚", "🈁", "🈂️", "🈷️", "🈶", "🈯", "🉐", "🈹",
            "🈚", "🈲", "🉑", "🈸", "🈴", "🈳", "㊗️", "㊙️", "🈺", "🈵",
            "🔴", "🟠", "🟡", "🟢", "🔵", "🟣", "🟤", "⚫", "⚪", "🟥",
            "🟧", "🟨", "🟩", "🟦", "🟪", "🟫", "⬛", "⬜", "◼️", "◻️",
            "◾", "◽", "▪️", "▫️", "🔶", "🔷", "🔸", "🔹", "🔺", "🔻",
            "💠", "🔘", "🔳", "🔲",
        ),
    )
    val Flags = EmojiCategory(
        icon = "🏳️",
        emoji = listOf(
            "🏁", "🚩", "🎌", "🏴", "🏳️", "🏳️‍🌈", "🏳️‍⚧️", "🏴‍☠️", "🇦🇨", "🇦🇩",
            "🇦🇪", "🇦🇫", "🇦🇬", "🇦🇮", "🇦🇱", "🇦🇲", "🇦🇴", "🇦🇶", "🇦🇷", "🇦🇸",
            "🇦🇹", "🇦🇺", "🇦🇼", "🇦🇽", "🇦🇿", "🇧🇦", "🇧🇧", "🇧🇩", "🇧🇪", "🇧🇫",
            "🇧🇬", "🇧🇭", "🇧🇮", "🇧🇯", "🇧🇱", "🇧🇲", "🇧🇳", "🇧🇴", "🇧🇶", "🇧🇷",
            "🇧🇸", "🇧🇹", "🇧🇻", "🇧🇼", "🇧🇾", "🇧🇿", "🇨🇦", "🇨🇨", "🇨🇩", "🇨🇫",
            "🇨🇬", "🇨🇭", "🇨🇮", "🇨🇰", "🇨🇱", "🇨🇲", "🇨🇳", "🇨🇴", "🇨🇵", "🇨🇶",
            "🇨🇷", "🇨🇺", "🇨🇻", "🇨🇼", "🇨🇽", "🇨🇾", "🇨🇿", "🇩🇪", "🇩🇬", "🇩🇯",
            "🇩🇰", "🇩🇲", "🇩🇴", "🇩🇿", "🇪🇦", "🇪🇨", "🇪🇪", "🇪🇬", "🇪🇭", "🇪🇷",
            "🇪🇸", "🇪🇹", "🇪🇺", "🇫🇮", "🇫🇯", "🇫🇰", "🇫🇲", "🇫🇴", "🇫🇷", "🇬🇦",
            "🇬🇧", "🇬🇩", "🇬🇪", "🇬🇫", "🇬🇬", "🇬🇭", "🇬🇮", "🇬🇱", "🇬🇲", "🇬🇳",
            "🇬🇵", "🇬🇶", "🇬🇷", "🇬🇸", "🇬🇹", "🇬🇺", "🇬🇼", "🇬🇾", "🇭🇰", "🇭🇲",
            "🇭🇳", "🇭🇷", "🇭🇹", "🇭🇺", "🇮🇨", "🇮🇩", "🇮🇪", "🇮🇱", "🇮🇲", "🇮🇳",
            "🇮🇴", "🇮🇶", "🇮🇷", "🇮🇸", "🇮🇹", "🇯🇪", "🇯🇲", "🇯🇴", "🇯🇵", "🇰🇪",
            "🇰🇬", "🇰🇭", "🇰🇮", "🇰🇲", "🇰🇳", "🇰🇵", "🇰🇷", "🇰🇼", "🇰🇾", "🇰🇿",
            "🇱🇦", "🇱🇧", "🇱🇨", "🇱🇮", "🇱🇰", "🇱🇷", "🇱🇸", "🇱🇹", "🇱🇺", "🇱🇻",
            "🇱🇾", "🇲🇦", "🇲🇨", "🇲🇩", "🇲🇪", "🇲🇫", "🇲🇬", "🇲🇭", "🇲🇰", "🇲🇱",
            "🇲🇲", "🇲🇳", "🇲🇴", "🇲🇵", "🇲🇶", "🇲🇷", "🇲🇸", "🇲🇹", "🇲🇺", "🇲🇻",
            "🇲🇼", "🇲🇽", "🇲🇾", "🇲🇿", "🇳🇦", "🇳🇨", "🇳🇪", "🇳🇫", "🇳🇬", "🇳🇮",
            "🇳🇱", "🇳🇴", "🇳🇵", "🇳🇷", "🇳🇺", "🇳🇿", "🇴🇲", "🇵🇦", "🇵🇪", "🇵🇫",
            "🇵🇬", "🇵🇭", "🇵🇰", "🇵🇱", "🇵🇲", "🇵🇳", "🇵🇷", "🇵🇸", "🇵🇹", "🇵🇼",
            "🇵🇾", "🇶🇦", "🇷🇪", "🇷🇴", "🇷🇸", "🇷🇺", "🇷🇼", "🇸🇦", "🇸🇧", "🇸🇨",
            "🇸🇩", "🇸🇪", "🇸🇬", "🇸🇭", "🇸🇮", "🇸🇯", "🇸🇰", "🇸🇱", "🇸🇲", "🇸🇳",
            "🇸🇴", "🇸🇷", "🇸🇸", "🇸🇹", "🇸🇻", "🇸🇽", "🇸🇾", "🇸🇿", "🇹🇦", "🇹🇨",
            "🇹🇩", "🇹🇫", "🇹🇬", "🇹🇭", "🇹🇯", "🇹🇰", "🇹🇱", "🇹🇲", "🇹🇳", "🇹🇴",
            "🇹🇷", "🇹🇹", "🇹🇻", "🇹🇼", "🇹🇿", "🇺🇦", "🇺🇬", "🇺🇲", "🇺🇳", "🇺🇸",
            "🇺🇾", "🇺🇿", "🇻🇦", "🇻🇨", "🇻🇪", "🇻🇬", "🇻🇮", "🇻🇳", "🇻🇺", "🇼🇫",
            "🇼🇸", "🇽🇰", "🇾🇪", "🇾🇹", "🇿🇦", "🇿🇲", "🇿🇼", "🏴󠁧󠁢󠁥󠁮󠁧󠁿", "🏴󠁧󠁢󠁳󠁣󠁴󠁿", "🏴󠁧󠁢󠁷󠁬󠁳󠁿",
        ),
    )
    val Special = EmojiCategory(
        icon = "#",
        emoji = listOf(
            "°", "%", "§", "†", "‡", "•", "‣", "∙", "©", "®",
            "™", "℠", "№", "#", "$", "€", "£", "¥", "¢", "₹",
            "₩", "₽", "±", "×", "÷", "=", "≠", "≈", "≤", "≥",
            "∞", "√", "∑", "∏", "Ω", "∆", "π", "µ", "→", "←",
            "↑", "↓", "↔", "¶", "«", "»", "‹", "›", "„", "“",
            "”", "‘", "’", "—", "–", "…", "¦", "¬", "¯", "´",
            "¨", "ˆ", "˜", "¤", "ƒ", "¹", "²", "³", "¼", "½",
            "¾", "α", "β", "γ", "δ",
        ),
    )
    // Plain text "faces," not unicode emoji glyphs — EmojiCategory.emoji is just List<String>,
    // so these slot in via the exact same data model and rendering path as everything else.
    val Emoticons = EmojiCategory(
        icon = "(^_^)",
        emoji = listOf(
            "(^_^)", "(^o^)", "(^_-)", "(-_-)", "(>_<)", "(T_T)", "(;_;)", "(ToT)", "(*_*)", "(o_o)",
            "(O_O)", "(0_0)", "(-_-)zzz", "(¬_¬)", "(¯\\_(ツ)_/¯)", "(╯°□°)╯", "ヽ(´▽`)/", "(づ｡◕‿‿◕｡)づ",
            "(ノಠ益ಠ)ノ", "щ(゚Д゚щ)", "(っ˘̩╭╮˘̩)っ", "ʕ•ᴥ•ʔ", "(◕‿◕)", "(¬‿¬)", "(＾▽＾)", "(≧▽≦)", "(๑˃̵ᴗ˂̵)و",
            "(灬º‿º灬)", "(・∀・)", "(´∀｀)", "(￣ω￣)", "(￣3￣)", "(*≧ω≦*)", "(๑>◡<๑)", "(*^▽^*)", "٩(◕‿◕)۶",
            "(⌒▽⌒)", "＼(≧▽≦)／", "(*¯︶¯*)", "(´｡• ᵕ •｡`)", "(◍•ᴗ•◍)", "( ˘ ³˘)♥", "(｡♥‿♥｡)", "(￣▽￣)ノ",
            "orz", "OTZ", "凸(-_-)凸", "ヽ(`Д´)ﾉ", "(╬ ಠ益ಠ)", "( ˘ ˘)", "(－‸ლ)", "(＃￣ω￣)", "(・_・;)",
            "(・・;)", "٩(๛ ˘ ³˘)۶", "(づ￣ ³￣)づ", "(*^_^*)", "＼(^o^)／", "٩(◕‿◕｡)۶", "(ง'̀-'́)ง", "ヽ(°〇°)ﾉ",
        ),
    )

    val all = listOf(Smileys, People, Animals, Food, Travel, Activities, Objects, Symbols, Flags, Emoticons, Special)
}
