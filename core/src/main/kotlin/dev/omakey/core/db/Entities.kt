package dev.omakey.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// language is a Languages.<X>.id string (e.g. "en_us", "ne_np") — the composite primary key
// (rather than `word` alone) is what lets the same spelling exist independently per language,
// and lets every dictionary query stay scoped to whichever language is actually active instead of
// mixing every enabled language's words together (see OmakeyDatabase's MIGRATION_2_3).
@Entity(tableName = "words", primaryKeys = ["word", "language"])
data class WordEntity(
    val word: String,
    // Defaults to English ("en_us", matching Languages.EnglishUS.id — core.db deliberately
    // doesn't depend on core.language, so this is a literal, not a reference) purely so every
    // call site that predates multi-language support (most of this module's own tests included)
    // keeps compiling unchanged; every real caller post-dating it passes this explicitly.
    val language: String = "en_us",
    val frequency: Int,
    val isUserAdded: Boolean,
    val lastUsedTimestamp: Long,
)

@Entity(
    tableName = "bigrams",
    primaryKeys = ["previousWord", "word", "language"],
    indices = [Index(value = ["previousWord"])],
)
data class BigramEntity(
    val previousWord: String,
    val word: String,
    val language: String = "en_us", // see WordEntity.language's own doc
    val count: Int,
)

@Entity(tableName = "clipboard_history")
data class ClipboardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // For an image entry, this holds a short human-readable placeholder (e.g. "Image"), not the
    // pixel data — the actual bytes live on disk (see [imagePath]), never in the database.
    val content: String,
    val timestamp: Long,
    val pinned: Boolean = false,
    val contentType: String = TYPE_TEXT,
    // Set only when contentType == TYPE_IMAGE — an app-private file path the image bytes were
    // copied to immediately on capture (see OmakeyInputMethodService's clipboardListener for why:
    // the clipboard's own content:// URI grant isn't guaranteed to still be readable later).
    val imagePath: String? = null,
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
    }
}
