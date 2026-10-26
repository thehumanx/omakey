package dev.omakey.core.layout

import android.text.InputType

/** The fields that get a number pad instead of letters, by their input class. */
enum class NumberPadKind { NUMBER, PHONE, DATETIME }

/**
 * Number pads for fields that only take numbers: one 4×5 grid per [NumberPadKind], with the
 * digits in the middle, the field's own symbols down the left and along the bottom, and space,
 * backspace and enter down the right. There is no key back to letters — a field that asks for
 * numbers gets numbers, and a field that is mis-tagged is what the settings are for.
 *
 * Backspace and enter sit at the ends of the rows where the letter layout has them (second-to-last
 * and last), and every column is backspace's width on the letter layout — on QWERTY and on ЙЦУКЕН
 * alike, which is why it is read from the letter layout rather than fixed. Enter keeps that width
 * too, not its own: on letters it is a little wider, and matching it made the bottom row's columns
 * stop lining up with the rows above (developer, on the phone).
 */
object NumberPads {
    const val NUMBER_ID = "numpad_number"
    const val PHONE_ID = "numpad_phone"
    const val DATETIME_ID = "numpad_datetime"
    val IDS = setOf(NUMBER_ID, PHONE_ID, DATETIME_ID)

    /** Drawn on the pad's small space key; the spacebar proper has no glyph. */
    const val SPACE_LABEL = "␣"

    /** QWERTY's share, for a letter layout whose backspace isn't last in its row. */
    private const val DEFAULT_BACKSPACE_SHARE = 1.5f / 10f

    /** Bounds on a share read from a layout, so a pack layout with an odd key can't squeeze the
     * digits to nothing or the side keys to slivers. */
    private const val MIN_SHARE = 0.08f
    private const val MAX_SHARE = 0.25f

    private val PHONE_LETTERS = mapOf(
        "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL",
        "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    )

    /** Which pad, if any, a field with [inputType] opens on, given what [settings] allow. */
    fun kindFor(inputType: Int, settings: LayoutSettings): NumberPadKind? {
        if (!settings.numberPad) return null
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NumberPadKind.NUMBER.takeIf { settings.numberPadNumber }
            InputType.TYPE_CLASS_PHONE -> NumberPadKind.PHONE.takeIf { settings.numberPadPhone }
            InputType.TYPE_CLASS_DATETIME -> NumberPadKind.DATETIME.takeIf { settings.numberPadDateTime }
            else -> null
        }
    }

    /**
     * The [kind] pad, its side columns as wide as [letters]' backspace.
     *
     * The keys type 0–9 whatever the language, with the language's own [digits] one long-press
     * away. The opposite of the number row on purpose: a field that only takes numbers almost always
     * only takes these (Android's number and phone filters, `<input type=number>`, SMS-code boxes),
     * so a Nepali pad typing "१२३" would type nothing in exactly the fields it exists for.
     */
    fun build(kind: NumberPadKind, letters: KeyboardLayout, digits: String): KeyboardLayout {
        val side = endShare(letters, SpecialKeyCode.BACKSPACE, letters.rows.size - 2, DEFAULT_BACKSPACE_SHARE)
        val spec = when (kind) {
            NumberPadKind.NUMBER -> Spec(NUMBER_ID, left = listOf("-", "+", "*", "/"), rightTop = "%", beside0 = "," to ".")
            NumberPadKind.PHONE -> Spec(PHONE_ID, left = listOf("+", "-", "(", ")"), rightTop = ".", beside0 = "*" to "#")
            NumberPadKind.DATETIME -> Spec(DATETIME_ID, left = listOf("-", "+", ",", "/"), rightTop = "'", beside0 = "." to ":")
        }
        val phone = kind == NumberPadKind.PHONE
        fun digit(d: String, weight: Float) = KeyDefinition(
            label = d,
            code = d.codePointAt(0),
            widthWeight = weight,
            popupChars = listOfNotNull(
                "+".takeIf { phone && d == "0" },
                digits[d.single() - '0'].toString().takeIf { it != d },
            ),
            hint = if (phone) PHONE_LETTERS[d] else null,
        )
        fun symbol(s: String, weight: Float) = KeyDefinition(label = s, code = s.codePointAt(0), widthWeight = weight)
        fun special(label: String, code: Int, weight: Float) =
            KeyDefinition(label, code, widthWeight = weight, keyType = KeyType.SPECIAL)

        // Every row's weights sum to 1 and are the same: a side key at either end, three equal keys
        // between them, so the five columns line up from top to bottom.
        val middle = (1f - 2 * side) / 3
        val upperRights = listOf(
            symbol(spec.rightTop, side),
            special(SPACE_LABEL, SpecialKeyCode.SPACE, side),
            special("⌫", SpecialKeyCode.BACKSPACE, side),
        )
        val upperRows = (0..2).map { row ->
            KeyRow(
                listOf(symbol(spec.left[row], side)) +
                    (1..3).map { digit("${row * 3 + it}", middle) } +
                    upperRights[row],
            )
        }
        val bottomRow = KeyRow(
            listOf(
                symbol(spec.left[3], side),
                symbol(spec.beside0.first, middle),
                digit("0", middle),
                symbol(spec.beside0.second, middle),
                special("⏎", SpecialKeyCode.ENTER, side),
            ),
        )
        return KeyboardLayout(id = spec.id, rows = upperRows + bottomRow, displayName = "Number pad")
    }

    /** The share of its row [code] takes in [layout] when it is the last key of row [rowIndex]. */
    private fun endShare(layout: KeyboardLayout, code: Int, rowIndex: Int, fallback: Float): Float {
        val row = layout.rows.getOrNull(rowIndex) ?: return fallback
        val key = row.keys.lastOrNull()?.takeIf { it.code == code } ?: return fallback
        val total = row.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
        if (!(total > 0f)) return fallback
        return (key.widthWeight / total).coerceIn(MIN_SHARE, MAX_SHARE)
    }

    private class Spec(val id: String, val left: List<String>, val rightTop: String, val beside0: Pair<String, String>)
}
