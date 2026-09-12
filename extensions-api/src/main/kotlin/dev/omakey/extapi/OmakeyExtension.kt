package dev.omakey.extapi

import androidx.compose.runtime.Composable

/** [Emoji] is the only variant because it is the only one anything uses. A `VectorResource(resId)`
 * case existed here unused, which both claimed a capability the host had no code to render and
 * coupled this module to Android resource ids for no caller's benefit. */
sealed interface ExtensionIcon {
    data class Emoji(val glyph: String) : ExtensionIcon
}

/**
 * A restricted subset of TextEditor exposed to extensions. Extensions never get raw
 * InputConnection or the full TextEditor, which enforces least-privilege: an extension can put
 * text in and take text out, and cannot inspect the field, read surrounding text, or move the
 * cursor.
 *
 * This interface was described as "the natural seed of an eventual IPC boundary if third-party
 * extension APKs are supported later". It is not, and saying so was setting up a future that the
 * API's own shape rules out — see [OmakeyExtension.PanelContent]. The least-privilege argument
 * stands entirely on its own and is the real reason this exists.
 */
interface TextEditorFacade {
    fun insertText(text: String)
    fun deleteBackward(count: Int = 1)
}

interface ClipboardRepository {
    suspend fun recent(limit: Int = 50): List<ClipboardItem>
    suspend fun pin(id: Long, pinned: Boolean)
    suspend fun delete(id: Long)

    /** One-shot catch-up read of whatever's on the system clipboard right now, capturing it if
     * it's new. Call before [recent] when opening the clipboard panel, so content copied while
     * the keyboard wasn't on screen (the common case for images) is guaranteed present. */
    suspend fun captureCurrentClipboard()
}

enum class ClipboardContentType { TEXT, IMAGE }

data class ClipboardItem(
    val id: Long,
    val content: String,
    val timestamp: Long,
    val pinned: Boolean,
    val contentType: ClipboardContentType = ClipboardContentType.TEXT,
    /** Set only when [contentType] is [ClipboardContentType.IMAGE] — an app-private file path
     * (never a `content://` URI; those aren't guaranteed readable after the moment of capture). */
    val imagePath: String? = null,
)

interface EmojiRecentsRepository {
    /** Most-recently-used emoji first. */
    fun recent(): List<String>
    fun recordUse(emoji: String)
}

interface ExtensionContext {
    val textEditor: TextEditorFacade
    val clipboardRepository: ClipboardRepository
    val emojiRecents: EmojiRecentsRepository

    /**
     * [emoji] rendered in whatever skin tone the user picked in Settings, or unchanged if it
     * doesn't support tone selection.
     *
     * A function rather than the tone value itself, so the Unicode rules (which modifiers exist,
     * which emoji accept one, stripping before re-applying) live in one place instead of being
     * copied into every extension that shows an emoji.
     *
     * This used to be justified as preserving a "depends on nothing but the Kotlin stdlib"
     * boundary. That boundary does not exist — this module pulls in the Compose BOM and
     * `androidx.compose.ui`, in this very file. The design is still right, for the plainer reason
     * above: exposing the enum would mean either depending on `core` (which would drag Room and
     * kotlinx-serialization in behind it) or duplicating the tone rules per extension.
     *
     * Call it for display *and* for insertion, so what the user taps is what they get.
     */
    fun withSkinTone(emoji: String): String

    fun requestPanelClose()
}

interface ExtensionHost {
    fun insertText(text: String)
    fun close()
}

/**
 * Contract for a keyboard extension (clipboard history, emoji panel, etc). Rendered into a fixed
 * panel slot above the key rows, never an arbitrary overlay, so touch regions stay predictable and
 * don't fight the gesture engine.
 *
 * ### This module is an internal seam, not a plugin SDK
 *
 * Worth stating plainly, because the opposite was previously written down here and in AGENTS.md.
 * [PanelContent] is a `@Composable`, and a composable cannot cross a process boundary — so
 * third-party extension APKs are not reachable from this design at all, however the rest of the
 * API is shaped. Supporting them would mean replacing this function with something serialisable
 * (`RemoteViews`, a declarative description, or a hosted surface), which is a redesign of the
 * central interface rather than an extension of it.
 *
 * The module still earns its place: it keeps the built-in extensions from reaching into
 * `KeyboardViewModel` or `TextEditor` directly, and that constraint is what [TextEditorFacade] and
 * [ClipboardRepository] enforce. Judge it as that, and do not add API surface whose only
 * justification is a future IPC boundary.
 */
interface OmakeyExtension {
    val id: String
    val displayName: String
    val icon: ExtensionIcon

    fun onAttach(context: ExtensionContext)
    fun onDetach()

    @Composable
    fun PanelContent(host: ExtensionHost)
}

interface ExtensionRegistry {
    fun register(extension: OmakeyExtension)
    fun unregister(id: String)
    fun all(): List<OmakeyExtension>
    fun getById(id: String): OmakeyExtension?
}
