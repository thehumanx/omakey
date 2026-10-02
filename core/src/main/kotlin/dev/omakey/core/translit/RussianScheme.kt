package dev.omakey.core.translit

/**
 * Russian typed in Latin letters ("privet" → привет) — the "translit" people use on a keyboard
 * without Cyrillic (AGENTS.md §70).
 *
 * Like Nepali, there is no single scheme people follow: х is "h", "kh" or "x"; я is "ya", "ja" or
 * "ia"; щ is "sch", "shch" or "shh"; ы, й and и are all often just "i" or "y"; ё is "yo", "jo" or
 * plain "e"; the soft and hard signs are usually not typed at all. So matching folds those
 * variations together in [light] and [skeleton], and the language model picks among what's left.
 *
 * Unlike Nepali there are no inflections to assemble: Russian endings are part of the word, and the
 * Russian model's vocabulary is sized to hold the common inflected forms (`build_lang_lm.py`).
 */
object RussianScheme : TransliterationScheme {

    private val romanization: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "",
        'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    /** Characters outside the alphabet (a hyphen, a stray Latin letter) carry no sound and are
     * dropped; the result is lowercase a–z. */
    override fun romanize(word: String): String {
        val out = StringBuilder(word.length + 4)
        for (c in word.lowercase()) out.append(romanization[c] ?: "")
        return out.toString()
    }

    /**
     * Multi-letter sounds become one placeholder each (uppercase or a digit, so they can't collide
     * with a letter typed for itself), then the single-letter variants are merged. Order matters:
     * "shch" before "sh" before "ch", and every digraph before its letters are merged on their own
     * ("kh" before "h" → х).
     */
    private val FOLDS = listOf(
        "shch" to "Q", "sch" to "Q", "shh" to "Q", "sh" to "W", "zh" to "J", "kh" to "X", "ch" to "4",
        "ts" to "C", "tz" to "C",
        "yo" to "e", "jo" to "e", "ye" to "e", "je" to "e",
        "yu" to "U", "ju" to "U", "ya" to "A", "ja" to "A",
        "h" to "X", "x" to "X", "c" to "C", "w" to "v", "q" to "k", "j" to "i", "y" to "i",
    )

    override fun light(latin: String): String {
        var s = latin.lowercase().filter { it in 'a'..'z' }
        for ((from, to) in FOLDS) s = s.replace(from, to)
        return collapseDoubles(s)
    }

    /** [light] with я/ю also merged into а/у — "maria" for мария (romanized "mariya") is
     * common — for retrieval only; the distance on [light] still prefers the closer spelling. */
    override fun skeleton(latin: String): String = collapseDoubles(light(latin).replace('A', 'a').replace('U', 'u'))

    private fun collapseDoubles(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) if (c != out.lastOrNull()) out.append(c)
        return out.toString()
    }

    private val LITERAL = listOf(
        "shch" to "щ", "sch" to "щ", "zh" to "ж", "kh" to "х", "ts" to "ц", "ch" to "ч", "sh" to "ш",
        "yo" to "ё", "jo" to "ё", "ye" to "е", "je" to "е", "yu" to "ю", "ju" to "ю", "ya" to "я", "ja" to "я",
        "a" to "а", "b" to "б", "v" to "в", "g" to "г", "d" to "д", "e" to "е", "z" to "з", "i" to "и",
        "j" to "й", "k" to "к", "l" to "л", "m" to "м", "n" to "н", "o" to "о", "p" to "п", "r" to "р",
        "s" to "с", "t" to "т", "u" to "у", "f" to "ф", "h" to "х", "x" to "х", "c" to "ц", "w" to "в",
        "q" to "к",
    )
    private const val VOWELS = "aeiouy"

    /** Greedy longest match. A lone "y" is ы after a consonant ("my" → мы, "byl" → был) and й
     * otherwise ("moy" → мой, "yod" → йод) — the one letter whose reading depends on its neighbour. */
    override fun literal(latin: String): String {
        val input = latin.lowercase()
        val out = StringBuilder(input.length)
        var i = 0
        outer@ while (i < input.length) {
            if (input[i] == 'y' && LITERAL.none { (roman, _) -> roman.length > 1 && input.startsWith(roman, i) }) {
                val previous = input.getOrNull(i - 1)
                out.append(if (previous != null && previous !in VOWELS && previous in 'a'..'z') 'ы' else 'й')
                i++
                continue
            }
            for ((roman, cyrillic) in LITERAL) {
                if (input.startsWith(roman, i)) {
                    out.append(cyrillic)
                    i += roman.length
                    continue@outer
                }
            }
            out.append(input[i])
            i++
        }
        return out.toString()
    }
}
