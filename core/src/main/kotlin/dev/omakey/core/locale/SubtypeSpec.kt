package dev.omakey.core.locale

/**
 * What the system is told about one of omakey's languages: an input-method subtype.
 *
 * The system spellchecker checks text in the language of the keyboard's *current subtype*. omakey
 * used to declare a single English subtype, so typing Russian in Telegram was checked against an
 * English dictionary and every word got a red underline (closed-beta report). Declaring a subtype
 * per enabled language, and keeping the current one in step with the active language, was meant to
 * fix that. It doesn't on its own: Gboard's spell checker on "use system languages" ignored the
 * ru_RU subtype (Android 17), so `method.xml` now sets `suppressesSpellChecker`. The subtypes stay
 * for the system keyboard switcher and anything else that reads the keyboard's language.
 */
data class SubtypeSpec(
    val localeId: String,
    /** BCP 47 ("ru-RU"), what `InputMethodSubtypeBuilder.setLanguageTag` takes. */
    val languageTag: String,
    /** Stable across releases — see [idFor]. */
    val subtypeId: Int,
    val asciiCapable: Boolean,
    /** Shown in the system's keyboard switcher: the language's own name. */
    val label: String,
) {
    companion object {
        /**
         * FNV-1a (32-bit) of `"omakey:" + localeId`. **Frozen**: the system remembers which subtypes
         * the user enabled by this id, so changing the formula would silently drop every user's
         * languages from the system switcher. Not `String.hashCode`, which is fine in practice but
         * isn't a format this code controls.
         */
        fun idFor(localeId: String): Int {
            var hash = 0x811c9dc5.toInt()
            for (byte in "omakey:$localeId".toByteArray(Charsets.UTF_8)) {
                hash = (hash xor (byte.toInt() and 0xFF)) * 0x01000193
            }
            return hash
        }
    }
}

fun KeyboardLocale.subtypeSpec(): SubtypeSpec = SubtypeSpec(
    localeId = id,
    languageTag = id.replace('_', '-'),
    subtypeId = SubtypeSpec.idFor(id),
    asciiCapable = profile.script == Script.LATIN,
    label = nativeName,
)

/** The enabled language a system subtype's [languageTag] names: an exact match first ("ru-RU"),
 * then the language alone ("ru"). Null when none is enabled. */
fun localeIdForLanguageTag(languageTag: String, enabled: List<KeyboardLocale>): String? {
    if (languageTag.isBlank()) return null
    val normalised = languageTag.replace('_', '-')
    enabled.firstOrNull { it.id.replace('_', '-').equals(normalised, ignoreCase = true) }?.let { return it.id }
    val language = normalised.substringBefore('-')
    return enabled.firstOrNull { it.language.equals(language, ignoreCase = true) }?.id
}
