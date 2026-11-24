package dev.omakey.app.design

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.omakey.core.ui.Reorder

/**
 * A list you reorder by holding a row and dragging it (the grip is the visual cue; the whole row
 * answers a long press). The held row lifts with a shadow and follows the finger; the others slide
 * out of its way, and a tick marks each slot it passes. Lifting drops it and reports [onMove].
 * TalkBack users get "Move up" and "Move down" actions on every row instead of the gesture.
 *
 * Each row is its own white card ([Radius.card] corners), [gap] apart, as in the design.
 */
@Composable
fun <T> DragReorderList(
    items: List<T>,
    label: (T) -> String,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp = 8.dp,
    row: @Composable (item: T, lifted: Boolean) -> Unit,
) {
    val c = Omakey.colors
    val haptics = LocalHapticFeedback.current
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    // Per position, kept across size changes (an import while the list is open), so rows that
    // didn't re-measure keep their real height instead of reading as zero.
    val measured = remember { androidx.compose.runtime.mutableStateMapOf<Int, Float>() }
    val heights = items.indices.map { measured[it] ?: 0f }
    // Set when a long press lifts a row, so the finger coming up doesn't also count as a tap on it.
    val liftedThisGesture = remember { BooleanArray(1) }
    var dragging by remember { mutableIntStateOf(-1) }
    var dy by remember { mutableFloatStateOf(0f) }
    var slot by remember { mutableIntStateOf(-1) }
    val currentItems by rememberUpdatedState(items)
    val currentOnMove by rememberUpdatedState(onMove)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        items.forEachIndexed { index, item ->
            val lifted = index == dragging
            val shift = if (dragging >= 0 && !lifted) {
                Reorder.shiftFor(index, dragging, slot, heights.getOrElse(dragging) { 0f }, gapPx)
            } else 0f
            val animatedShift by animateFloatAsState(shift, label = "shift")
            val elevation by animateDpAsState(if (lifted) 12.dp else 0.dp, label = "lift")
            val scale by animateFloatAsState(if (lifted) 1.02f else 1f, label = "scale")
            Box(
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (lifted) 1f else 0f)
                    .onSizeChanged { measured[index] = it.height.toFloat() }
                    .graphicsLayer {
                        translationY = if (lifted) dy else animatedShift
                        scaleX = scale
                        scaleY = scale
                    }
                    .shadow(elevation, RoundedCornerShape(Radius.card), clip = false)
                    .clip(RoundedCornerShape(Radius.card))
                    .background(c.surface)
                    // Initial pass, ahead of the row's own click: once a long press has lifted
                    // the row, the release is consumed, so holding and letting go without moving
                    // doesn't toggle or open the row underneath.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            liftedThisGesture[0] = false
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (liftedThisGesture[0]) event.changes.forEach { if (it.changedToUpIgnoreConsumed()) it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(index, items.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                liftedThisGesture[0] = true
                                dragging = index
                                slot = index
                                dy = 0f
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dy += amount.y
                                val next = Reorder.slotFor(dragging, dy, heights, gapPx)
                                if (next != slot) {
                                    slot = next
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onDragEnd = {
                                val from = dragging
                                val to = slot
                                dragging = -1
                                dy = 0f
                                if (from >= 0 && to >= 0 && from != to) currentOnMove(from, to)
                            },
                            // The consumed release (above) can end the drag as a cancel; it is still
                            // the user letting go, so the row lands where it was dropped.
                            onDragCancel = {
                                val from = dragging
                                val to = slot
                                dragging = -1
                                dy = 0f
                                if (from >= 0 && to >= 0 && from != to) currentOnMove(from, to)
                            },
                        )
                    }
                    // Merged, so TalkBack lands on one node per row that carries both the row's
                    // own action and Move up / Move down.
                    .semantics(mergeDescendants = true) {
                        val name = label(item)
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction("Move $name up") { currentOnMove(index, index - 1); true })
                            if (index < currentItems.size - 1) add(CustomAccessibilityAction("Move $name down") { currentOnMove(index, index + 1); true })
                        }
                    },
            ) {
                row(item, lifted)
            }
        }
    }
}
