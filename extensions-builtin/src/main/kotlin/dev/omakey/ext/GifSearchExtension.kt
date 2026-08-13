package dev.omakey.ext

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omakey.extapi.ExtensionContext
import dev.omakey.extapi.ExtensionHost
import dev.omakey.extapi.ExtensionIcon
import dev.omakey.extapi.OmakeyExtension

/**
 * Stub only for v1. A working GIF search needs the INTERNET permission, which contradicts the
 * offline-by-default decision for the base app. Ships the UI shell/extension point disabled so
 * a future deliberate, explicit opt-in networking build (or flavor) can wire in a real backend
 * without restructuring the extension system.
 */
class GifSearchExtension : OmakeyExtension {
    override val id = "builtin.gif"
    override val displayName = "GIFs"
    override val icon = ExtensionIcon.Emoji("🎬")

    override fun onAttach(context: ExtensionContext) = Unit
    override fun onDetach() = Unit

    @Composable
    override fun PanelContent(host: ExtensionHost) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("GIF search coming soon")
            Text("omakey is offline by default — this needs a network connection to work.")
        }
    }
}
