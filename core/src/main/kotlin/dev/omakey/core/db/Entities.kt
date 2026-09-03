package dev.omakey.core.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A word the user taught the keyboard. Since the bundled vocabulary moved to the memory-mapped
 * language model asset, every row here is user data — [isUserAdded] is retained because rows
 * written by older versions (which seeded 60,000 dictionary words into this same table) are
 * deleted by migration 2→3 on the strength of that flag.
 *
 * [frequency] counts the user's own uses and is meaningful only relative to other rows in this
 * table. It is deliberately never compared against the bundled model's probabilities: the previous
 * design did exactly that, in a column where seeded values ran to 60,000, so a word the user saved
 * arrived with a count of 1 and could never outrank one they had never typed.
 */
@Entity(tableName = "words")
data class WordEntity(
    @PrimaryKey val word: String,
    /** Decayed use count, scaled by [COUNT_SCALE] so a fractional count survives an `Int` column. */
    val frequency: Int,
    val isUserAdded: Boolean,
    val lastUsedTimestamp: Long,
    /** True when the user saved this deliberately (the swipe-up gesture), false when it was picked
     * up from ordinary typing. Decides whether autocorrect may treat the word as real on sight —
     * see [dev.omakey.core.predict.PersonalLanguageModel], where knowing a word and trusting it are
     * deliberately different things. */
    val explicit: Boolean = true,
) {
    companion object {
        /** Fixed-point scale for [frequency]. Counts decay continuously, and rounding a count of 1
         * to an integer on every write would quantise the decay away entirely. */
        const val COUNT_SCALE = 100f
    }
}

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
