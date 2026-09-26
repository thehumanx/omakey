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
    /** Switches to the next enabled language; long-press opens the language picker. Never part of
     * a stored layout: normally the language button lives in the suggestion bar, and this key only
     * replaces the emoji key when the user swaps the two (see `withLanguageKeyInsteadOfEmoji`). */
    const val LANGUAGE = -9
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
    /** What the key types, when that is more than the single character [code] names — a Devanagari
     * conjunct such as "क्ष" is three code points. Null means "the character [code]". A key with
     * text needs a [code] unique within its layout, since taps are identified by code; see
     * [KeyboardLayout.validate]. */
    val text: String? = null,
) {
    /** The string a tap on this key commits. */
    val committedText: String get() = text ?: String(Character.toChars(code))
}

@Immutable
@Serializable
data class KeyRow(val keys: List<KeyDefinition>)

@Immutable
@Serializable
data class KeyboardLayout(
    val id: String,
    val rows: List<KeyRow>,
    /** Row that gets the home-row tint and the swipe-delete shimmer; -1 for none (symbols pages). */
    val homeRow: Int = -1,
    /** For languages without letter case: the layout Shift shows instead of uppercasing, e.g. a
     * Devanagari layout's aspirate/retroflex layer. Null means Shift changes case, as in English. */
    val shiftLayoutId: String? = null,
    /** Latin keys whose typing is transliterated into the language's own script (Nepali typed in
     * Latin letters, shown in Devanagari). See `TransliterationSession`. */
    val transliteration: Boolean = false,
    /** Name shown where the user picks between layouts; derived from the keys when absent. */
    val displayName: String? = null,
) {
    fun keyForCode(code: Int): KeyDefinition? {
        for (row in rows) for (key in row.keys) if (key.code == code) return key
        return null
    }

    /**
     * Structural problems that would make this layout misbehave, empty if there are none. Checked
     * for every bundled layout by a test and, from AGENTS.md §66 Phase 7, for every layout a
     * language pack brings — a pack is data from the network, and a malformed layout must be
     * rejected at install rather than discovered as a keyboard that types the wrong thing.
     */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (id.isBlank()) problems += "blank id"
        if (rows.isEmpty()) problems += "no rows"
        if (homeRow !in -1 until rows.size) problems += "homeRow $homeRow is not a row"
        val codes = HashSet<Int>()
        for ((rowIndex, row) in rows.withIndex()) {
            if (row.keys.isEmpty()) problems += "row $rowIndex is empty"
            for (key in row.keys) {
                val where = "row $rowIndex key '${key.label}'"
                if (!(key.widthWeight > 0f) || key.widthWeight.isInfinite()) problems += "$where: bad widthWeight ${key.widthWeight}"
                if (key.keyType == KeyType.SPACER) continue
                if (!codes.add(key.code)) problems += "$where: duplicate code ${key.code}"
                if (key.keyType == KeyType.CHARACTER) {
                    if (key.text != null) {
                        if (key.text.isEmpty()) problems += "$where: empty text"
                    } else if (key.code < 0 || !Character.isValidCodePoint(key.code)) {
                        problems += "$where: code ${key.code} is not a character and there is no text"
                    }
                }
            }
        }
        val all = rows.flatMap { it.keys }
        if (all.count { it.code == SpecialKeyCode.SPACE } != 1) problems += "needs exactly one space key"
        if (all.count { it.code == SpecialKeyCode.BACKSPACE } != 1) problems += "needs exactly one backspace key"
        return problems
    }
}

/** Computes per-key pixel widths for a row given the available width, preserving widthWeight proportions. */
fun KeyRow.computeKeyWidthsPx(availableWidthPx: Float): List<Float> {
    val totalWeight = keys.sumOf { it.widthWeight.toDouble() }.toFloat()
    if (totalWeight <= 0f) return keys.map { 0f }
    return keys.map { availableWidthPx * (it.widthWeight / totalWeight) }
}
