package dev.omakey.core.predict

/**
 * A deliberately small inline calculator — plain `+ - * /` with standard precedence and decimal
 * points, no parentheses, matching exactly what was asked for ("a simple addition, subtraction,
 * multiply and divide"). Triggered when '=' is typed (see `KeyboardViewModel.commitTypedChar`):
 * the trailing run of digits/operators/whitespace before the cursor is handed to [evaluate], and a
 * successful parse is offered as a suggestion-strip candidate — never auto-applied, same
 * "corrections are offered, not forced" convention as [AutocorrectIndex].
 *
 * Implementation is a textbook recursive-descent parser (expression -> term -> factor, so `*`/`/`
 * bind tighter than `+`/`-`) rather than a naive left-to-right scan, so "2+3*4" correctly
 * evaluates to 14, not 20.
 */
object Calculator {
    /** Returns null for anything that isn't a clean arithmetic expression — malformed input
     * (trailing operator, empty, division by zero, non-arithmetic text that happened to end in
     * digits) is a silent no-op rather than a wrong or crashing "correction". */
    fun evaluate(expression: String): Double? {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.all { it.isDigit() || it in "+-*/. " }) return null
        // Requires at least one operator — otherwise every plain number typed before an unrelated
        // "=" (e.g. a URL query string) would spuriously "evaluate" to itself.
        if (trimmed.none { it in "+-*/" }) return null

        val parser = Parser(trimmed.filterNot { it == ' ' })
        val result = runCatching { parser.parseExpression() }.getOrNull() ?: return null
        if (!parser.atEnd()) return null
        return if (result.isFinite()) result else null
    }

    /**
     * [expression] evaluated and formatted in the digits it was typed in: "१२+७" with Devanagari
     * [digits] gives "१९", so a Nepali sum is answered in Nepali. [digits] is the language's 0–9;
     * Western digits are always understood too. Null if it doesn't evaluate.
     */
    fun evaluateIn(expression: String, digits: String): String? {
        val western = buildString(expression.length) {
            for (c in expression) {
                val native = digits.indexOf(c)
                append(if (native >= 0) '0' + native else c)
            }
        }
        val result = formatResult(evaluate(western) ?: return null)
        val typedNative = expression.any { it in digits && it !in '0'..'9' }
        if (!typedNative) return result
        return buildString(result.length) { for (c in result) append(if (c in '0'..'9') digits[c - '0'] else c) }
    }

    /** Formats a successful [evaluate] result the way a person would type it — "19" not "19.0",
     * but "4.5" kept as-is. */
    fun formatResult(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private class Parser(private val input: String) {
        private var pos = 0

        fun atEnd() = pos >= input.length

        fun parseExpression(): Double {
            var value = parseTerm()
            while (!atEnd()) {
                when (input[pos]) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (!atEnd()) {
                when (input[pos]) {
                    '*' -> { pos++; value *= parseFactor() }
                    '/' -> { pos++; value /= parseFactor() }
                    else -> return value
                }
            }
            return value
        }

        private fun parseFactor(): Double {
            if (!atEnd() && input[pos] == '-') {
                pos++
                return -parseFactor()
            }
            val start = pos
            while (!atEnd() && (input[pos].isDigit() || input[pos] == '.')) pos++
            if (pos == start) error("expected a number at $pos")
            return input.substring(start, pos).toDouble()
        }
    }
}
