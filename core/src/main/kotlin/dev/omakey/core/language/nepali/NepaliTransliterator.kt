package dev.omakey.core.language.nepali

import dev.omakey.core.language.Transliterator

/**
 * Rule-based phonetic (Romanized) Nepali transliteration — the standard "type Latin sounds, get
 * Devanagari" method most Nepali typing tools (Google's Nepali Input among them) use, which this
 * follows the same conventions as: lowercase `t/d/n/s` are dental (त/द/न/स), uppercase `T/D/N/S`
 * are retroflex (ट/ड/ण/ष); a single vowel letter (`a/i/u/e/o`) is the short/inherent form, a
 * doubled or capitalized one (`aa`/`A`, `ee`/`I`, `oo`/`U`) is the long form. Since the layout this
 * drives ([dev.omakey.core.language.Languages.Nepali]'s Romanized input method) is plain
 * [dev.omakey.core.layout.Layouts.QwertyEnUS], the shift key already produces the uppercase
 * letters this scheme reads as retroflex — no separate key layout is needed for it.
 *
 * **Flagged for native-speaker review/tuning before relying on it for real typing** — see
 * [dev.omakey.core.layout.NepaliLayouts]'s doc for the same note on the Traditional layout. Two
 * known, deliberate simplifications worth knowing about up front:
 * - A word-final consonant with nothing typed after it keeps its inherent "a" sound rather than
 *   being silenced (e.g. "kamal" -> कमल, not कमल्) — the common case, but real Nepali orthography
 *   occasionally wants a silent final consonant, which this scheme has no way to request.
 * - A single vowel letter always reads as short/inherent, even where casual Romanized spelling
 *   commonly means the long form — e.g. "gyan" renders with the schwa-only form, not ज्ञान; ज्ञान
 *   needs the double-vowel spelling "gyaan". This is the strict, consistent reading, not a
 *   special-cased guess at which specific words' casual spelling means something else.
 * - Two typed consonants with no vowel letter between them always render as a real orthographic
 *   conjunct (base + ् + base) — correct for a genuine cluster like "matra" -> मात्र, but this
 *   can't distinguish that case from ordinary word-medial schwa deletion, where the *spelling*
 *   keeps each consonant's own inherent vowel even though it isn't pronounced (e.g. "matlab"
 *   should spell as मतलब — four separate letters, no virama — but renders as मत्लब here). Reliably
 *   telling the two apart needs a real Nepali word list to check candidates against, which this
 *   scheme deliberately doesn't have yet (see LanguageDefinition.dictionaryAsset) — until it does,
 *   expect this specific class of word to need a manual nudge (an extra vowel letter breaks the
 *   cluster apart, e.g. typing "matalab" instead).
 *
 * Algorithm: tokenize [transliterate]'s input via greedy longest-match (3 chars, then 2, then 1)
 * against the consonant/vowel tables below, then render left to right — a consonant takes the
 * next token's vowel matra if one follows, a virama (्) if another consonant follows instead, or
 * stays bare (inherent "a") if nothing does. A vowel renders in its standalone form only when
 * nothing precedes it in that pairing (word start, or after another vowel) — matras can't stand
 * alone. Re-run in full against the whole buffer on every keystroke (see [Transliterator]'s own
 * doc for why: a later keystroke can change how an earlier one reads, e.g. "s" alone is स but
 * "sh" together is श), not incrementally.
 */
object NepaliTransliterator : Transliterator {

    private sealed interface Token {
        data class Consonant(val base: String) : Token
        data class Vowel(val standalone: String, val matra: String?) : Token
        /** A trailing nasalization/aspiration mark (anusvara ं / visarga ः) — appended directly
         * after whatever precedes it, never triggers virama insertion the way a real consonant
         * token would. */
        data class Modifier(val mark: String) : Token
        data class Literal(val text: String) : Token
    }

    private const val VIRAMA = "्" // ्

    // Longest-match-first lookup, split by key length so tokenize() can try 3/2/1 in order.
    // Case-sensitive: uppercase marks the retroflex/long-vowel member of a pair, per this file's
    // class doc — never folded to lowercase before matching.
    private val CONSONANTS_3 = mapOf("chh" to "छ", "ksh" to "क्ष")
    private val CONSONANTS_2 = mapOf(
        "kh" to "ख", "gh" to "घ", "ng" to "ङ", "ch" to "च", "jh" to "झ", "ny" to "ञ",
        "th" to "थ", "dh" to "ध", "ph" to "फ", "bh" to "भ", "sh" to "श", "gy" to "ज्ञ",
        "Th" to "ठ", "Dh" to "ढ",
    )
    private val CONSONANTS_1 = mapOf(
        "k" to "क", "g" to "ग", "c" to "च", "j" to "ज", "t" to "त", "d" to "द", "n" to "न",
        "p" to "प", "f" to "फ", "b" to "ब", "m" to "म", "y" to "य", "r" to "र", "l" to "ल",
        "v" to "व", "w" to "व", "s" to "स", "h" to "ह", "x" to "क्ष",
        "T" to "ट", "D" to "ड", "N" to "ण", "S" to "ष",
    )
    private val VOWELS_2 = mapOf(
        "aa" to ("आ" to "ा"), "ee" to ("ई" to "ी"), "oo" to ("ऊ" to "ू"),
        "ai" to ("ऐ" to "ै"), "au" to ("औ" to "ौ"),
    )
    private val VOWELS_1 = mapOf(
        "a" to ("अ" to null), "i" to ("इ" to "ि"), "u" to ("उ" to "ु"),
        "e" to ("ए" to "े"), "o" to ("ओ" to "ो"),
        "A" to ("आ" to "ा"), "I" to ("ई" to "ी"), "U" to ("ऊ" to "ू"), "R" to ("ऋ" to "ृ"),
    )
    private val MODIFIERS_1 = mapOf("M" to "ं", "H" to "ः") // anusvara ं, visarga ः

    override fun transliterate(raw: String): String {
        if (raw.isEmpty()) return ""
        val tokens = tokenize(raw)
        val out = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            when (val token = tokens[i]) {
                is Token.Consonant -> {
                    out.append(token.base)
                    when (val next = tokens.getOrNull(i + 1)) {
                        is Token.Vowel -> {
                            if (next.matra != null) out.append(next.matra)
                            i++ // consume the paired vowel too
                        }
                        is Token.Consonant -> out.append(VIRAMA)
                        // Word-final, or followed by a modifier/literal: stays bare (inherent
                        // "a") — see this file's class doc.
                        else -> {}
                    }
                }
                is Token.Vowel -> out.append(token.standalone)
                is Token.Modifier -> out.append(token.mark)
                is Token.Literal -> out.append(token.text)
            }
            i++
        }
        return out.toString()
    }

    private fun tokenize(raw: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < raw.length) {
            val consumed = matchAt(raw, i, tokens)
            i += consumed
        }
        return tokens
    }

    /** Tries the longest key first at position [i], appends the matching token, and returns how
     * many characters it consumed — 1 (as a passthrough [Token.Literal]) if nothing matched, so
     * an unrecognized character (a digit, an already-Devanagari character pasted in, punctuation)
     * is never silently dropped. */
    private fun matchAt(raw: String, i: Int, tokens: MutableList<Token>): Int {
        substringOrNull(raw, i, 3)?.let { CONSONANTS_3[it]?.let { d -> tokens += Token.Consonant(d); return 3 } }
        substringOrNull(raw, i, 2)?.let { key ->
            CONSONANTS_2[key]?.let { d -> tokens += Token.Consonant(d); return 2 }
            VOWELS_2[key]?.let { (standalone, matra) -> tokens += Token.Vowel(standalone, matra); return 2 }
        }
        val one = raw.substring(i, i + 1)
        CONSONANTS_1[one]?.let { d -> tokens += Token.Consonant(d); return 1 }
        VOWELS_1[one]?.let { (standalone, matra) -> tokens += Token.Vowel(standalone, matra); return 1 }
        MODIFIERS_1[one]?.let { m -> tokens += Token.Modifier(m); return 1 }
        tokens += Token.Literal(one)
        return 1
    }

    private fun substringOrNull(s: String, start: Int, length: Int): String? =
        if (start + length <= s.length) s.substring(start, start + length) else null
}
