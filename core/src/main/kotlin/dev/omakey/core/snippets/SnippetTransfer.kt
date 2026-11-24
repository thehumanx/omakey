package dev.omakey.core.snippets

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Export and import of snippets as a small JSON file (step 4), so they survive a new phone without
 * any cloud sync. **Secrets are never written**: an export file is plain text anyone with the file
 * can read. Import reads with `ignoreUnknownKeys`, so a file from a newer omakey still loads.
 */
object SnippetTransfer {

    const val FORMAT = "omakey-snippets"
    const val VERSION = 1

    @Serializable
    data class Entry(val name: String = "", val text: String = "", val shortcut: String? = null)

    @Serializable
    private data class File(val format: String = "", val version: Int = 0, val snippets: List<Entry> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /** The plain snippets in [items] (with their text) as an export file. Secrets are dropped here
     * too, whatever the caller passes in. */
    fun encode(items: List<Snippet>): String =
        json.encodeToString(
            File.serializer(),
            File(FORMAT, VERSION, items.filter { !it.secret && it.text != null }.map { Entry(it.name, it.text!!, it.shortcut) }),
        )

    /** The entries in an export file, or null when it isn't one omakey can read. Entries with no
     * text are dropped. */
    fun decode(text: String): List<Entry>? {
        val file = runCatching { json.decodeFromString(File.serializer(), text) }.getOrNull() ?: return null
        if (file.format != FORMAT || file.version !in 1..VERSION) return null
        return file.snippets.filter { it.text.isNotBlank() }
    }

    /** What an import does: [toAdd] in file order, [skipped] duplicates, and [codesDropped] short
     * codes already in use (the snippet is still imported, without its code). */
    data class Plan(val toAdd: List<Entry>, val skipped: Int, val codesDropped: Int, val shortened: Int = 0)

    /**
     * Skips an entry with the same name and text as a snippet already there (importing the same file
     * twice adds nothing). A short code already taken, by an existing snippet or an earlier entry
     * in the same file, is dropped rather than the snippet.
     */
    fun plan(existing: List<Snippet>, incoming: List<Entry>): Plan {
        val have = existing.filter { !it.secret }.map { it.name.trim() to it.text }.toMutableSet()
        val codes = existing.mapNotNull { it.shortcut?.lowercase() }.toMutableSet()
        val toAdd = mutableListOf<Entry>()
        var skipped = 0
        var dropped = 0
        var shortened = 0
        for (raw in incoming) {
            // A snippet holds up to MAX_TEXT; longer text is cut, and the result says so.
            val cut = raw.text.length > SnippetRules.MAX_TEXT
            val entry = if (cut) raw.copy(text = raw.text.take(SnippetRules.MAX_TEXT)) else raw
            val key = entry.name.trim() to entry.text
            if (key in have) { skipped++; continue }
            have += key
            val code = entry.shortcut?.trim()?.takeIf { it.isNotEmpty() }
            val usable = code?.takeIf { SnippetCodes.validate(it, emptyList(), null) == null && it.lowercase() !in codes }
            if (code != null && usable == null) dropped++
            usable?.let { codes += it.lowercase() }
            toAdd += entry.copy(shortcut = usable)
            if (cut) shortened++
        }
        return Plan(toAdd, skipped, dropped, shortened)
    }
}
