package dev.omakey.core.locale

/**
 * The flag emoji for a language id's region — "en_US" → 🇺🇸, "ne_NP" → 🇳🇵 — built from the two
 * regional-indicator letters, so every language pack gets its flag without a table to maintain.
 * Null when the id names no region.
 *
 * A flag stands for a country, not a language; it is shown next to the language's own name, never
 * instead of it, as a quick visual anchor in a list.
 */
fun flagEmoji(localeId: String): String? {
    val region = localeId.substringAfter('_', missingDelimiterValue = "").uppercase()
    if (region.length != 2 || !region.all { it in 'A'..'Z' }) return null
    return buildString {
        region.forEach { appendCodePoint(REGIONAL_INDICATOR_A + (it - 'A')) }
    }
}

private const val REGIONAL_INDICATOR_A = 0x1F1E6
