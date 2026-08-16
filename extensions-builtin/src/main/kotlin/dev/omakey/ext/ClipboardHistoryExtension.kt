package dev.omakey.ext

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.omakey.core.theme.LocalKeyboardLayoutMode
import dev.omakey.core.theme.LocalOmakeyTheme
import dev.omakey.core.theme.LayoutMode
import dev.omakey.extapi.ClipboardContentType
import dev.omakey.extapi.ClipboardItem
import dev.omakey.extapi.ExtensionContext
import dev.omakey.extapi.ExtensionHost
import dev.omakey.extapi.ExtensionIcon
import dev.omakey.extapi.OmakeyExtension
import kotlinx.coroutines.launch

private fun dev.omakey.core.theme.ColorSpec.toComposeColor() = Color(argb.toInt())

private fun dev.omakey.core.theme.GridBorderWidth.toDp(): androidx.compose.ui.unit.Dp = when (this) {
    dev.omakey.core.theme.GridBorderWidth.SM -> 1.dp
    dev.omakey.core.theme.GridBorderWidth.MD -> 1.5.dp
    dev.omakey.core.theme.GridBorderWidth.LG -> 2.5.dp
}

// Same single-draw border model as KeyboardRoot.kt/EmojiPanelExtension.kt — every cell draws only
// its own right+bottom edge (so adjacent cells never double up).
private fun Modifier.gridCellBorder(color: Color, strokeWidth: androidx.compose.ui.unit.Dp): Modifier = this.drawBehind {
    val strokePx = strokeWidth.toPx()
    val half = strokePx / 2f
    drawLine(color, androidx.compose.ui.geometry.Offset(size.width - half, 0f), androidx.compose.ui.geometry.Offset(size.width - half, size.height), strokePx)
    drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height - half), androidx.compose.ui.geometry.Offset(size.width, size.height - half), strokePx)
}

/**
 * Reads clipboard history while the IME is the active input source. No special runtime permission
 * is required for this on modern Android since foreground-IME clipboard reads are permitted
 * (Android 12+ shows its standard clipboard-read toast, which is expected, not a bug).
 */
class ClipboardHistoryExtension : OmakeyExtension {
    override val id = "builtin.clipboard"
    override val displayName = "Clipboard"
    override val icon = ExtensionIcon.Emoji("📋")

    private var extensionContext: ExtensionContext? = null

    override fun onAttach(context: ExtensionContext) {
        extensionContext = context
    }

    override fun onDetach() {
        extensionContext = null
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun PanelContent(host: ExtensionHost) {
        val scope = rememberCoroutineScope()
        var items by remember { mutableStateOf(emptyList<ClipboardItem>()) }
        var itemToDelete by remember { mutableStateOf<ClipboardItem?>(null) }

        suspend fun reload() {
            items = extensionContext?.clipboardRepository?.recent() ?: emptyList()
        }
        LaunchedEffect(Unit) { reload() }

        // Same fix as EmojiPanelExtension: Text() with no explicit color defaults to black outside
        // a Material theming ancestor, invisible against the dark keyboard background.
        val theme = LocalOmakeyTheme.current
        val textColor = theme.keyTextColor.toComposeColor()
        val isGridMode = LocalKeyboardLayoutMode.current == LayoutMode.GRID
        val gridBorderColor = theme.gridBorderColor.toComposeColor()
        val gridBorderWidth = theme.gridBorderWidth.toDp()

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(if (isGridMode) 0.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (items.isEmpty()) {
                    Text("No clipboard history yet", color = textColor, modifier = Modifier.padding(8.dp))
                } else if (isGridMode) {
                    // Real request: a proper multi-column grid in Grid mode, not the same
                    // single-column list Normal mode uses — every other screen of the keyboard is
                    // a bordered grid in this mode, and a clipboard list was the one place that
                    // still looked like a floating card list instead.
                    LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize()) {
                        gridItems(items, key = { it.id }) { item ->
                            ClipboardGridCell(
                                item = item,
                                textColor = textColor,
                                gridBorderColor = gridBorderColor,
                                gridBorderWidth = gridBorderWidth,
                                keyBackground = theme.keyboardBackground.toComposeColor(),
                                keyBackgroundPressed = theme.keyBackgroundPressed.toComposeColor(),
                                onClick = { if (item.contentType == ClipboardContentType.TEXT) host.insertText(item.content) },
                                onLongClick = { itemToDelete = item },
                            )
                        }
                    }
                } else {
                    LazyColumn {
                        items(items, key = { it.id }) { item ->
                            ClipboardListRow(
                                item = item,
                                textColor = textColor,
                                onClick = { if (item.contentType == ClipboardContentType.TEXT) host.insertText(item.content) },
                                onLongClick = { itemToDelete = item },
                            )
                        }
                    }
                }
            }

            // A plain Material3 AlertDialog used to sit here — a real bug, fixed: AlertDialog
            // creates its own separate Android Dialog window, and the IME's own content is
            // already hosted in an unusual Dialog-backed window of its own (see
            // OmakeyInputMethodService.onCreateInputView's comment on why it has to manually wire
            // ViewTreeLifecycleOwner/etc onto the decorView) — a *second*, system-created Dialog
            // window on top of that never got that same manual wiring, and threw on
            // recomposition once `itemToDelete` went non-null (long-press), which isn't caught by
            // ExtensionPanelSlot's own error boundary (that one only covers instantiation/first
            // composition, not later recomposition — see its own doc) and crashed the whole IME
            // process, reported as "long-press closes the keyboard." Replaced with a plain
            // in-tree overlay instead of a system Dialog — same trick every other popup in this
            // app (AccentDragPopup, SymbolModeOverlay, KeyboardRoot's own banner) already uses.
            itemToDelete?.let { item ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .combinedClickable(onClick = { itemToDelete = null }, onLongClick = {}),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .background(theme.keyboardBackground.toComposeColor(), RoundedCornerShape(12.dp))
                            .let { m -> if (isGridMode) m.border(gridBorderWidth, gridBorderColor, RoundedCornerShape(12.dp)) else m }
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(text = "Remove this item?", color = textColor)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { itemToDelete = null }, modifier = Modifier.weight(1f)) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    itemToDelete = null
                                    scope.launch {
                                        extensionContext?.clipboardRepository?.delete(item.id)
                                        reload()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipboardListRow(
    item: ClipboardItem,
    textColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .background(Color.Transparent)
        .padding(8.dp)
    val imagePath = item.imagePath
    if (item.contentType == ClipboardContentType.IMAGE && imagePath != null) {
        ClipboardImageOrPlaceholder(
            imagePath = imagePath,
            textColor = textColor,
            modifier = rowModifier.height(80.dp).background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
        )
    } else {
        Text(text = item.content, color = textColor, modifier = rowModifier, maxLines = 2)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipboardGridCell(
    item: ClipboardItem,
    textColor: Color,
    gridBorderColor: Color,
    gridBorderWidth: androidx.compose.ui.unit.Dp,
    keyBackground: Color,
    keyBackgroundPressed: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .combinedClickable(interactionSource = interactionSource, indication = null, onClick = onClick, onLongClick = onLongClick)
            .background(if (isPressed) keyBackgroundPressed else keyBackground)
            .gridCellBorder(gridBorderColor, gridBorderWidth)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val imagePath = item.imagePath
        if (item.contentType == ClipboardContentType.IMAGE && imagePath != null) {
            ClipboardImageOrPlaceholder(imagePath = imagePath, textColor = textColor, modifier = Modifier.fillMaxSize())
        } else {
            Text(text = item.content, color = textColor, maxLines = 4)
        }
    }
}

/** Always renders *something* for an image entry, even if the file can't be decoded (corrupt,
 * moved, or the copy step silently failed) — real bug, fixed: this used to render nothing at all
 * when `BitmapFactory.decodeFile` returned null, an empty, invisible list item indistinguishable
 * from the image just not being in the list — reported as "copied images don't show up." A
 * decode failure is now a visible, diagnosable placeholder instead of an invisible no-op. */
@Composable
private fun ClipboardImageOrPlaceholder(imagePath: String, textColor: Color, modifier: Modifier = Modifier) {
    val bitmap = remember(imagePath) { runCatching { android.graphics.BitmapFactory.decodeFile(imagePath) }.getOrNull() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Copied image",
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(text = "Image unavailable", color = textColor.copy(alpha = 0.6f))
        }
    }
}
