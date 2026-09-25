package dev.omakey.core.translit

/**
 * Nepali romanization, both directions, plus the lossy keys that make matching tolerant of how
 * differently people spell the same word in Latin letters (AGENTS.md §66 Phase 9).
 *
 * There is no standard Nepali romanization people actually follow when typing: "timi" / "timee",
 * "chha" / "cha" / "xa", "garnu" / "garnoo", "bhayo" / "vayo" are all the same words. Rather than
 * guess one scheme, candidates are found by a [skeleton] that merges those variations, then ranked by
 * the language model and by how close the typing is to each candidate's own [romanize] form.
 */
object Romanization {

    private const val VIRAMA = '्'
    private const val NUKTA = '़'

    private val consonants: Map<Char, String> = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "ny",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "w",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
    )

    private val independentVowels: Map<Char, String> = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ii", 'उ' to "u", 'ऊ' to "uu",
        'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
    )

    private val vowelSigns: Map<Char, String> = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ii", 'ु' to "u", 'ू' to "uu",
        'ृ' to "ri", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au",
    )

    /**
     * A Devanagari word in Latin letters, the way a Nepali speaker would plausibly type it:
     * consonants carry an inherent "a" unless a vowel sign or virama follows, and a word-final
     * consonant drops it (नेपाल → nepal) unless the word would otherwise have no vowel (र → ra).
     */
    fun romanize(word: String): String {
        val out = StringBuilder(word.length * 2)
        var i = 0
        while (i < word.length) {
            val c = word[i]
            // ज्ञ is said "gya" in Nepali, not the "jny" its parts spell.
            if (c == 'ज' && i + 2 < word.length && word[i + 1] == VIRAMA && word[i + 2] == 'ञ') {
                out.append("gy")
                i += 3
                if (i >= word.length || word[i] !in vowelSigns && word[i] != VIRAMA) out.append('a')
                continue
            }
            val consonant = consonants[c]
            when {
                consonant != null -> {
                    out.append(consonant)
                    var next = i + 1
                    if (next < word.length && word[next] == NUKTA) next++
                    val following = word.getOrNull(next)
                    if (following != null && following != VIRAMA && following !in vowelSigns) out.append('a')
                    i = next
                    continue
                }
                c in vowelSigns -> out.append(vowelSigns.getValue(c))
                c in independentVowels -> out.append(independentVowels.getValue(c))
                c == 'ं' || c == 'ँ' -> out.append('n')
                c == 'ः' -> out.append('h')
                else -> Unit // virama, nukta, ZWJ/ZWNJ and anything else carry no sound of their own
            }
            i++
        }
        if (out.none { it in "aeiou" }) out.append('a')
        return out.toString()
    }

    /**
     * Latin spelling with the variations people make *without* changing the word folded together,
     * but vowels kept: the form edit distance is measured on. "chha", "cha" and "xa" all become
     * "ca"; "bhayo" and "vayo" both "bayo"; "timee" and "timi" both "timi".
     */
    fun light(latin: String): String = collapse(fold(latin), dropAspiration = true)

    /**
     * The retrieval key: [light], but with every non-initial "a" removed *before* aspiration is
     * dropped. Whether a schwa gets typed is the least consistent thing of all ("ramro" for राम्रो
     * has none where "garnu" for गर्नु has one), so candidates are looked up without it and the
     * ranking decides. The order matters: किताबहरू romanizes to "kitaabaharuu", where the schwa
     * before "h" keeps it from reading as aspiration — so it has to go first, or "kitabharu" (b,h)
     * and the word itself (ba, ha) could never meet.
     */
    fun skeleton(latin: String): String {
        val folded = collapse(fold(latin), dropAspiration = false)
        if (folded.isEmpty()) return folded
        val withoutSchwa = StringBuilder(folded.length).append(folded[0])
        for (i in 1 until folded.length) if (folded[i] != 'a') withoutSchwa.append(folded[i])
        return collapse(withoutSchwa.toString(), dropAspiration = true)
    }

    /** Lowercase, with the letter-for-letter variations folded ("chh"/"ch"/"x" → "c", "w"/"v" → "b"). */
    private fun fold(latin: String): String {
        var s = latin.lowercase()
        for ((from, to) in LIGHT_REPLACEMENTS) s = s.replace(from, to)
        return s
    }

    /** Doubled letters merged — vowel length ("aa") and gemination ("tt") are both inconsistently
     * typed — and, with [dropAspiration], an "h" after an aspirable consonant ("kh"/"k", "bh"/"b"),
     * the most-dropped letter in romanized Nepali. */
    private fun collapse(s: String, dropAspiration: Boolean): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            val previous = out.lastOrNull()
            if (dropAspiration && c == 'h' && previous != null && previous in ASPIRABLE) continue
            if (c == previous) continue
            out.append(c)
        }
        return out.toString()
    }

    private val ASPIRABLE = setOf('k', 'g', 'c', 'j', 't', 'd', 'p', 'b', 's')

    private val LIGHT_REPLACEMENTS = listOf(
        "chh" to "c", "ch" to "c", "x" to "c", "sh" to "s", "ph" to "f",
        "w" to "b", "v" to "b", "z" to "j", "q" to "k", "ee" to "i", "oo" to "u",
    )
}
