package dev.omakey.app.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.omakey.app.keyboard.KeyboardViewModel
import dev.omakey.core.gesture.GestureEvent
import dev.omakey.core.gesture.GestureStateMachine
import dev.omakey.core.gesture.GestureThresholds
import dev.omakey.core.gesture.KeyHitTester
import dev.omakey.core.gesture.SwipeDirection
import dev.omakey.core.gesture.TouchAction
import dev.omakey.core.gesture.TouchSample
import dev.omakey.core.layout.KeyDefinition
import dev.omakey.core.layout.KeyRow as LayoutKeyRow
import dev.omakey.core.layout.KeyType
import dev.omakey.core.layout.computeKeyWidthsPx
import dev.omakey.core.theme.ColorSpec
import dev.omakey.core.theme.OmakeyTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private fun ColorSpec.toComposeColor() = Color(argb.toInt())

/** Approximate keyboard height, per plan section 4 (screen-height ratio, simplified to a fixed
 * dp value for v1 pending per-device tuning). */
private const val KEYBOARD_HEIGHT_DP = 220

@Composable
fun KeyboardRoot(viewModel: KeyboardViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val theme = uiState.theme
    val scope = rememberCoroutineScope()

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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.keyboardBackground.toComposeColor()),
    ) {
        if (uiState.activeExtensionId != null) {
            ExtensionPanelSlot(viewModel)
        } else {
            SuggestionStrip(suggestions = uiState.suggestions, onAccept = viewModel::onSuggestionAccepted)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(KEYBOARD_HEIGHT_DP.dp)
                .onGloballyPositioned { keysAreaCoordinates = it }
                .pointerInput(uiState.layout.id) {
                    val thresholds = GestureThresholds(
                        touchSlopPx = 12f,
                        minSwipeDistancePxHorizontal = size.width * 0.25f,
                        minSwipeDistancePxVertical = size.height * 0.35f,
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
                                handleGestureEvent(event, viewModel)
                            }
                        }

                        var settled = false
                        while (!settled) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                            val now = System.currentTimeMillis()
                            if (!change.pressed) {
                                longPressJob.cancel()
                                val gestureEvent = machine.onTouch(
                                    TouchSample(change.position.x, change.position.y, now, TouchAction.UP),
                                )
                                handleGestureEvent(gestureEvent, viewModel)
                                settled = true
                            } else {
                                val gestureEvent = machine.onTouch(
                                    TouchSample(change.position.x, change.position.y, now, TouchAction.MOVE),
                                )
                                if (gestureEvent != null) longPressJob.cancel()
                                handleGestureEvent(gestureEvent, viewModel)
                            }
                        }
                    }
                },
        ) {
            Column(Modifier.fillMaxWidth().padding(PaddingValues(horizontal = 4.dp))) {
                uiState.layout.rows.forEachIndexed { rowIndex, row ->
                    KeyRowView(
                        rowKeys = row.keys,
                        rowIndex = rowIndex,
                        shiftOn = uiState.shiftOn,
                        theme = theme,
                        ancestorCoordinates = { keysAreaCoordinates },
                        onBoundsMeasured = { keyIndex, key, rect ->
                            keyBoundsState.value = keyBoundsState.value + (rowIndex * 1000 + keyIndex to (key to rect))
                        },
                    )
                }
            }
        }
    }
}

private fun handleGestureEvent(event: GestureEvent?, viewModel: KeyboardViewModel) {
    when (event) {
        is GestureEvent.KeyTap -> if (event.keyCode != 0) viewModel.onKeyTap(event.keyCode)
        is GestureEvent.Swipe -> when (event.direction) {
            SwipeDirection.LEFT -> viewModel.onSwipeLeft()
            SwipeDirection.RIGHT -> viewModel.onSwipeRight()
            SwipeDirection.UP -> viewModel.onSwipeUp()
            SwipeDirection.DOWN -> viewModel.onSwipeDown()
        }
        is GestureEvent.KeyLongPress -> Unit // accent popup wiring: M1 follow-up
        GestureEvent.GestureCancelled, null -> Unit
    }
}

@Composable
private fun KeyRowView(
    rowKeys: List<KeyDefinition>,
    rowIndex: Int,
    shiftOn: Boolean,
    theme: OmakeyTheme,
    ancestorCoordinates: () -> androidx.compose.ui.layout.LayoutCoordinates?,
    onBoundsMeasured: (Int, KeyDefinition, Rect) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
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
        Row(Modifier.fillMaxWidth()) {
            rowKeys.forEach { key ->
                val label = if (shiftOn && key.label.length == 1) key.label.uppercase() else key.label
                Box(
                    modifier = Modifier
                        .weight(key.widthWeight)
                        .fillMaxWidth()
                        .padding(2.dp)
                        .background(
                            if (key.keyType == KeyType.SPECIAL) {
                                theme.keySpecialBackground.toComposeColor()
                            } else {
                                theme.keyBackground.toComposeColor()
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = label, color = theme.keyTextColor.toComposeColor())
                }
            }
        }
    }
}

@Composable
private fun SuggestionStrip(suggestions: List<String>, onAccept: (String) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp)) {
        items(suggestions) { suggestion ->
            Box(
                Modifier
                    .padding(4.dp)
                    .clickable { onAccept(suggestion) },
            ) {
                Text(suggestion)
            }
        }
    }
}

@Composable
private fun ExtensionPanelSlot(viewModel: KeyboardViewModel) {
    val activeId = viewModel.uiState.collectAsState().value.activeExtensionId ?: return
    val extension = viewModel.extensionRegistry.getById(activeId) ?: return
    Box(Modifier.fillMaxWidth().height(160.dp)) {
        extension.PanelContent(host = viewModel.extensionHost)
    }
}
