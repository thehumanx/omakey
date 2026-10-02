package dev.omakey.core.translit

/**
 * One language's rules for typing it in Latin letters (AGENTS.md §66 Phase 9, §70).
 *
 * [Transliterator] and [TransliterationIndex] are language-neutral: they find vocabulary words whose
 * romanization looks like the typing and rank them with the language model. Everything that knows
 * how a particular script is written in Latin lives behind this interface instead.
 */
interface TransliterationScheme {

    /** A vocabulary word in Latin letters, the way a speaker would plausibly type it. */
    fun romanize(word: String): String

    /** Latin with the spelling variations that don't change the word folded together, vowels kept:
     * what edit distance is measured on. Applied to the typing and to [romanize]'s output alike. */
    fun light(latin: String): String

    /** The lossier retrieval key the index is sorted by. ASCII only — the index stores it as bytes. */
    fun skeleton(latin: String): String

    /** Rule-based transliteration of [latin] (lowercase a–z): the candidate that is always
     * offered, so names and words the model has never seen can still be typed. */
    fun literal(latin: String): String

    /** Ways [typed] could be a known stem plus regular suffixes the vocabulary is too small to
     * list, longest suffix first. Empty for languages that don't need it. */
    fun inflections(typed: String): List<Inflection> = emptyList()

    /** [stemLatin] written as a known word, followed by [suffix] (already in the target script);
     * [parts] suffixes, each costing a penalty. */
    class Inflection(val stemLatin: String, val suffix: String, val parts: Int)

    companion object {
        /** The scheme for an ISO 639 [language] code, or null if it has none. */
        fun forLanguage(language: String): TransliterationScheme? = when (language) {
            "ne" -> NepaliScheme
            "ru" -> RussianScheme
            else -> null
        }
    }
}

/** Nepali: [Romanization], [LiteralTransliterator], and joined postpositions as inflections. */
object NepaliScheme : TransliterationScheme {
    override fun romanize(word: String) = Romanization.romanize(word)
    override fun light(latin: String) = Romanization.light(latin)
    override fun skeleton(latin: String) = Romanization.skeleton(latin)
    override fun literal(latin: String) = LiteralTransliterator.transliterate(latin)

    /** The plural marker, written joined to its noun. */
    private val PLURAL = "haru" to "हरू"

    /** Postpositions written joined to the word before, longest first so "bata" isn't read as
     * "ta". Latin forms as people type them; Devanagari as the standard spelling. */
    private val CASE_SUFFIXES = listOf(
        "dekhi" to "देखि", "sanga" to "सँग", "samma" to "सम्म", "bata" to "बाट", "baata" to "बाट",
        "laai" to "लाई", "lai" to "लाई", "ko" to "को", "ka" to "का", "ki" to "की", "le" to "ले", "ma" to "मा",
    )
    private const val MIN_STEM = 2

    /** Nepali builds inflected forms from a stem and regular postpositions (किताब + हरू + लाई), and
     * most are too rare to be in the vocabulary — 44 % of Aksharantar's test words are not. Every
     * way [typed] ends in a postposition, optionally after the plural हरू. */
    override fun inflections(typed: String): List<TransliterationScheme.Inflection> {
        val splits = ArrayList<TransliterationScheme.Inflection>()
        for ((caseLatin, caseDevanagari) in CASE_SUFFIXES + ("" to "")) {
            if (!typed.endsWith(caseLatin)) continue
            val beforeCase = typed.dropLast(caseLatin.length)
            for (plural in listOf(true, false)) {
                if (plural && !beforeCase.endsWith(PLURAL.first)) continue
                if (!plural && caseLatin.isEmpty()) continue
                val stem = if (plural) beforeCase.dropLast(PLURAL.first.length) else beforeCase
                if (stem.length < MIN_STEM) continue
                val devanagari = (if (plural) PLURAL.second else "") + caseDevanagari
                splits += TransliterationScheme.Inflection(
                    stem, devanagari, (if (plural) 1 else 0) + (if (caseLatin.isEmpty()) 0 else 1),
                )
            }
        }
        return splits
    }
}
