package dev.omakey.core.layout

/**
 * The user's own long-press symbols ("Long-press symbols" in Settings), read from what they typed:
 * one symbol per character, so "@#$_&" and "@ # $ _ &" are the same five. Whole characters, not
 * code points, so an emoji with a skin tone or a flag stays one symbol (see [characters]).
 * Duplicates are dropped and at most [MAX_POPUP_SYMBOLS] are kept — one strip, and past that the
 * cells get too narrow to hit.
 */
fun parsePopupSymbols(input: String): List<String> {
    val symbols = LinkedHashSet<String>()
    for (symbol in characters(input)) {
        if (symbols.size >= MAX_POPUP_SYMBOLS) break
        if (symbol.isNotBlank()) symbols += symbol
    }
    return symbols.toList()
}

/**
 * The symbols in [input] exactly as typed (repeats included, spaces dropped), or null when it isn't
 * a list [parsePopupSymbols] would keep whole — more than [MAX_POPUP_SYMBOLS], or a symbol twice.
 * The Settings field refuses an edit that returns null, so what it shows is always what is saved;
 * before, an 11th symbol or a repeat could be typed and then silently vanished.
 */
fun popupSymbolsAsTyped(input: String): List<String>? {
    val typed = characters(input).filter { it.isNotBlank() }
    return typed.takeIf { it.size <= MAX_POPUP_SYMBOLS && it.toSet().size == it.size }
}

/**
 * [text] split into what a reader sees as single characters. Not `java.text.BreakIterator`: the
 * JDK's before 20 splits emoji sequences apart while Android's keeps them, and this has to behave
 * the same in tests as on the phone. Covers what a symbol list meets: combining and enclosing
 * marks (keycaps), variation selectors, skin tones, ZWJ sequences, flag pairs and tag sequences.
 */
internal fun characters(text: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var joinNext = false
    var regionalIndicators = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        index += Character.charCount(codePoint)
        val regional = codePoint in 0x1F1E6..0x1F1FF
        val extends = joinNext ||
            codePoint == 0x200D ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint in 0x1F3FB..0x1F3FF ||
            codePoint in 0xE0020..0xE007F ||
            Character.getType(codePoint).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() } ||
            (regional && regionalIndicators % 2 == 1)
        if (!extends && current.isNotEmpty()) {
            result += current.toString()
            current.clear()
            regionalIndicators = 0
        }
        current.appendCodePoint(codePoint)
        if (regional) regionalIndicators++
        joinNext = codePoint == 0x200D
    }
    if (current.isNotEmpty()) result += current.toString()
    return result
}

const val MAX_POPUP_SYMBOLS = 10

/**
 * This layout with every letter key's long-press options replaced by exactly [symbols], in that
 * order. Empty [symbols] leaves the layout as it is.
 *
 * The user asked for "exact": a held letter offers these and nothing else, so its own accents (é on
 * e) and digit go while this is set — Settings says so. Keys that aren't letters keep theirs: the
 * "." key's punctuation list and the digits are lists of their own, not per-letter extras. A symbol
 * equal to the letter itself is left out, since the letter is already the popup's first option.
 */
fun KeyboardLayout.withPopupSymbols(symbols: List<String>): KeyboardLayout {
    if (symbols.isEmpty()) return this
    return copy(
        rows = rows.map { row ->
            KeyRow(
                row.keys.map { key ->
                    if (key.keyType != KeyType.CHARACTER || !key.label.first().isLetter()) {
                        key
                    } else {
                        key.copy(popupChars = symbols.filter { it != key.label && it != key.committedText })
                    }
                },
            )
        },
    )
}
