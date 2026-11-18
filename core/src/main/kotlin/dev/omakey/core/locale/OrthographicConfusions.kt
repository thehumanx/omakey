package dev.omakey.core.locale

/**
 * Built-in spelling-confusion tables per language, for packs that don't declare their own.
 *
 * In code rather than only in each pack's `profile.json` so a language pack already on a phone
 * gets them with an app update, without a pack rebuild. A pack that declares either table keeps
 * its own.
 *
 * Russian, measured on synthetic orthographic errors (`RussianOrthography`, held-out Tatoeba):
 * й typed as и and unstressed о as а / е as и are far-apart keys on ЙЦУКЕН, so they were priced as
 * unlikely slips and a nearby wrong word won ("каждыи" → "каждым"); a left-out ь cost a full
 * missed letter. ь/ъ swapped already counts as equivalent (the pack's `equivalentLetters`).
 */
object OrthographicConfusions {

    private val confusable = mapOf(
        "ru" to listOf("йи", "оа", "еи"),
    )

    private val omittable = mapOf(
        "ru" to setOf('ь', 'ъ'),
    )

    /** [profile] with the built-in tables for [localeId]'s language, unless it declares its own. */
    fun applyDefaults(profile: LanguageProfile, localeId: String): LanguageProfile {
        val language = localeId.substringBefore('_')
        val groups = profile.confusableLetters.ifEmpty { confusable[language].orEmpty() }
        val letters = profile.omittableLetters.ifEmpty { omittable[language].orEmpty() }
        if (groups == profile.confusableLetters && letters == profile.omittableLetters) return profile
        return profile.withOrthography(groups, letters)
    }
}
