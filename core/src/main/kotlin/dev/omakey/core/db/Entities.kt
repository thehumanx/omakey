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
    val content: String,
    val timestamp: Long,
    val pinned: Boolean = false,
)
