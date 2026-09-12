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
 * Stub. Never registered in `LazyExtensionRegistry`, so this is unreachable at runtime.
 *
 * The reason recorded here used to be "needs the INTERNET permission, which contradicts the
 * offline-by-default decision" — that stopped being true when the update checker added `INTERNET`.
 * Keeping an expired blocker written down is worse than having none, so here is what actually
 * stands in the way, none of which is a small job:
 *
 * 1. **An API key that cannot be kept secret.** Tenor and Giphy both require one, and omakey is
 *    open-source and sideloaded — a key in the APK is a key anyone can extract and get revoked for
 *    abuse. The honest options are asking each user for their own key (which almost nobody will
 *    do), or running a proxy, which means operating a server that sees every search.
 * 2. **It sends what the user typed to a third party.** Qualitatively different from the update
 *    check, which is one call to a fixed URL carrying no user content. This is the part that
 *    genuinely tests "offline by default", not the permission.
 * 3. **Inserting the result is the hard half.** A GIF is not text: it needs
 *    `InputConnection.commitContent` with an `InputContentInfoCompat` and a content URI the target
 *    app can read, which means a `FileProvider` and temporary read grants — attack surface the
 *    updater deliberately avoided taking on (see `UpdateCheckResult.releaseUrl`). Most fields
 *    accept no image MIME type at all (`EditorInfoCompat.getContentMimeTypes`), so there has to be
 *    a defined fallback, normally "copied to clipboard instead".
 * 4. **Animated GIF decode and a disk cache**, which is a new image-loading dependency plus the
 *    same eviction problem clipboard images just had to solve.
 * 5. **Attribution**, required by both providers' terms.
 *
 * Left in place rather than deleted because the extension point and panel shell are the cheap part
 * and correctly shaped. Delete it if the answer to (1) and (2) turns out to be no.
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
            Text("Not built yet — searching would send what you type to a third-party GIF service.")
        }
    }
}
