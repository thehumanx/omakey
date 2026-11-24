package dev.omakey.core.snippets

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Placeholders in a plain snippet, filled in at the moment it is typed (step 3), as text-expander
 * apps do and no keyboard does: `{date}` (the locale's medium date), `{time}` (its short time) and
 * `{clipboard}` (what is on the clipboard now, or nothing). `{{` and `}}` type a literal brace, and
 * an unknown `{word}` is typed as written. Secrets are never expanded; their text is typed exactly
 * as saved.
 */
object SnippetPlaceholders {

    fun expand(text: String, now: Date, locale: Locale, timeZone: TimeZone, clipboard: () -> String?): String {
        if ('{' !in text && '}' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '{' && text.startsWith("{{", i) -> { out.append('{'); i += 2 }
                c == '}' && text.startsWith("}}", i) -> { out.append('}'); i += 2 }
                c == '{' -> {
                    val close = text.indexOf('}', i + 1)
                    val value = if (close < 0) null else when (text.substring(i + 1, close)) {
                        "date" -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { this.timeZone = timeZone }.format(now)
                        "time" -> DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply { this.timeZone = timeZone }.format(now)
                        "clipboard" -> clipboard().orEmpty().take(SnippetRules.MAX_TEXT)
                        else -> null
                    }
                    if (value != null) { out.append(value); i = close + 1 } else { out.append(c); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        // However many placeholders it has, a snippet types no more than a snippet can hold: a
        // huge clip would otherwise become one giant commit across the binder.
        return out.toString().take(SnippetRules.MAX_TEXT)
    }
}
