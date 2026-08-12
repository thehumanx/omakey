package dev.omakey.extapi

import androidx.compose.runtime.Composable

sealed interface ExtensionIcon {
    data class Emoji(val glyph: String) : ExtensionIcon
    data class VectorResource(val resId: Int) : ExtensionIcon
}

/**
 * A restricted subset of TextEditor exposed to extensions. Extensions never get raw
 * InputConnection or the full TextEditor — this enforces least-privilege now and is the
 * natural seed of an eventual IPC boundary if third-party extension APKs are supported later.
 */
interface TextEditorFacade {
    fun insertText(text: String)
    fun deleteBackward(count: Int = 1)
}

interface ClipboardRepository {
    suspend fun recent(limit: Int = 50): List<ClipboardItem>
    suspend fun pin(id: Long, pinned: Boolean)
    suspend fun delete(id: Long)
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

interface ExtensionContext {
    val textEditor: TextEditorFacade
    val clipboardRepository: ClipboardRepository
    fun requestPanelClose()
}

interface ExtensionHost {
    fun insertText(text: String)
    fun close()
}

/**
 * Contract for a keyboard extension (clipboard history, emoji panel, etc). In-process
 * implementations for v1 — no IPC. Rendered into a fixed panel slot above the key rows, never
 * an arbitrary overlay, so touch regions stay predictable and don't fight the gesture engine.
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
