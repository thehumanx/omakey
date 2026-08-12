package dev.omakey.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "words")
data class WordEntity(
    @PrimaryKey val word: String,
    val frequency: Int,
    val isUserAdded: Boolean,
    val lastUsedTimestamp: Long,
)

@Entity(
    tableName = "bigrams",
    primaryKeys = ["previousWord", "word"],
    indices = [Index(value = ["previousWord"])],
)
data class BigramEntity(
    val previousWord: String,
    val word: String,
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
