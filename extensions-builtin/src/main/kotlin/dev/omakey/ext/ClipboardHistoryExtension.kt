package dev.omakey.ext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omakey.core.theme.LocalOmakeyTheme
import dev.omakey.extapi.ClipboardItem
import dev.omakey.extapi.ExtensionContext
import dev.omakey.extapi.ExtensionHost
import dev.omakey.extapi.ExtensionIcon
import dev.omakey.extapi.OmakeyExtension

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

    @Composable
    override fun PanelContent(host: ExtensionHost) {
        var items by remember { mutableStateOf(emptyList<ClipboardItem>()) }
        LaunchedEffect(Unit) {
            items = extensionContext?.clipboardRepository?.recent() ?: emptyList()
        }
        // Same fix as EmojiPanelExtension: Text() with no explicit color defaults to black outside
        // a Material theming ancestor, invisible against the dark keyboard background.
        val textColor = LocalOmakeyTheme.current.keyTextColor.let { androidx.compose.ui.graphics.Color(it.argb.toInt()) }

        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (items.isEmpty()) {
                Text("No clipboard history yet", color = textColor)
            } else {
                LazyColumn {
                    items(items) { item ->
                        Text(
                            text = item.content,
                            color = textColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { host.insertText(item.content) }
                                .background(androidx.compose.ui.graphics.Color.Transparent)
                                .padding(8.dp),
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}
