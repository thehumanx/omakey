package dev.omakey.app.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.core.layout.KeyDefinition
import dev.omakey.core.theme.LayoutMode
import dev.omakey.core.theme.LocalKeyboardLayoutMode
import dev.omakey.core.theme.OmakeyTheme
import dev.omakey.core.theme.toComposeColor
import dev.omakey.core.theme.toDp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The tap preview bubble's state, kept apart from everything else so a keystroke touches only the
 * bubble. [key] is the last key previewed and stays set after the bubble hides, so the bubble's
 * node is built once and reused rather than created and torn down on every tap; [visible] is read
 * in the draw phase only, so showing and hiding it never recomposes anything. It shows while the
 * finger is down and lingers [LINGER_MS] after it lifts.
 */
@Stable
internal class TapPreviewState {
    var key: Pair<KeyDefinition, Rect>? by mutableStateOf(null)
        private set
    var visible: Boolean by mutableStateOf(false)
        private set

    private var hideJob: Job? = null

    /** A finger is down on [key]: show it, for as long as the finger stays. */
    fun hold(key: Pair<KeyDefinition, Rect>) {
        hideJob?.cancel()
        this.key = key
        visible = true
    }

    /** The finger lifted: hide after a short linger, so even the quickest tap is seen. */
    fun release(scope: CoroutineScope) {
        if (!visible) return
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(LINGER_MS)
            visible = false
        }
    }

    /** A key typed without a finger-down of its own (a rollover finger): show it briefly. */
    fun flash(key: Pair<KeyDefinition, Rect>, scope: CoroutineScope) {
        hold(key)
        release(scope)
    }

    /** The touch became something else (a swipe, a long-press popup): hide now. */
    fun hide() {
        hideJob?.cancel()
        visible = false
    }

    private companion object {
        const val LINGER_MS = 70L
    }
}

/** The enlarged copy of the tapped key, drawn above it. Recomposes only when a different key is
 * previewed (its label and size change); repeat taps on the same key and every show/hide are
 * draw-only. Hidden from accessibility: it repeats the key that was just typed and announced. */
@Composable
internal fun TapPreviewBubble(
    state: TapPreviewState,
    theme: OmakeyTheme,
    fontFamily: FontFamily?,
    ancestorCoordinates: () -> LayoutCoordinates?,
    /** The "Slim" style: key-wide, shorter, slightly see-through, sitting closer to the key. */
    slim: Boolean = false,
) {
    val (key, rect) = state.key ?: return
    val density = LocalDensity.current
    val bubbleWidthPx = if (slim) rect.width + with(density) { 2.dp.toPx() } else with(density) { 44.dp.toPx() }.coerceAtLeast(rect.width)
    val bubbleHeightPx = with(density) { (if (slim) 42.dp else 52.dp).toPx() }
    val gapPx = with(density) { (if (slim) 3.dp else 6.dp).toPx() }
    val isGridMode = LocalKeyboardLayoutMode.current == LayoutMode.GRID
    val shape = RoundedCornerShape(if (isGridMode) 0.dp else if (slim) 6.dp else 8.dp)
    // The key tap colour in both modes: the bubble is the tapped key, enlarged. Slim lets a
    // little of what's behind show through, so it reads as lighter.
    // Classic is 70 % opaque too: solid, it read as one blob with the pressed key under it.
    val background = theme.keyBackgroundPressed.toComposeColor().let { it.copy(alpha = it.alpha * if (slim) SLIM_ALPHA else CLASSIC_ALPHA) }
    Box(
        modifier = Modifier
            .offset {
                val parentWidthPx = ancestorCoordinates()?.size?.width?.toFloat() ?: rect.right
                val x = ((rect.left + rect.right) / 2f - bubbleWidthPx / 2f)
                    .coerceIn(0f, (parentWidthPx - bubbleWidthPx).coerceAtLeast(0f))
                // Not clamped to the keys: the top row's bubble rises over the bar, and the number
                // row's into the clear space above the keyboard (OmakeyInputMethodService keeps
                // that space in the window, untouchable), the way Fleksy's do.
                val y = rect.top - bubbleHeightPx - gapPx
                IntOffset(x.toInt(), y.toInt())
            }
            .graphicsLayer {
                alpha = if (state.visible) 1f else 0f
                if (slim) {
                    shadowElevation = 3.dp.toPx()
                    this.shape = shape
                }
            }
            .clearAndSetSemantics {}
            .size(with(density) { bubbleWidthPx.toDp() }, with(density) { bubbleHeightPx.toDp() })
            .background(background, shape)
            .let { m ->
                if (isGridMode) m.border(theme.gridBorderWidth.toDp(), theme.gridBorderColor.toComposeColor(), shape) else m
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label.let { if (it.length == 1) it.uppercase() else it },
            color = theme.labelOn(theme.keyBackgroundPressed).toComposeColor(),
            fontFamily = fontFamily,
            fontSize = if (slim) 22.sp else 26.sp,
        )
    }
}

private const val SLIM_ALPHA = 0.88f
private const val CLASSIC_ALPHA = 0.7f

/** What the bar's number row needs to show the same bubble as the keys. */
internal class TapPreviewHost(
    val state: TapPreviewState,
    /** The keys area every bubble is positioned against. */
    val ancestor: () -> LayoutCoordinates?,
    val enabled: () -> Boolean,
    val scope: CoroutineScope,
)

internal val LocalTapPreviewHost = androidx.compose.runtime.staticCompositionLocalOf<TapPreviewHost?> { null }
