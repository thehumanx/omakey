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
    /** Overrides what actually gets committed on tap when it's more than [code]'s single Unicode
     * codepoint can represent — e.g. a Devanagari conjunct cluster ("त्र", 3 codepoints) assigned
     * to one key on the Nepali Traditional layout. Null (true for every English/Symbols key)
     * means "commit Character.toChars(code)" exactly as before — [code] still serves as this
     * key's unique hit-test/identity value either way, it just isn't necessarily what gets typed
     * once [text] is set. */
    val text: String? = null,
    /** Overrides both this key's rendered label and its committed output while shift is engaged,
     * for layouts (like Nepali Traditional) where the shifted character isn't a Unicode case
     * mapping of the unshifted one (e.g. "q"->त्र vs "Q"->त्त — unrelated conjuncts, not a
     * lower/uppercase pair). Null (true for every English/Symbols key) preserves the existing
     * behavior: shift is applied generically at commit time via Char.uppercaseChar()/the key's
     * own label case, not per-key data. */
    val shiftedText: String? = null,
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
