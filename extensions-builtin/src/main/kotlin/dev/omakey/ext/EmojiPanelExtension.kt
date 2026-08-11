package dev.omakey.ext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omakey.extapi.ExtensionContext
import dev.omakey.extapi.ExtensionHost
import dev.omakey.extapi.ExtensionIcon
import dev.omakey.extapi.OmakeyExtension

/** Bundled static emoji data, no network. Recents tracking deferred past v1 (small nice-to-have). */
class EmojiPanelExtension : OmakeyExtension {
    override val id = "builtin.emoji"
    override val displayName = "Emoji"
    override val icon = ExtensionIcon.Emoji("😊")

    override fun onAttach(context: ExtensionContext) = Unit
    override fun onDetach() = Unit

    @Composable
    override fun PanelContent(host: ExtensionHost) {
        LazyVerticalGrid(columns = GridCells.Fixed(8), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            items(BundledEmoji.all) { emoji ->
                Text(
                    text = emoji,
                    modifier = Modifier
                        .clickable { host.insertText(emoji) }
                        .padding(6.dp),
                )
            }
        }
    }
}

private object BundledEmoji {
    val all = listOf(
        "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😎", "🤔", "😴",
        "👍", "👎", "👏", "🙏", "💪", "🔥", "✨", "🎉", "❤️", "💯",
        "😢", "😡", "😭", "😅", "🙄", "😬", "🤷", "🤦", "👀", "✅",
    )
}
