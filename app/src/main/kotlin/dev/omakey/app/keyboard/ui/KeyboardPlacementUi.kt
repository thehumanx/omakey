package dev.omakey.app.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.app.keyboard.KeyboardFeedback
import dev.omakey.app.keyboard.KeyboardViewModel
import dev.omakey.core.theme.LocalOmakeyTheme
import dev.omakey.core.theme.toComposeColor
import dev.omakey.core.theme.toDp
import dev.omakey.core.icons.PhosphorArrowLeft
import dev.omakey.core.icons.PhosphorExpand
import dev.omakey.core.icons.PhosphorFloating
import dev.omakey.core.icons.PhosphorGear
import dev.omakey.core.icons.PhosphorGlobe
import dev.omakey.core.icons.PhosphorOneHanded
import dev.omakey.core.icons.PhosphorPalette
import dev.omakey.core.icons.PhosphorResize
import dev.omakey.core.icons.PhosphorSwitchSide
import kotlin.math.roundToInt
import dev.omakey.core.layout.KeyboardPlacement
import dev.omakey.core.layout.KeyboardPlacementGeometry
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.theme.OmakeyTheme

/*
 * Placement: the live drag state every mode shares, the quick-access panel, the one-handed gutter,
 * the floating move handle and the resize overlay. Split out of KeyboardRoot.kt.
 *
 * These belong in one file because they all mutate the same PlacementState and have to agree about
 * the keyboard's geometry — see FLOATING_HANDLE_HEIGHT_DP, which must be accounted for in three
 * places at once or the overlay's handles land off the keyboard's real edges.
 */

/**
 * Live size/position for the current placement, seeded from preferences and mutated by resize
 * drags.
 *
 * Local state rather than reading [LayoutSettings] directly, because a drag updates this on every
 * frame and preferences are a SharedPreferences write plus a StateFlow emission plus a
 * recomposition of everything that observes them — fine once per gesture ([commit]), not sixty
 * times a second. Same split Settings' own `KeyboardSizePositionOverlay` already uses.
 *
 * Every setter clamps through [KeyboardPlacementGeometry], so there is no path by which a drag can
 * leave the keyboard off screen or too small to type on, whatever order the user drags in.
 */
@androidx.compose.runtime.Stable
internal class PlacementState(
    private val settings: LayoutSettings,
    private val screenWidthDp: Int,
    private val screenHeightDp: Int,
    private val onCommit: (width: Int, height: Int, x: Int, y: Int, bottomOffset: Int) -> Unit,
) {
    private val placement = settings.placement

    var widthDp by androidx.compose.runtime.mutableFloatStateOf(
        when {
            placement == KeyboardPlacement.FLOATING ->
                KeyboardPlacementGeometry.clampFloatingWidth(settings.floatingWidthDp, screenWidthDp).toFloat()
            placement.isOneHanded ->
                KeyboardPlacementGeometry.clampOneHandedWidth(settings.oneHandedWidthDp, screenWidthDp).toFloat()
            else -> screenWidthDp.toFloat()
        },
    )
        private set

    var keyboardHeightDpFloat by androidx.compose.runtime.mutableFloatStateOf(
        if (placement == KeyboardPlacement.FLOATING) {
            KeyboardPlacementGeometry.clampFloatingHeight(settings.floatingHeightDp, screenHeightDp).toFloat()
        } else {
            settings.keyboardHeightDp.toFloat()
        },
    )
        private set

    /** Left edge, from the window's left. Only meaningful while floating. */
    var floatingXDpFloat by androidx.compose.runtime.mutableFloatStateOf(
        (
            settings.floatingXDp.takeIf { it != LayoutSettings.UNSET_POSITION }
                // Never positioned before — centre it rather than dropping it in a corner the user
                // then has to drag it out of.
                ?: KeyboardPlacementGeometry.defaultFloatingX(
                    KeyboardPlacementGeometry.clampFloatingWidth(settings.floatingWidthDp, screenWidthDp),
                    screenWidthDp,
                )
            ).toFloat(),
    )
        private set

    /**
     * How far a docked keyboard is raised off the bottom edge.
     *
     * Lives here, alongside floating position and size, because the quick-access overlay now
     * repositions as well as resizes — matching what Settings' "Keyboard size & position" screen
     * has always done. Before this it was reachable only from Settings, which meant the overlay
     * called "Resize" could change a docked keyboard's height but not where it sat.
     */
    var bottomOffsetDpFloat by androidx.compose.runtime.mutableFloatStateOf(settings.bottomOffsetDp.toFloat())
        private set

    /** Bottom edge, measured *up* from the window's bottom. See clampFloatingY's doc for why up. */
    var floatingBottomDpFloat by androidx.compose.runtime.mutableFloatStateOf(settings.floatingYDp.toFloat())
        private set

    val keyboardHeightDp: Int get() = keyboardHeightDpFloat.roundToInt()
    val floatingXDp: Int get() = floatingXDpFloat.roundToInt()
    val floatingBottomDp: Int get() = floatingBottomDpFloat.roundToInt()
    val bottomOffsetDp: Int get() = bottomOffsetDpFloat.roundToInt()

    /** What [move]'s vertical clamp measures against, so it has to match what is actually on
     * screen: a floating keyboard also carries [FloatingMoveHandle] above the strip, and omitting
     * it here would let the top of the keyboard be dragged that far past the top edge. */
    private val totalHeightDp: Float
        get() = keyboardHeightDpFloat + SUGGESTION_STRIP_HEIGHT_DP +
            if (placement == KeyboardPlacement.FLOATING) FLOATING_HANDLE_HEIGHT_DP else 0

    fun resizeWidth(deltaDp: Float) {
        widthDp = when {
            placement == KeyboardPlacement.FLOATING ->
                KeyboardPlacementGeometry.clampFloatingWidth((widthDp + deltaDp).roundToInt(), screenWidthDp).toFloat()
            placement.isOneHanded ->
                KeyboardPlacementGeometry.clampOneHandedWidth((widthDp + deltaDp).roundToInt(), screenWidthDp).toFloat()
            else -> widthDp // docked is always full width; nothing to drag
        }
    }

    fun resizeHeight(deltaDp: Float) {
        val next = keyboardHeightDpFloat + deltaDp
        keyboardHeightDpFloat = if (placement == KeyboardPlacement.FLOATING) {
            KeyboardPlacementGeometry.clampFloatingHeight(next.roundToInt(), screenHeightDp).toFloat()
        } else {
            next.coerceIn(LayoutSettings.MIN_HEIGHT_DP.toFloat(), LayoutSettings.MAX_HEIGHT_DP.toFloat())
        }
    }

    /**
     * Accumulates a drag delta. Stays in float throughout — see
     * [KeyboardPlacementGeometry.clampFloatingX]`(Float, Float, Int)`: rounding the running total
     * each frame discards the fraction, so a slow drag moved the keyboard nowhere at all and a
     * faster one advanced in visible one-dp steps. Rounding happens once, in [commit].
     */
    fun move(deltaXDp: Float, deltaYDp: Float) {
        if (placement != KeyboardPlacement.FLOATING) return
        floatingXDpFloat = KeyboardPlacementGeometry
            .clampFloatingX(floatingXDpFloat + deltaXDp, widthDp, screenWidthDp)
        // Dragging the finger *down* (positive y) lowers the keyboard, which decreases a value
        // measured upward from the bottom — hence the subtraction.
        floatingBottomDpFloat = KeyboardPlacementGeometry
            .clampFloatingY(floatingBottomDpFloat - deltaYDp, totalHeightDp, screenHeightDp)
    }

    /**
     * Raises or lowers a **docked or one-handed** keyboard. The floating equivalent is [move];
     * these are separate because they mean different things — floating has a free x/y position,
     * while a docked keyboard only ever sits at some distance above the bottom edge.
     *
     * Dragging up (negative delta, since y decreases upward) raises the keyboard, so the sign is
     * inverted here exactly as it is in [move].
     */
    fun raise(deltaYDp: Float) {
        if (placement == KeyboardPlacement.FLOATING) return
        bottomOffsetDpFloat = KeyboardPlacementGeometry
            .clampBottomOffset(bottomOffsetDpFloat - deltaYDp, totalHeightDp, screenHeightDp)
    }

    fun commit() =
        onCommit(widthDp.roundToInt(), keyboardHeightDp, floatingXDp, floatingBottomDp, bottomOffsetDp)
}

/** Keyed on the settings that seed it, so an external change (Settings, or switching placement)
 * re-seeds rather than leaving stale local values on screen. */
@Composable
internal fun rememberPlacementState(
    viewModel: KeyboardViewModel,
    settings: LayoutSettings,
    screenWidthDp: Int,
    screenHeightDp: Int,
): PlacementState = remember(
    viewModel,
    settings.placement,
    settings.floatingWidthDp,
    settings.floatingHeightDp,
    settings.floatingXDp,
    settings.floatingYDp,
    settings.oneHandedWidthDp,
    settings.keyboardHeightDp,
    screenWidthDp,
    screenHeightDp,
) {
    PlacementState(settings, screenWidthDp, screenHeightDp, viewModel::commitPlacementBounds)
}

/** One quick-access tile: icon over label, in a 4-column grid. */
private data class QuickTile(
    val label: String,
    val icon: ImageVector,
    val active: Boolean,
    val onClick: () -> Unit,
)

/**
 * The panel behind the quick-access button — the keyboard's own home for decisions about *where it
 * sits*, which are made while looking at the app being typed into and so have no business being in
 * Settings.
 *
 * Occupies the key-grid slot (same one the extension panels use) rather than floating over the
 * keys: these tiles change the keyboard's size and position, and a panel overlapping the thing it
 * is about would hide the result of every tap.
 *
 * A tile for the mode you are already in is highlighted and switches back to docked, so each is its
 * own off switch — no separate "back to normal" control to find.
 */
@Composable
internal fun QuickAccessPanel(
    viewModel: KeyboardViewModel,
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    feedback: KeyboardFeedback,
    placement: KeyboardPlacement,
    heightDp: Int,
    /** Enabled languages; the Language tile only appears when there is a choice to make. */
    languageCount: Int,
    onOpenSettings: () -> Unit,
) {
    val tiles = listOf(
        QuickTile("One-handed", PhosphorOneHanded, placement.isOneHanded) {
            feedback.onKeyPress(); viewModel.toggleOneHanded()
        },
        QuickTile("Floating", PhosphorFloating, placement == KeyboardPlacement.FLOATING) {
            feedback.onKeyPress(); viewModel.setPlacement(KeyboardPlacement.FLOATING)
        },
        QuickTile("Size & position", PhosphorResize, false) {
            feedback.onKeyPress(); viewModel.setResizing(true)
        },
        QuickTile("Theme", PhosphorPalette, false) {
            feedback.onKeyPress(); viewModel.cycleTheme()
        },
        QuickTile("Settings", PhosphorGear, false) {
            feedback.onKeyPress(); viewModel.closeQuickAccess(); onOpenSettings()
        },
    ) + if (languageCount > 1) {
        listOf(QuickTile("Language", PhosphorGlobe, false) { feedback.onKeyPress(); viewModel.openLanguagePicker() })
    } else {
        emptyList()
    }

    TilePanel(
        title = "Quick access",
        closeDescription = "Close quick access",
        onClose = { viewModel.closeQuickAccess() },
        tiles = tiles,
        theme = theme,
        fontFamily = fontFamily,
        feedback = feedback,
        heightDp = heightDp,
    )
}

/**
 * The language picker: every enabled language as a tile, the active one highlighted, plus a way
 * into Settings to add more. Opened by long-pressing the language key or from quick access, and
 * shown in the key-grid slot for the same reason quick access is.
 */
@Composable
internal fun LanguagePickerPanel(
    viewModel: KeyboardViewModel,
    languages: List<dev.omakey.app.keyboard.LanguageOption>,
    activeLanguageId: String,
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    feedback: KeyboardFeedback,
    heightDp: Int,
    onOpenSettings: () -> Unit,
) {
    val tiles = languages.map { language ->
        QuickTile(language.nativeName, PhosphorGlobe, language.id == activeLanguageId) {
            feedback.onKeyPress(); viewModel.selectLanguage(language.id)
        }
    } + QuickTile("Languages…", PhosphorGear, false) {
        feedback.onKeyPress(); viewModel.closeLanguagePicker(); onOpenSettings()
    }
    TilePanel(
        title = "Language",
        closeDescription = "Close language picker",
        onClose = { viewModel.closeLanguagePicker() },
        tiles = tiles,
        theme = theme,
        fontFamily = fontFamily,
        feedback = feedback,
        heightDp = heightDp,
    )
}

/** A back arrow and title over a 4-column grid of [tiles] — quick access and the language picker. */
@Composable
private fun TilePanel(
    title: String,
    closeDescription: String,
    onClose: () -> Unit,
    tiles: List<QuickTile>,
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    feedback: KeyboardFeedback,
    heightDp: Int,
) {
    val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
    Column(
        Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .background(theme.keyboardBackground.toComposeColor()),
    ) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val backInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = backInteraction,
                        indication = if (isGridMode) null else androidx.compose.foundation.LocalIndication.current,
                    ) { feedback.onKeyPress(); onClose() }
                    .padding(horizontal = 6.dp)
                    .semantics { contentDescription = closeDescription },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = PhosphorArrowLeft,
                    contentDescription = null,
                    tint = theme.keyTextColor.toComposeColor(),
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = title,
                color = theme.keyTextColor.toComposeColor().copy(alpha = 0.7f),
                fontFamily = fontFamily,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
        ) {
            gridItems(tiles) { tile ->
                val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val background = when {
                    tile.active -> theme.keyBackgroundPressed
                    isPressed -> theme.keyBackgroundPressed
                    else -> theme.keySpecialBackground
                }
                Column(
                    modifier = Modifier
                        .padding(4.dp)
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(
                            background.toComposeColor(),
                            androidx.compose.foundation.shape.RoundedCornerShape(if (isGridMode) 0.dp else 10.dp),
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = if (isGridMode) null else androidx.compose.foundation.LocalIndication.current,
                            onClick = tile.onClick,
                        )
                        .semantics { contentDescription = tile.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(
                        imageVector = tile.icon,
                        contentDescription = null,
                        // Resolved against this tile's own fill, which for an active tile is the
                        // accent colour — see OmakeyTheme.labelOn.
                        tint = theme.labelOn(background).toComposeColor(),
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = tile.label,
                        color = theme.labelOn(background).toComposeColor(),
                        fontFamily = fontFamily,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * The narrow column of buttons filling the space a one-handed keyboard leaves behind — switch
 * side, expand back to full width, resize. Same three Gboard puts there, and for the same reason:
 * the gutter is dead space that the hand not holding the phone can't reach anyway, so the controls
 * for getting *out* of one-handed mode belong in it.
 */
@Composable
internal fun OneHandedGutter(
    viewModel: KeyboardViewModel,
    theme: OmakeyTheme,
    placement: KeyboardPlacement,
    heightDp: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.height(heightDp.dp).width(KeyboardPlacementGeometry.ONE_HANDED_GUTTER_DP.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The switch-side arrow points where the keyboard is *going*, so it mirrors with the
        // current side — an arrow pointing left while the keyboard is already on the left reads as
        // a broken button. Mirroring the one glyph beats shipping a second, near-identical icon.
        val switchSideMirrored = placement == KeyboardPlacement.ONE_HANDED_LEFT
        val buttons = listOf(
            Triple("Switch side", PhosphorSwitchSide) { viewModel.switchOneHandedSide() },
            Triple("Full width", PhosphorExpand) { viewModel.setPlacement(KeyboardPlacement.DOCKED) },
            Triple("Size & position", PhosphorResize) { viewModel.setResizing(true) },
        )
        buttons.forEach { (description, icon, action) ->
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            Box(
                Modifier
                    .padding(vertical = 6.dp)
                    .size(36.dp)
                    .background(
                        if (isPressed) theme.keyBackgroundPressed.toComposeColor() else theme.keySpecialBackground.toComposeColor(),
                        androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    )
                    .clickable(interactionSource = interactionSource, indication = null, onClick = action)
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = theme.keyTextColor.toComposeColor(),
                    modifier = Modifier
                        .size(18.dp)
                        .then(
                            if (icon == PhosphorSwitchSide && switchSideMirrored) {
                                Modifier.graphicsLayer(scaleX = -1f)
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
    }
}

/**
 * Drag-to-resize, drawn over the keyboard in whatever placement it is currently in.
 *
 * Which edges are draggable follows from the placement, because that is what "resize in the current
 * mode" means — a docked keyboard has no width to change, a floating one has both dimensions and a
 * position, a one-handed one has a width worth changing and a height shared with docked:
 *
 * | placement   | draggable                                |
 * |-------------|------------------------------------------|
 * | docked      | top edge → height                        |
 * | one-handed  | inner edge → width; top edge → height    |
 * | floating    | corner → width + height; body → position |
 *
 * Corner brackets rather than a full outline: they mark the grab targets, which an outline does
 * not, and they leave the keys underneath readable while dragging.
 *
 * Everything is live — the keyboard behind this really is resizing as the finger moves, rather than
 * showing a ghost that snaps into place on release. The value is written to preferences once, on
 * release (see [PlacementState.commit]).
 */
/**
 * The grab bar along the top of a floating keyboard.
 *
 * Drags move the keyboard live and write the new position once on release, the same contract
 * [ResizeOverlay] uses — [PlacementState] already owns the clamping and the single commit, so this
 * adds an affordance rather than a second source of truth for where the keyboard is.
 *
 * A dedicated strip rather than making the whole keyboard draggable: every other pixel of a
 * keyboard is a key, and a drag that starts on a key has to stay a swipe gesture. This is the only
 * surface that can afford to mean "move me".
 *
 * `consume()` on each change matters more here than it looks — without it the drag would also reach
 * the key grid's own pointer loop underneath and be read as a surface swipe.
 */
@Composable
internal fun FloatingMoveHandle(theme: OmakeyTheme, place: PlacementState) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(FLOATING_HANDLE_HEIGHT_DP.dp)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { place.commit() }) { change, dragAmount ->
                    change.consume()
                    with(density) { place.move(dragAmount.x.toDp().value, dragAmount.y.toDp().value) }
                }
            }
            .semantics { contentDescription = "Move keyboard" },
        contentAlignment = Alignment.Center,
    ) {
        // Drawn from keyTextColor, faded — not keyBackgroundPressed, which was the first choice and
        // is the wrong one: pressed-key colour is designed to contrast with *keyBackground*, and a
        // custom theme is free to set it equal to keyboardBackground, which is what this sits on.
        // The pill would then be invisible with nothing obviously wrong. keyTextColor is the one
        // colour a theme must keep legible against the keyboard background, so deriving from it
        // means the handle cannot disappear on any theme, including ones not written yet.
        Box(
            Modifier
                .size(width = 40.dp, height = 5.dp)
                .background(
                    theme.keyTextColor.toComposeColor().copy(alpha = 0.4f),
                    androidx.compose.foundation.shape.RoundedCornerShape(2.5.dp),
                ),
        )
    }
}

/**
 * Drag up to raise a docked or one-handed keyboard off the bottom edge, for thumb reach.
 *
 * The floating equivalent is the whole keyboard body (there is nothing else it could usefully do
 * while floating). Here the body is full of keys the user may want to see, so this is a discrete
 * grip in the middle — the same affordance, in the same place, as Settings' "Keyboard size &
 * position" screen, so the two do not teach different gestures for the same adjustment.
 *
 * Vertical-only: a docked keyboard has no horizontal position to change.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.RaiseHandle(
    place: PlacementState,
    density: androidx.compose.ui.unit.Density,
) {
    val theme = LocalOmakeyTheme.current
    Box(
        Modifier
            // Below the centre, not on it: "Done" owns the centre of this overlay and is drawn
            // after everything in the `when`, so a grip sharing that spot would be covered by it
            // and simply not receive the drag.
            .align(Alignment.Center)
            .offset(y = 38.dp)
            .size(width = 64.dp, height = 32.dp)
            .background(
                theme.keyBackgroundPressed.toComposeColor(),
                androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            )
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { place.commit() }) { change, dragAmount ->
                    change.consume()
                    with(density) { place.raise(dragAmount.y.toDp().value) }
                }
            }
            .semantics { contentDescription = "Raise or lower keyboard" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 28.dp, height = 5.dp)
                .background(
                    theme.keyTextColor.toComposeColor().copy(alpha = 0.6f),
                    androidx.compose.foundation.shape.RoundedCornerShape(2.5.dp),
                ),
        )
    }
}

@Composable
internal fun androidx.compose.foundation.layout.BoxScope.ResizeOverlay(
    viewModel: KeyboardViewModel,
    theme: OmakeyTheme,
    placement: KeyboardPlacement,
    place: PlacementState,
    totalHeightDp: Int,
    /** Matches the keyboard's own edge gutters so the brackets land on the keys' real edge rather
     * than the window's — only docked keyboards have them (see the root Column's own padding). */
    edgePaddingDp: Int,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val accent = theme.keyBackgroundPressed.toComposeColor()
    // Bracket arms, drawn at the four corners of the keyboard's own rectangle.
    val bracket = 18.dp
    val stroke = 3.dp

    Box(
        Modifier
            .align(
                when {
                    placement == KeyboardPlacement.FLOATING -> Alignment.BottomStart
                    placement == KeyboardPlacement.ONE_HANDED_RIGHT -> Alignment.TopEnd
                    else -> Alignment.TopStart
                },
            )
            .then(
                if (placement == KeyboardPlacement.FLOATING) {
                    // Same lambda overload as the keyboard container it has to stay aligned
                    // with — if these two used different rounding the brackets would drift off the
                    // keyboard's real edges mid-drag.
                    Modifier.offset {
                        IntOffset(
                            x = place.floatingXDpFloat.dp.roundToPx(),
                            y = -place.floatingBottomDpFloat.dp.roundToPx(),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = edgePaddingDp.dp)
            .width((place.widthDp - edgePaddingDp * 2).dp)
            .height(totalHeightDp.dp),
    ) {
        @Composable
        fun Corner(alignment: Alignment, horizontalSign: Int, verticalSign: Int, description: String) {
            Box(
                Modifier
                    .align(alignment)
                    .size(bracket * 2)
                    .pointerInput(placement) {
                        detectDragGestures(
                            onDragEnd = { place.commit() },
                        ) { change, dragAmount ->
                            change.consume()
                            with(density) {
                                // Sign per corner: dragging the right edge right grows the
                                // keyboard, dragging the left edge right shrinks it. Same for
                                // vertical. Without this, two of the four corners would resize
                                // backwards.
                                place.resizeWidth(dragAmount.x.toDp().value * horizontalSign)
                                place.resizeHeight(dragAmount.y.toDp().value * verticalSign)
                            }
                        }
                    }
                    .semantics { contentDescription = description },
            ) {
                // Two arms meeting at the corner.
                Box(
                    Modifier
                        .align(alignment)
                        .size(width = bracket, height = stroke)
                        .background(accent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                )
                Box(
                    Modifier
                        .align(alignment)
                        .size(width = stroke, height = bracket)
                        .background(accent, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                )
            }
        }

        when {
            placement == KeyboardPlacement.FLOATING -> {
                Corner(Alignment.TopStart, horizontalSign = -1, verticalSign = -1, description = "Resize from top left")
                Corner(Alignment.TopEnd, horizontalSign = 1, verticalSign = -1, description = "Resize from top right")
                Corner(Alignment.BottomStart, horizontalSign = -1, verticalSign = 1, description = "Resize from bottom left")
                Corner(Alignment.BottomEnd, horizontalSign = 1, verticalSign = 1, description = "Resize from bottom right")
                // The whole body drags the keyboard around — while resizing there is nothing else
                // the body could usefully do, and a dedicated grab handle would be one more small
                // target on an already busy overlay.
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(bracket * 2)
                        .pointerInput(placement) {
                            detectDragGestures(
                                onDragEnd = { place.commit() },
                            ) { change, dragAmount ->
                                change.consume()
                                with(density) { place.move(dragAmount.x.toDp().value, dragAmount.y.toDp().value) }
                            }
                        }
                        .semantics { contentDescription = "Move keyboard" },
                )
            }
            placement.isOneHanded -> {
                val inner = if (placement == KeyboardPlacement.ONE_HANDED_LEFT) Alignment.TopEnd else Alignment.TopStart
                val sign = if (placement == KeyboardPlacement.ONE_HANDED_LEFT) 1 else -1
                Corner(inner, horizontalSign = sign, verticalSign = -1, description = "Resize keyboard")
                Corner(
                    if (placement == KeyboardPlacement.ONE_HANDED_LEFT) Alignment.BottomEnd else Alignment.BottomStart,
                    horizontalSign = sign,
                    verticalSign = 1,
                    description = "Resize keyboard",
                )
                RaiseHandle(place, density)
            }
            else -> {
                // Docked: only the top edge means anything, so both handles resize height alone.
                Corner(Alignment.TopStart, horizontalSign = 0, verticalSign = -1, description = "Resize keyboard height")
                Corner(Alignment.TopEnd, horizontalSign = 0, verticalSign = -1, description = "Resize keyboard height")
                RaiseHandle(place, density)
            }
        }

        // Done sits inside the keyboard's own rectangle, not below it: while floating there is no
        // "below" that belongs to us, and a button outside the touchable region would not receive
        // the tap at all.
        Box(
            Modifier
                .align(Alignment.Center)
                .background(accent, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .clickable { place.commit(); viewModel.setResizing(false) }
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .semantics { contentDescription = "Done resizing" },
        ) {
            Text(text = "Done", color = theme.labelOn(theme.keyBackgroundPressed).toComposeColor(), fontSize = 14.sp)
        }
    }
}
