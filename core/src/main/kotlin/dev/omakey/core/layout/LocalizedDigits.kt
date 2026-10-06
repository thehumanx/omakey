package dev.omakey.core.layout

import dev.omakey.core.locale.LanguageProfile

/**
 * [this] with every Western digit key ("0"–"9") retyped in [digits], keeping the Western digit
 * first in its long-press so both stay one gesture away. The same key positions and widths, so
 * switching language never moves a key.
 *
 * Applied to the bundled number row and symbols page, which are shared by every language: before
 * this they were hardcoded 0–9, so Nepali typed "123" where a Nepali keyboard types "१२३".
 * Identity (the same instance) when [digits] are already Western, which is every language but
 * the Devanagari ones.
 */
fun KeyRow.withDigits(digits: String): KeyRow {
    if (digits == LanguageProfile.WESTERN_DIGITS) return this
    return KeyRow(keys.map { it.withDigit(digits) })
}

fun KeyboardLayout.withDigits(digits: String): KeyboardLayout {
    if (digits == LanguageProfile.WESTERN_DIGITS) return this
    return copy(rows = rows.map { it.withDigits(digits) })
}

private fun KeyDefinition.withDigit(digits: String): KeyDefinition {
    val western = label.singleOrNull()?.takeIf { it in '0'..'9' } ?: return this
    if (keyType != KeyType.CHARACTER || text != null) return this
    val native = digits[western - '0'].toString()
    return copy(label = native, code = native.codePointAt(0), popupChars = (listOf(western.toString()) + popupChars).distinct() - native)
}
