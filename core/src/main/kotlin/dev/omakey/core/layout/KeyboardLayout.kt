package dev.omakey.core.layout

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

enum class KeyType { CHARACTER, SPECIAL, SPACER }

/** Special key codes, negative to avoid colliding with Unicode character codes used for CHARACTER keys. */
object SpecialKeyCode {
    const val SHIFT = -1
    const val BACKSPACE = -2
    const val SPACE = -3
    const val ENTER = -4
    const val SYMBOLS = -5
    const val LETTERS = -6
    const val EXTENSIONS = -7
    /** Opens Settings — only ever placed on the Symbols1/Symbols2 layouts, in the exact slot
     * QwertyEnUS's emoji-launcher key (EXTENSIONS) occupies, so switching between letters and
     * symbols doesn't shift every other key's width (see Layouts.kt's Symbols1/Symbols2 bottom
     * row — they used to simply omit that slot entirely, one widthWeight short of QwertyEnUS's
     * total). */
    const val SETTINGS = -8
}

// @Immutable is a promise to the Compose compiler that instances never change after construction
// (true here — every field is a val, all-the-way down). Without it, Compose treats any List<T>
// parameter as unstable and can never skip recomposing a composable that takes one, regardless of
// how well lambda parameters elsewhere are memoized.
@Immutable
@Serializable
data class KeyDefinition(
    val label: String,
    val code: Int,
    val popupChars: List<String> = emptyList(),
    val widthWeight: Float = 1f,
    val keyType: KeyType = KeyType.CHARACTER,
)

@Immutable
@Serializable
data class KeyRow(val keys: List<KeyDefinition>)

@Immutable
@Serializable
data class KeyboardLayout(val id: String, val rows: List<KeyRow>)

/** Computes per-key pixel widths for a row given the available width, preserving widthWeight proportions. */
fun KeyRow.computeKeyWidthsPx(availableWidthPx: Float): List<Float> {
    val totalWeight = keys.sumOf { it.widthWeight.toDouble() }.toFloat()
    if (totalWeight <= 0f) return keys.map { 0f }
    return keys.map { availableWidthPx * (it.widthWeight / totalWeight) }
}
