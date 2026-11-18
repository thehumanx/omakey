package dev.omakey.core.layout

/** No choice made: the key right of the spacebar types what the language's layout says ("." in
 * most, "।" in Nepali). Empty rather than "." so that the default never rewrites a language whose
 * key isn't a period. */
const val DEFAULT_SPACEBAR_SIDE_KEY = ""

/**
 * This layout with the character key right of the spacebar typing [character] instead.
 *
 * Applied at display time to whichever letter layout is showing, so every language — bundled or
 * from a pack — follows the setting without a pack update. The key keeps its code (taps and
 * long-press find keys by code) and the character it replaced moves to the front of its long-press
 * list, so nothing becomes unreachable: Nepali's "।" is still a hold away after choosing ",".
 *
 * Unchanged for [DEFAULT_SPACEBAR_SIDE_KEY], when the key already types [character], or when the
 * layout has no such key.
 */
fun KeyboardLayout.withSpacebarSideKey(character: String): KeyboardLayout {
    if (character == DEFAULT_SPACEBAR_SIDE_KEY) return this
    val bottom = rows.lastOrNull() ?: return this
    val space = bottom.keys.indexOfFirst { it.code == SpecialKeyCode.SPACE }
    if (space < 0) return this
    val index = (space + 1 until bottom.keys.size).firstOrNull { bottom.keys[it].keyType == KeyType.CHARACTER } ?: return this
    val key = bottom.keys[index]
    val replaced = key.committedText
    if (replaced == character) return this
    val swapped = key.copy(
        label = character,
        text = character,
        popupChars = (listOf(replaced) + key.popupChars).filter { it != character }.distinct(),
    )
    return copy(rows = rows.dropLast(1) + KeyRow(bottom.keys.toMutableList().also { it[index] = swapped }))
}

/** [value] if it is exactly one visible character (one grapheme, so "👍🏽" counts), else the default. */
fun sanitizeSpacebarSideKey(value: String?): String {
    if (value.isNullOrBlank()) return DEFAULT_SPACEBAR_SIDE_KEY
    // [characters], not java.text.BreakIterator: see its doc — the JDK splits "👍🏽" in two.
    return if (characters(value).size == 1 && value.none { it.isWhitespace() }) value else DEFAULT_SPACEBAR_SIDE_KEY
}
