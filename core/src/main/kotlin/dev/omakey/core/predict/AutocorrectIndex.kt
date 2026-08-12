package dev.omakey.core.predict

import dev.omakey.core.db.WordEntity
import kotlin.math.abs

/**
 * In-memory typo correction, checked on every word-boundary keystroke (space/punctuation/enter)
 * — far too hot a path to round-trip SQLite per word, so the whole dictionary is loaded into a
 * plain map once at startup instead.
 *
 * Correction is real Damerau-Levenshtein edit distance (insert/delete/substitute/adjacent-
 * transpose), not just a fixed one-edit candidate set — scanning only dictionary words that share
 * the typed word's first letter (see [candidatesNear]) keeps this affordable even at distance 2,
 * without the combinatorial blowup of generating and hashing every possible 1- or 2-edit string
 * (a 60k-word dictionary split across 26 first letters is ~2,300 candidates on average, each an
 * O(length²) distance computation — microseconds, not milliseconds).
 */
class AutocorrectIndex {
    @Volatile private var frequencyByWord: Map<String, Int> = emptyMap()
    @Volatile private var wordsByFirstLetter: Map<Char, List<Pair<String, Int>>> = emptyMap()
    @Volatile private var correctionFloor: Int = 0
    @Volatile private var strictFloor: Int = 0

    /** Which "known" words came from the user's own swipe-up saves, as opposed to the bundled
     * seed dictionary — the only ones [unlearn] is ever allowed to touch. Without this distinction
     * "unlearn" could remove a real, common word (e.g. "cat") from the in-memory index just
     * because the user swiped up on it a second time, silently turning autocorrect against an
     * ordinary dictionary word for the rest of the session. */
    @Volatile private var userAddedWords: Set<String> = emptySet()

    fun load(words: List<WordEntity>) {
        val map = words.associate { it.word to it.frequency }
        frequencyByWord = map
        userAddedWords = words.filter { it.isUserAdded }.mapTo(mutableSetOf()) { it.word }
        wordsByFirstLetter = map.entries
            .filter { it.key.isNotEmpty() }
            .groupBy({ it.key.first() }) { it.key to it.value }
        // Only correct into reasonably common words — otherwise a typed non-word that happens to
        // be close to an obscure dictionary entry gets "corrected" into something the user has
        // never heard of, which is worse than not correcting at all. Two tiers: a looser floor
        // (top ~20%) for close/high-confidence matches (distance 1, word splits), a stricter one
        // (top ~10%) for the inherently less certain distance-2 fallback.
        val sorted = map.values.sortedDescending()
        correctionFloor = sorted.getOrElse((sorted.size * 0.2).toInt()) { 0 }
        strictFloor = sorted.getOrElse((sorted.size * 0.1).toInt()) { 0 }
    }

    /** Marks a word as known (e.g. explicitly saved via swipe-up) so it's never "corrected" away
     * in the future, even if it's a name/slang/word absent from the seeded dictionary. The exact
     * frequency value doesn't matter here — only presence in the map is checked. */
    fun learn(word: String) {
        val lower = word.lowercase()
        if (lower.isEmpty() || lower in frequencyByWord) return
        frequencyByWord = frequencyByWord + (lower to 1)
        userAddedWords = userAddedWords + lower
        val firstLetter = lower.first()
        wordsByFirstLetter = wordsByFirstLetter +
            (firstLetter to ((wordsByFirstLetter[firstLetter] ?: emptyList()) + (lower to 1)))
    }

    /** Reverses [learn] — removes [word] from the index so it goes back to being correctable,
     * e.g. swiping up a second time on an already-learned word. Only ever touches words
     * previously learned via [learn] (see [userAddedWords]'s doc) — a no-op for bundled
     * dictionary words, which can never be unlearned this way. */
    fun unlearn(word: String) {
        val lower = word.lowercase()
        if (lower !in userAddedWords) return
        frequencyByWord = frequencyByWord - lower
        userAddedWords = userAddedWords - lower
        val firstLetter = lower.firstOrNull() ?: return
        wordsByFirstLetter = wordsByFirstLetter +
            (firstLetter to (wordsByFirstLetter[firstLetter].orEmpty().filterNot { it.first == lower }))
    }

    /** Whether [word] is already a real/known word — used to skip re-saving something to the
     * dictionary that's already in it (see the swipe-up "save word" gesture). */
    fun isKnown(word: String): Boolean = word.lowercase() in frequencyByWord

    /** Whether [word] was learned via the user's own swipe-up save (as opposed to being part of
     * the bundled seed dictionary) — this is exactly what determines whether a second swipe-up
     * can [unlearn] it. */
    fun isUserAdded(word: String): Boolean = word.lowercase() in userAddedWords

    /** Curated apostrophe-insertion fixes ("im" -> "I'm", "weve" -> "we've") — a closed, well-
     * known set deliberately handled separately from [correct]'s general distance search, for two
     * reasons: (1) the seeded dictionary's source corpus had punctuation stripped, so contraction
     * forms like "dont"/"cant"/"im" already exist in it as ordinary "known" words in their own
     * right — [correct] would refuse to touch them at all (the "already known" guardrail exists
     * for good reason, just not here); (2) several of these are genuinely ambiguous with an
     * unrelated common word ("well" the adverb vs. "we'll", "its" the possessive vs. "it's", "id"
     * as in ID card vs. "I'd") — offering the expansion as a suggestion the user chooses to accept
     * is appropriate, silently auto-applying it often would not be.
     *
     * Tries an exact match first (the common case), then a fuzzy one-edit match against the
     * curated key set for contraction keys of at least [MIN_FUZZY_CONTRACTION_LENGTH] characters
     * — e.g. "shoudve" (a typo *of* "shouldve", missing the 'l') still resolves to "should've".
     * Short keys ("im", "id", "ive"...) are deliberately exact-only: fuzzy-matching a 2-3 letter
     * string against anything is far too prone to colliding with unrelated short words. Returns
     * null for anything not close to an entry in the curated set — never guesses beyond it. */
    fun contractionFor(typed: String): String? {
        val lower = typed.lowercase()
        CONTRACTIONS[lower]?.let { return it }
        var best: String? = null
        var bestDistance = 2
        for ((key, expansion) in CONTRACTIONS) {
            if (key.length < MIN_FUZZY_CONTRACTION_LENGTH) continue
            val distance = damerauLevenshtein(lower, key, 1) ?: continue
            if (distance < bestDistance) {
                bestDistance = distance
                best = expansion
            }
        }
        return best
    }

    /** Every plausible alternative for [word] worth offering on the suggestion strip for the user
     * to cycle through via swipe up/down — deliberately broader than [correct]: applies whether or
     * not [word] is itself a valid dictionary word, because "valid dictionary word" and "what the
     * user actually meant" are different questions. Typing "well" perfectly correctly doesn't mean
     * "we'll" wasn't the intent; only the user can tell, so both get offered rather than the
     * keyboard silently deciding for them (that's exactly why this is a browsable list, not an
     * auto-apply). Ordered by confidence: curated contraction expansion, then — only if [word]
     * isn't itself real — a split or distance-based typo fix, then close real-word neighbors
     * (distance 1 before distance 2, frequency breaking ties) regardless of [word]'s own validity.
     * Deduplicated case-insensitively, capped at [limit]. */
    fun alternatives(word: String, limit: Int): List<String> {
        val lower = word.lowercase()
        if (limit <= 0 || lower.isEmpty()) return emptyList()
        if (!lower.all { it.isLetter() }) return emptyList()

        val results = LinkedHashSet<String>()
        // Checked before the general MIN_LENGTH gate below — several contraction keys ("im",
        // "id") are shorter than it, and would otherwise never even reach contractionFor() at
        // all. Real bug, confirmed: "im" (2 letters) was rejected outright before contraction
        // lookup ever ran.
        contractionFor(lower)?.let { results += it }

        if (lower.length in MIN_LENGTH..MAX_LENGTH) {
            if (lower !in frequencyByWord) {
                if (results.size < limit) correctSplit(lower)?.let { results += it }
                if (results.size < limit) bestByDistance(lower, maxDistance = 1, minFrequency = correctionFloor)?.let { results += it }
                if (results.size < limit) bestByDistance(lower, maxDistance = 2, minFrequency = strictFloor)?.let { results += it }
            }
        }

        if (results.size < limit && lower.length in MIN_LENGTH..MAX_LENGTH) {
            candidatesNear(lower).asSequence()
                // Same "don't suggest something obscure" bar as the rest of this class — without
                // it, an over-broad neighbor search surfaces genuinely rare words (e.g. "helot")
                // as if they were reasonable everyday alternatives.
                .filter { (candidate, freq) -> freq >= correctionFloor && candidate != lower && results.none { it.equals(candidate, ignoreCase = true) } }
                .mapNotNull { (candidate, freq) -> damerauLevenshtein(lower, candidate, 2)?.let { d -> Triple(candidate, d, freq) } }
                .sortedWith(compareBy({ it.second }, { -it.third }))
                .forEach { (candidate, _, _) -> if (results.size < limit) results += candidate }
        }

        return results.take(limit).toList()
    }

    /** Returns a corrected (lowercase) word if [typed] is confidently a typo of a much more
     * common dictionary word, or null if it should be left alone — already a known word, too
     * short, contains non-letters, or no sufficiently common/close neighbor exists.
     *
     * Can also return two words separated by a single space (e.g. `"this is"`) when [typed] looks
     * like two real words typed without the space between them — see [correctSplit], checked
     * *before* single-word distance correction: a concatenation of two very common short words
     * (e.g. "thisis") can coincidentally also be one edit away from some unrelated real word
     * ("thesis") — when both split halves are this common, the split is almost always what was
     * actually meant, so it wins outright rather than only being a fallback nobody reaches.
     * Callers that commit the result must handle the two-word shape (split on the space, treat it
     * as a real word boundary) rather than assuming the return value is always one token. */
    fun correct(typed: String): String? {
        val lower = typed.lowercase()
        if (lower.length < MIN_LENGTH || lower.length > MAX_LENGTH) return null
        if (!lower.all { it.isLetter() }) return null
        if (lower in frequencyByWord) return null // never "correct" an already-real word

        correctSplit(lower)?.let { return it }

        return bestByDistance(lower, maxDistance = 1, minFrequency = correctionFloor)
            ?: bestByDistance(lower, maxDistance = 2, minFrequency = strictFloor)
    }

    /** Checks whether [lower] is actually two real words typed without a space between them —
     * common for fast typists whose thumb slightly missed the spacebar — optionally with exactly
     * one stray extra character sitting where the space should have been (e.g. "thisbis" = "this"
     * + a stray 'b' + "is"). Tries the string as typed, plus every single-character-deleted
     * variant of it, at every possible split point; picks whichever split has the strongest
     * confidence in *both* halves (each must clear [strictFloor] — stricter than plain single-word
     * correction, since a split has more freedom to coincidentally line up than a single edit
     * does). */
    private fun correctSplit(lower: String): String? {
        if (lower.length < MIN_SPLIT_LENGTH) return null
        val candidates = (listOf(lower) + lower.indices.map { lower.removeRange(it, it + 1) }).distinct()
        var best: Pair<String, String>? = null
        var bestScore = -1
        for (candidate in candidates) {
            if (candidate.length < MIN_SPLIT_LENGTH) continue
            for (split in MIN_SPLIT_WORD_LENGTH..(candidate.length - MIN_SPLIT_WORD_LENGTH)) {
                val left = candidate.substring(0, split)
                val leftFreq = frequencyByWord[left] ?: continue
                if (leftFreq < strictFloor) continue
                val right = candidate.substring(split)
                val rightFreq = frequencyByWord[right] ?: continue
                if (rightFreq < strictFloor) continue
                val score = minOf(leftFreq, rightFreq)
                if (score > bestScore) {
                    bestScore = score
                    best = left to right
                }
            }
        }
        return best?.let { "${it.first} ${it.second}" }
    }

    /** Real-dictionary neighbors of [word] exactly one edit away — the candidate set a context-
     * aware caller (see [dev.omakey.core.predict.PredictionEngine.bigramRank]) can rank against
     * bigram context, for catching "real-word errors": a typo that happens to itself be a valid
     * word (so [correct] won't touch it) but isn't the word the surrounding context suggests was
     * actually meant. Unlike [correct], this deliberately does *not* filter by frequency or
     * exclude [word] itself being known — ranking by context is the caller's job, not raw
     * dictionary frequency. */
    fun realWordNeighbors(word: String): Set<String> {
        val lower = word.lowercase()
        if (lower.length < MIN_LENGTH || lower.length > MAX_LENGTH) return emptySet()
        if (!lower.all { it.isLetter() }) return emptySet()
        val neighbors = mutableSetOf<String>()
        for ((candidate, _) in candidatesNear(lower)) {
            if (candidate != lower && damerauLevenshtein(lower, candidate, 1) != null) neighbors += candidate
        }
        return neighbors
    }

    /** Best real dictionary word within [maxDistance] edits of [word] — closest distance wins,
     * ties broken by frequency. Scans only [candidatesNear] (same first letter as [word]) so full
     * Damerau-Levenshtein distance stays cheap even at distance 2. */
    private fun bestByDistance(word: String, maxDistance: Int, minFrequency: Int): String? {
        var best: String? = null
        var bestDistance = maxDistance + 1
        var bestFrequency = -1
        for ((candidate, freq) in candidatesNear(word)) {
            if (freq < minFrequency) continue
            val distance = damerauLevenshtein(word, candidate, maxDistance) ?: continue
            if (distance < bestDistance || (distance == bestDistance && freq > bestFrequency)) {
                best = candidate
                bestDistance = distance
                bestFrequency = freq
            }
        }
        return best
    }

    /** Dictionary words sharing [word]'s first letter — a classic spelling-correction prune (the
     * first character is rarely the one actually mistyped) that cuts a ~60k-word dictionary down
     * to roughly 1/26th before the O(length²) distance computation below. Trade-off: misses
     * corrections where the first letter itself was mistyped — accepted, since that's a
     * comparatively rare class of typo, in exchange for making real edit-distance-2 correction
     * affordable at all (naively hashing every generated 2-edit string, the previous approach,
     * is quadratic in the distance-1 candidate count — tens of thousands of strings for a 6-letter
     * word). */
    private fun candidatesNear(word: String): List<Pair<String, Int>> =
        if (word.isEmpty()) emptyList() else wordsByFirstLetter[word.first()] ?: emptyList()

    /** Standard Damerau-Levenshtein distance (insert/delete/substitute/adjacent-transpose).
     * Returns null as soon as it's clear the true distance exceeds [maxDistance] — both a cheap
     * early exit (length difference alone rules out most candidates instantly) and a way for
     * callers to treat "too far" and "not found" identically. */
    private fun damerauLevenshtein(a: String, b: String, maxDistance: Int): Int? {
        if (abs(a.length - b.length) > maxDistance) return null
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + cost)
                }
            }
        }
        val result = d[a.length][b.length]
        return if (result <= maxDistance) result else null
    }

    private companion object {
        const val MIN_LENGTH = 3
        const val MAX_LENGTH = 20
        const val MIN_SPLIT_WORD_LENGTH = 2
        const val MIN_SPLIT_LENGTH = MIN_SPLIT_WORD_LENGTH * 2

        /** Fuzzy contraction matching (see [contractionFor]) only applies to keys at least this
         * long — a 2-3 letter key fuzzy-matched against arbitrary typed text is far too prone to
         * colliding with unrelated short words to be worth the false-positive risk. */
        const val MIN_FUZZY_CONTRACTION_LENGTH = 5

        // Aiming for comprehensive coverage of standard English contractions, not just a handful
        // of examples — every common subject+verb and negative pairing, plus the modal-perfect
        // ("could've"/"should've"/etc) forms that are routinely typed without the apostrophe and
        // routinely typo'd on top of that (see contractionFor's fuzzy fallback).
        val CONTRACTIONS: Map<String, String> = mapOf(
            // I
            "im" to "I'm", "ive" to "I've", "id" to "I'd", "ill" to "I'll",
            // you
            "youre" to "you're", "youve" to "you've", "youd" to "you'd", "youll" to "you'll",
            // he / she / it
            "hes" to "he's", "hed" to "he'd", "hell" to "he'll",
            "shes" to "she's", "shed" to "she'd", "shell" to "she'll",
            "its" to "it's", "itd" to "it'd", "itll" to "it'll",
            // we
            "were" to "we're", "weve" to "we've", "wed" to "we'd", "well" to "we'll",
            // they
            "theyre" to "they're", "theyve" to "they've", "theyd" to "they'd", "theyll" to "they'll",
            // that / who / what / there / here / where / when / why / how
            "thats" to "that's", "thatd" to "that'd", "thatll" to "that'll",
            "whos" to "who's", "whod" to "who'd", "wholl" to "who'll",
            "whats" to "what's", "whatd" to "what'd", "whatll" to "what'll",
            "theres" to "there's", "thered" to "there'd", "therell" to "there'll",
            "heres" to "here's", "wheres" to "where's", "whens" to "when's",
            "whys" to "why's", "hows" to "how's",
            // negatives
            "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
            "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
            "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
            "wont" to "won't", "cant" to "can't",
            "couldnt" to "couldn't", "shouldnt" to "shouldn't", "wouldnt" to "wouldn't",
            "mightnt" to "mightn't", "mustnt" to "mustn't", "neednt" to "needn't",
            "shant" to "shan't", "oughtnt" to "oughtn't",
            // modal + have — very commonly typed without the apostrophe, and often mistyped on
            // top of that (the actual case this fuzzy matching exists for)
            "couldve" to "could've", "shouldve" to "should've", "wouldve" to "would've",
            "mightve" to "might've", "mustve" to "must've",
            // let's, y'all, ain't, o'clock
            "lets" to "let's", "yall" to "y'all", "aint" to "ain't", "oclock" to "o'clock",
        )
    }
}
