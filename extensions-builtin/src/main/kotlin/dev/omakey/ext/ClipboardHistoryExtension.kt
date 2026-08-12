package dev.omakey.ext

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.omakey.core.theme.LocalOmakeyTheme
import dev.omakey.extapi.ClipboardContentType
import dev.omakey.extapi.ClipboardItem
import dev.omakey.extapi.ExtensionContext
import dev.omakey.extapi.ExtensionHost
import dev.omakey.extapi.ExtensionIcon
import dev.omakey.extapi.OmakeyExtension
import kotlinx.coroutines.launch

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
        val textColor = LocalOmakeyTheme.current.keyTextColor.let { androidx.compose.ui.graphics.Color(it.argb.toInt()) }

        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (items.isEmpty()) {
                Text("No clipboard history yet", color = textColor)
            } else {
                LazyColumn {
                    items(items, key = { it.id }) { item ->
                        val rowModifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { if (item.contentType == ClipboardContentType.TEXT) host.insertText(item.content) },
                                onLongClick = { itemToDelete = item },
                            )
                            .background(androidx.compose.ui.graphics.Color.Transparent)
                            .padding(8.dp)
                        if (item.contentType == ClipboardContentType.IMAGE && item.imagePath != null) {
                            val bitmap = remember(item.imagePath) {
                                android.graphics.BitmapFactory.decodeFile(item.imagePath)
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Copied image",
                                    contentScale = ContentScale.Fit,
                                    modifier = rowModifier.height(80.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                                )
                            }
                        } else {
                            Text(
                                text = item.content,
                                color = textColor,
                                modifier = rowModifier,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }

        itemToDelete?.let { item ->
            AlertDialog(
                onDismissRequest = { itemToDelete = null },
                title = { Text(text = "Remove this item?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            itemToDelete = null
                            scope.launch {
                                extensionContext?.clipboardRepository?.delete(item.id)
                                reload()
                            }
                        },
                    ) { Text(text = "Remove") }
                },
                dismissButton = {
                    TextButton(onClick = { itemToDelete = null }) { Text(text = "Cancel") }
                },
            )
        }
    }
}
