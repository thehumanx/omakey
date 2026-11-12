package dev.omakey.core.search

import java.text.Normalizer
import java.util.Locale

/** One searchable thing: a setting's [title], hand-picked [keywords] (synonyms the title doesn't
 * contain), and [context] (where it lives: section and page titles). Descriptions are left out on
 * purpose: short queries like "key" would match almost every row. */
data class SearchDocument<T>(
    val id: T,
    val title: String,
    val keywords: List<String>,
    val context: List<String>,
)

/**
 * A match. [tier] orders results (lower first): 0 the query starts the title, 1 every word matches
 * a title word, 2 needs a keyword, 3 needs the section or page name. [titleHighlights] are ranges
 * in the original title to show bold; [matchedKeyword] is set when a keyword contributed.
 */
data class SearchHit<T>(
    val id: T,
    val tier: Int,
    val titleHighlights: List<IntRange>,
    val matchedKeyword: String?,
)

/** Text folding shared by documents and queries, so both sides compare the same way. */
object SearchText {
    // Marks are part of a word: Devanagari vowel signs and viramas aren't decoration, and
    // splitting on them would cut नमस्ते into pieces.
    private val wordRun = Regex("[\\p{L}\\p{N}\\p{M}]+")

    /** Case- and accent-insensitive form: "Café" → "cafe". Only marks on Latin letters are
     * dropped; marks in other scripts carry meaning and stay. */
    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var baseIsLatin = false
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            val isMark = Character.getType(cp).let {
                it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() ||
                    it == Character.ENCLOSING_MARK.toInt()
            }
            if (!isMark) baseIsLatin = Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN
            if (!(isMark && baseIsLatin)) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return Normalizer.normalize(out, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    /** Every word a query token may start: the letter/digit runs, plus each whitespace chunk with
     * its punctuation squeezed out, so "autocap" finds "Auto-capitalize". */
    fun words(s: String): List<String> {
        val out = LinkedHashSet<String>()
        normalize(s).split(Regex("\\s+")).forEach { chunk ->
            val parts = wordRun.findAll(chunk).map { it.value }.toList()
            out += parts
            if (parts.size > 1) out += parts.joinToString("")
        }
        return out.toList()
    }

    /** Query tokens: one per whitespace chunk, punctuation squeezed out, empties dropped. */
    internal fun tokens(query: String): List<String> =
        normalize(query).split(Regex("\\s+"))
            .map { chunk -> wordRun.findAll(chunk).joinToString("") { it.value } }
            .filter { it.isNotEmpty() }

    /** The title as a comparable phrase: chunks squeezed, single spaces between. */
    internal fun phrase(s: String): String = tokens(s).joinToString(" ")

    internal fun wordRanges(s: String): Sequence<MatchResult> = wordRun.findAll(s)
}

/** Word-prefix search over a fixed list of documents. Every query word must start some word of
 * the title, keywords, section or page; no infix or fuzzy matching. */
class SettingsSearch<T>(documents: List<SearchDocument<T>>) {

    private class Indexed<T>(
        val doc: SearchDocument<T>,
        val phrase: String,
        val titleWords: List<String>,
        val keywordWords: List<Pair<String, List<String>>>,
        val contextWords: List<String>,
    )

    private val indexed = documents.map { d ->
        Indexed(
            doc = d,
            phrase = SearchText.phrase(d.title),
            titleWords = SearchText.words(d.title),
            keywordWords = d.keywords.map { it to SearchText.words(it) },
            contextWords = d.context.flatMap(SearchText::words),
        )
    }

    fun search(query: String): List<SearchHit<T>> {
        val tokens = SearchText.tokens(query)
        if (tokens.isEmpty()) return emptyList()
        val whole = tokens.joinToString(" ")
        return indexed.mapNotNull { hit(it, tokens, whole) }
            .withIndex()
            .sortedWith(compareBy({ it.value.tier }, { it.index }))
            .map { it.value }
    }

    private fun hit(d: Indexed<T>, tokens: List<String>, whole: String): SearchHit<T>? {
        fun List<String>.has(t: String) = any { it.startsWith(t) }
        val keywordAll = d.keywordWords.flatMap { it.second }
        val tier = when {
            tokens.any { t -> !d.titleWords.has(t) && !keywordAll.has(t) && !d.contextWords.has(t) } -> return null
            d.phrase.startsWith(whole) -> 0
            tokens.all { d.titleWords.has(it) } -> 1
            tokens.all { d.titleWords.has(it) || keywordAll.has(it) } -> 2
            else -> 3
        }
        val keyword = if (tier < 2) null else d.keywordWords
            .firstOrNull { (_, words) -> tokens.any { t -> !d.titleWords.has(t) && words.has(t) } }
            ?.first
        return SearchHit(d.doc.id, tier, highlights(d.doc.title, tokens), keyword)
    }

    /** Bold ranges in the original title: each word a token starts, and for a token that only
     * matched a hyphenated chunk joined up ("autocap" → "Auto-capitalize"), the letters it covers
     * across that chunk's parts. */
    private fun highlights(title: String, tokens: List<String>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        Regex("\\S+").findAll(title).forEach { chunk ->
            val parts = SearchText.wordRanges(chunk.value)
                .map { (chunk.range.first + it.range.first) to it.value }
                .toList()
            var matched = false
            parts.forEach { (start, word) ->
                val normalized = SearchText.normalize(word)
                val token = tokens.filter { normalized.startsWith(it) }.maxByOrNull { it.length }
                if (token != null) {
                    out += start until start + minOf(token.length, word.length)
                    matched = true
                }
            }
            if (!matched && parts.size > 1) {
                val joined = parts.joinToString("") { SearchText.normalize(it.second) }
                val token = tokens.filter { joined.startsWith(it) }.maxByOrNull { it.length } ?: return@forEach
                var left = token.length
                for ((start, word) in parts) {
                    if (left <= 0) break
                    out += start until start + minOf(left, word.length)
                    left -= SearchText.normalize(word).length
                }
            }
        }
        return out
    }
}
