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

    /** An optional nasal in [romanize]'s output; never typed, never in a key. */
    const val NASAL = '~'
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
                // A nasal sign is typed as "n", "m" or not at all (कहाँ "kaha", तपाईं "tapai"), so it
                // is marked rather than spelled: [skeleton] and [light] drop it, and [distance]
                // makes it cheap to leave out.
                c == 'ं' || c == 'ँ' -> out.append(NASAL)
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
        var s = latin.lowercase().replace(NASAL.toString(), "")
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

    /**
     * How far [typed] is from a word romanized as [romanized] (see [TransliterationScheme.distance]).
     *
     * [light] folds aspiration and vowel length away entirely, which is right for *finding*
     * candidates — people drop them all the time — and wrong for *ranking* them: it made "bhat"
     * exactly as close to बाट as to भात, so the more frequent बाट won, and "bholi" came out as बोली.
     * What a speaker types is evidence, and it is asymmetric:
     *
     *  - typing an "h" (bh, kh, chh) or a long vowel (aa, ii, uu) the word lacks costs most — they
     *    went out of their way to type it;
     *  - leaving out one the word has costs a little — the commonest shortcut there is;
     *  - leaving out a nasal sign (कहाँ "kaha") costs almost nothing, as does a final "a" the
     *    word does not have (आज "aja", किन "kina"), which is how many people end every consonant.
     *
     * Everything else is one edit, as before.
     */
    fun distance(typed: String, romanized: String, prefix: Boolean): Float {
        val a = medium(typed)
        val full = medium(romanized)
        if (prefix) return weightedEditDistance(a, full.take(a.length + 1))
        var best = weightedEditDistance(a, full)
        if (full.isNotEmpty() && full.last() !in MEDIUM_VOWELS && a.lastOrNull() == 'a') {
            best = minOf(best, weightedEditDistance(a, full + "a") + T_FINAL_A)
        }
        return best
    }

    /** One character per sound, aspiration and length kept: uppercase marks the aspirated
     * consonant or long vowel ("bhaat" → "BAt"), [NASAL] stays, doubled consonants merge. */
    internal fun medium(latin: String): String {
        var s = latin.lowercase()
        for ((from, to) in MEDIUM_REPLACEMENTS) s = s.replace(from, to)
        val out = StringBuilder(s.length)
        for (c in s) {
            if (c == out.lastOrNull() && c != 'a') {
                // A doubled consonant ("tt" in भट्ट) is one sound held longer: marked, not spelled
                // twice, so leaving it out is cheap but not free.
                if (c !in MEDIUM_VOWELS && c != NASAL) out.append(GEMINATE)
                continue
            }
            if (c == out.lastOrNull()?.let { if (it == GEMINATE) out.getOrNull(out.length - 2) else null }) continue
            out.append(c)
        }
        return out.toString()
    }

    private val MEDIUM_REPLACEMENTS = listOf(
        "chh" to "C", "ch" to "c", "x" to "C",
        "kh" to "K", "gh" to "G", "jh" to "J", "th" to "T", "dh" to "D", "ph" to "f", "bh" to "B", "sh" to "S",
        "aa" to "A", "ee" to "I", "ii" to "I", "oo" to "U", "uu" to "U",
        "z" to "j", "q" to "k",
    )
    private const val MEDIUM_VOWELS = "aAiIuUeo"

    /** The unmarked counterpart of an aspirated consonant or long vowel, else itself. */
    private fun base(c: Char): Char = when (c) {
        'C' -> 'c'; 'K' -> 'k'; 'G' -> 'g'; 'J' -> 'j'; 'T' -> 't'; 'D' -> 'd'; 'B' -> 'b'; 'S' -> 's'
        'A' -> 'a'; 'I' -> 'i'; 'U' -> 'u'
        else -> c
    }

    private fun substitution(typed: Char, word: Char): Float {
        if (typed == word) return 0f
        if (base(typed) == base(word)) {
            val typedMarked = typed.isUpperCase()
            return when {
                // श/ष and स are not told apart in speech, so "s" and "sh" are near-free either way.
                base(typed) == 's' -> if (typedMarked) 0.3f else 0.1f
                base(typed) in "aiu" -> if (typedMarked) T_VOWEL_TYPED else T_VOWEL_UNTYPED
                typedMarked -> TYPED_BUT_ABSENT
                else -> PRESENT_BUT_UNTYPED
            }
        }
        if (word == NASAL && (typed == 'n' || typed == 'm')) return 0.1f
        // "v" is typed for व and, very often, for भ ("vayo" भयो).
        if (typed == 'v') return when (word) { 'w' -> 0.1f; 'B' -> 0.2f; 'b' -> 0.4f; else -> 1f }
        if ((typed == 'w' && word == 'b') || (typed == 'b' && word == 'w')) return 0.6f
        return 1f
    }

    private fun deletion(word: Char): Float = when (word) {
        NASAL -> 0.15f
        GEMINATE -> T_GEM
        else -> 1f
    }

    private fun insertion(typed: Char): Float = if (typed == GEMINATE) T_GEM else 1f

    /** Marks the consonant before it as doubled, in [medium] form. */
    private const val GEMINATE = ':'


    private fun weightedEditDistance(typed: String, word: String): Float {
        val previous = FloatArray(word.length + 1)
        for (j in 1..word.length) previous[j] = previous[j - 1] + deletion(word[j - 1])
        val current = FloatArray(word.length + 1)
        for (i in 1..typed.length) {
            current[0] = previous[0] + insertion(typed[i - 1])
            for (j in 1..word.length) {
                current[j] = minOf(
                    previous[j] + insertion(typed[i - 1]),
                    current[j - 1] + deletion(word[j - 1]),
                    previous[j - 1] + substitution(typed[i - 1], word[j - 1]),
                )
            }
            current.copyInto(previous)
        }
        return previous[word.length]
    }

    /*
     * Costs, swept on the everyday-word probe (`NepaliEverydayWordsTest`) and Aksharantar's test
     * split together (2026-11-12; see that test for the numbers). A full edit is 1.
     */

    /** Typed an "h" (bh, kh, chh) the word does not have: as costly as a typo, since it was typed
     * on purpose. */
    private const val TYPED_BUT_ABSENT = 1.0f

    /** Left out an "h" the word has: the commonest shortcut in romanized Nepali. */
    private const val PRESENT_BUT_UNTYPED = 0.3f

    /** Typed a long vowel (aa, ii, uu) the word does not have. */
    private const val T_VOWEL_TYPED = 0.5f

    /** Typed a short vowel for a long one ("khana" for खाना): nearly everyone does. */
    private const val T_VOWEL_UNTYPED = 0.1f

    /** A final "a" the word does not end in ("aja" for आज). */
    private const val T_FINAL_A = 0f

    /** A doubled consonant typed single, or the reverse ("bhat" vs भट्ट). */
    private const val T_GEM = 0.3f
}
