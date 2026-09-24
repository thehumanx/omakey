package dev.omakey.core.predict

import dev.omakey.core.locale.LanguageProfile
import dev.omakey.core.locale.toLookupForm
import dev.omakey.core.predict.lm.LanguageModel
import dev.omakey.core.predict.spatial.ChannelModel
import dev.omakey.core.predict.spatial.KeyboardGeometry
import dev.omakey.core.predict.spatial.TouchTrace
import kotlin.math.abs

/**
 * Typo correction, checked on every word-boundary keystroke (space/punctuation/enter).
 *
 * Candidates are ranked by a **single noisy-channel score**, the model mainstream keyboards use:
 *
 * ```
 * score(candidate) = -channelCost(typed | candidate) + λ · logP(candidate | context)
 * ```
 *
 * The first term (see [ChannelModel]) prices *how the letters differ*, aware of where the keys sit,
 * so a slip onto a neighbouring key is cheap and a jump across the keyboard is not. The second
 * prices *how likely the word is here*, using the same trigram backoff the prediction engine uses,
 * so context participates in correction rather than being applied as an afterthought.
 *
 * This replaced a lexicographic `(edit distance, then raw frequency)` comparison, under which a
 * distance-1 match to a marginal word always beat a distance-2 match to an overwhelmingly likely
 * one, and under which every substitution cost the same regardless of which keys were involved.
 * That ordering was the main reason the engine, when it acted at all, changed a misspelling into
 * the *wrong* word more often than the right one.
 *
 * Holds no dictionary of its own: the word set and its probabilities come straight from the
 * memory-mapped [LanguageModel]. Membership is a binary search over the blob, "words near this one"
 * is a contiguous id range, and probability is a `getShort`.
 */
class AutocorrectIndex(
    /** Tuned costs and the default (QWERTY, no equivalent letters) keyboard; [load] derives the
     * per-language [channel] from it. */
    private val baseChannel: ChannelModel = ChannelModel(),
    /** Rank in the frequency ordering a word must reach to be a correction target. Constructor
     * parameters so the tuning sweep can search over them; see the companion for the defaults and
     * why these are ranks rather than percentiles. */
    private val correctionRank: Int = CORRECTION_RANK,
    private val strictRank: Int = STRICT_RANK,
    private val maxEdits: Int = MAX_EDITS,
    private val maxBrowsableEdits: Int = MAX_BROWSABLE_EDITS,
) {

    @Volatile private var model: LanguageModel? = null
    @Volatile private var personal: PersonalLanguageModel = PersonalLanguageModel()
    @Volatile private var profile: LanguageProfile = LanguageProfile.English
    @Volatile private var correctionFloor: Float = Float.NEGATIVE_INFINITY
    @Volatile private var strictFloor: Float = Float.NEGATIVE_INFINITY

    /**
     * The letters a word can start with, and which of them sit next to each other on the keyboard —
     * what [forEachCandidate]'s first-letter routes iterate over.
     *
     * Built at [load] from the model's own alphabet, keeping only characters the language counts as
     * word characters, so English gets exactly `a`–`z` (its alphabet also holds the apostrophe) in
     * the same order the old hardcoded 26-letter table used, and another language gets its own.
     * Adjacency comes from the channel's [KeyboardGeometry] rather than being written out by hand,
     * so it stays true to whichever layout that geometry was derived from.
     */
    private class FirstLetters(
        val letters: CharArray,
        /** Single-character prefixes, held rather than built per lookup — [forEachCandidate] runs
         * on the typing hot path and asks for several of these per keystroke. */
        val prefixes: Array<String>,
        /** For each letter, the indices of letters whose keys physically touch it, ascending. */
        val neighbours: Array<IntArray>,
    ) {
        fun indexOf(character: Char): Int = letters.binarySearch(character.lowercaseChar()).let { if (it >= 0) it else -1 }

        companion object {
            val EMPTY = FirstLetters(CharArray(0), emptyArray(), emptyArray())
        }
    }

    @Volatile private var firstLetters: FirstLetters = FirstLetters.EMPTY

    /** [baseChannel] for the loaded language's keyboard and equivalent letters. */
    @Volatile private var channel: ChannelModel = baseChannel

    /** Reused across the thousands of distance computations one correction performs, instead of
     * allocating a matrix per candidate. Thread-local because corrections run on whatever
     * `Dispatchers.Default` thread the refresh coroutine landed on. */
    private val editScratch = ThreadLocal.withInitial { Array(MAX_LENGTH + 2) { IntArray(MAX_LENGTH + 2) } }
    private val costScratch = ThreadLocal.withInitial { Array(MAX_LENGTH + 2) { FloatArray(MAX_LENGTH + 2) } }

    /** The word being corrected, as a `CharArray`, for the two DP loops. Indexing a `String` there
     * costs a compact-strings encoding check per cell, and on HotSpot the JIT can only drop that
     * check while no non-Latin-1 string has ever been read — one "⇧" or "😊" key label anywhere in
     * the process and `correct()` got 25% slower (measured, AGENTS.md §66 Phase 2). Every
     * Devanagari or accented word is such a string, so the check has to go rather than be avoided. */
    private val charScratch = ThreadLocal.withInitial { CharArray(MAX_LENGTH + 2) }

    /** The candidate word, decoded once per DP rather than read cell by cell through
     * [LanguageModel.charAt] — see [LanguageModel.copyWord]. */
    private val candidateScratch = ThreadLocal.withInitial { CharArray(MAX_LENGTH + 2) }

    /** [text]'s characters in the thread's scratch buffer; valid up to `text.length`. */
    private fun charsOf(text: String): CharArray {
        val chars = charScratch.get()
        text.toCharArray(chars, 0, 0, minOf(text.length, chars.size))
        return chars
    }

    /** Left context for scoring, as word ids. Two words, because the language model's trigram tier
     * is where most of its discriminating power is. */
    data class Context(val previousId: Int = LanguageModel.NO_WORD, val beforePreviousId: Int = LanguageModel.NO_WORD) {
        companion object { val NONE = Context() }
    }

    fun contextOf(previousWord: String?, beforePreviousWord: String?): Context {
        val languageModel = model ?: return Context.NONE
        return Context(
            previousId = previousWord?.toLookupForm()?.let { languageModel.indexOf(it) } ?: LanguageModel.NO_WORD,
            beforePreviousId = beforePreviousWord?.toLookupForm()?.let { languageModel.indexOf(it) } ?: LanguageModel.NO_WORD,
        )
    }

    fun load(
        languageModel: LanguageModel,
        personalModel: PersonalLanguageModel,
        languageProfile: LanguageProfile = LanguageProfile.English,
        geometry: KeyboardGeometry = baseChannel.geometry,
    ) {
        profile = languageProfile
        personal = personalModel
        channel = if (languageProfile.equivalentLetters.isEmpty() && geometry === baseChannel.geometry) {
            baseChannel
        } else {
            baseChannel.forLanguage(geometry, languageProfile.equivalentLetters)
        }
        val letters = CharArray(languageModel.alphabetSize) { languageModel.alphabetChar(it) }
            .filter { it.isLetter() && languageProfile.isWordChar(it) }
            .toCharArray()
        firstLetters = FirstLetters(
            letters = letters,
            prefixes = Array(letters.size) { letters[it].toString() },
            neighbours = Array(letters.size) { index ->
                letters.indices.filter { it != index && channel.geometry.areAdjacent(letters[index], letters[it]) }.toIntArray()
            },
        )
        model = languageModel

        // Only correct *into* reasonably common words — otherwise a typed non-word that happens to
        // sit close to an obscure entry gets "corrected" into something the user has never heard
        // of, which is worse than not correcting at all. Two tiers: a looser floor for
        // close/high-confidence matches (distance 1, word splits) and a stricter one for the
        // inherently less certain distance-2 fallback.
        //
        // Expressed as an absolute rank rather than a percentile of the vocabulary, deliberately.
        // A percentile silently loosens as the vocabulary grows — a move from a 60k to a 150k word
        // model would take "top 20%" from the 12,000th most common word to the 30,000th, admitting
        // a long tail of rare words as correction targets without anyone changing a threshold.
        val sorted = FloatArray(languageModel.vocabularySize) { languageModel.unigramLogProbability(it) }
        sorted.sort() // ascending, so rank N from the top is at size - 1 - N
        fun floorAtRank(rank: Int): Float =
            sorted.getOrElse(sorted.size - 1 - rank) { sorted.firstOrNull() ?: Float.NEGATIVE_INFINITY }
        correctionFloor = floorAtRank(correctionRank)
        strictFloor = floorAtRank(strictRank)
    }

    /** Marks a word as known (e.g. explicitly saved via swipe-up) so it's never "corrected" away
     * in the future, even if it's a name/slang/word absent from the bundled vocabulary. */
    fun learn(word: String) {
        val lower = word.toLookupForm()
        if (lower.isEmpty() || isKnown(lower)) return
        personal.record(lower, explicit = true)
    }

    /** Reverses [learn] — removes [word] so it goes back to being correctable, e.g. swiping up a
     * second time on an already-learned word. Only ever touches words the user added: a bundled
     * vocabulary word can never be unlearned this way, so "unlearn" can't silently turn autocorrect
     * against an ordinary word like "cat". */
    fun unlearn(word: String) {
        val lower = word.toLookupForm()
        if (!personal.isExplicit(lower)) return
        personal.forget(lower)
    }

    /** Whether [word] is a real/known word — bundled vocabulary or the user's own. */
    fun isKnown(word: String): Boolean {
        val lower = word.toLookupForm()
        // isTrusted, not contains: a word picked up from casual typing must not gain immunity from
        // correction just by having been typed once. See PersonalLanguageModel's class doc.
        if (personal.isTrusted(lower)) return true
        val languageModel = model ?: return false
        return languageModel.indexOf(lower) != LanguageModel.NO_WORD
    }

    /** Whether [word] came from the user's own swipe-up save rather than the bundled vocabulary —
     * exactly what determines whether a second swipe-up can [unlearn] it. */
    fun isUserAdded(word: String): Boolean = personal.isExplicit(word.toLookupForm())

    /**
     * Curated apostrophe-insertion fixes ("im" -> "I'm", "weve" -> "we've").
     *
     * The bundled vocabulary contains apostrophe forms as first-class words, so general edit
     * distance can reach "don't" from "dont" on its own. This map survives for the cases distance
     * alone gets wrong: the apostrophe-less spelling is usually *also* a real word ("were"/"we're",
     * "well"/"we'll", "its"/"it's", "id"/"I'd"), so [correct] will not touch it, and several are
     * genuinely ambiguous — offering the expansion for the user to accept is right, silently
     * applying it is not.
     *
     * Tries an exact match first, then a fuzzy one-edit match against keys of at least
     * [MIN_FUZZY_CONTRACTION_LENGTH] characters — so "shoudve" still resolves to "should've". Short
     * keys ("im", "id", "ive") are exact-only: fuzzy-matching a 2-3 letter string collides with
     * unrelated short words far too readily.
     */
    fun contractionFor(typed: String): String? {
        val lower = typed.toLookupForm()
        profile.contractions[lower]?.let { return it }
        var best: String? = null
        var bestDistance = 2
        for ((key, expansion) in profile.contractions) {
            if (key.length < MIN_FUZZY_CONTRACTION_LENGTH) continue
            val distance = editDistance(lower, key, 1) ?: continue
            if (distance < bestDistance) {
                bestDistance = distance
                best = expansion
            }
        }
        return best
    }

    /**
     * Every plausible alternative for [word] worth offering on the suggestion strip — deliberately
     * broader than [correct]: applies whether or not [word] is itself valid, because "valid
     * dictionary word" and "what the user actually meant" are different questions. Typing "well"
     * perfectly correctly doesn't mean "we'll" wasn't the intent; only the user can tell, so both
     * get offered rather than the keyboard silently deciding (which is exactly why this is a
     * browsable list, not an auto-apply).
     *
     * Ordered by the same noisy-channel score [correct] uses, so the strip agrees with the
     * auto-apply decision instead of ranking by a different rule. The curated contraction leads
     * when there is one; a word split is offered alongside single-word candidates.
     * Deduplicated case-insensitively, capped at [limit].
     */
    fun alternatives(
        word: String,
        limit: Int,
        context: Context = Context.NONE,
        taps: TouchTrace.Taps? = null,
    ): List<String> {
        val languageModel = model ?: return emptyList()
        val lower = word.toLookupForm()
        if (limit <= 0 || lower.isEmpty()) return emptyList()
        if (!profile.isWord(lower)) return emptyList()

        val results = LinkedHashSet<String>()
        // Checked before the length gate below — several contraction keys ("im", "id") are shorter
        // than MIN_LENGTH and would otherwise never reach contractionFor() at all.
        contractionFor(lower)?.let { results += it }
        if (lower.length !in MIN_LENGTH..MAX_LENGTH) return results.take(limit).toList()

        val scored = ArrayList<Scored>(SCORED_CAPACITY)
        collectCandidates(lower, correctionFloor, context, maxBrowsableEdits, taps, scored)
        if (!isKnown(lower)) {
            correctSplitCandidate(lower, context)?.let { scored += it }
        }
        scored.sortByDescending { it.score }
        for (candidate in scored) {
            if (results.size >= limit) break
            if (results.none { it.equals(candidate.text, ignoreCase = true) }) results += candidate.text
        }
        return results.take(limit).toList()
    }

    /**
     * A corrected (lowercase) word if [typed] is confidently a typo of a much more likely word, or
     * null to leave it alone — already known, too short, contains non-letters, or nothing scores
     * well enough.
     *
     * Can also return two words separated by a single space (e.g. `"this is"`) when [typed] looks
     * like two real words typed without the space — see [correctSplitCandidate]. Callers committing
     * the result must handle the two-word shape rather than assuming one token.
     */
    fun correct(typed: String, context: Context = Context.NONE, taps: TouchTrace.Taps? = null): String? {
        model ?: return null
        val lower = typed.toLookupForm()
        if (lower.length < MIN_LENGTH || lower.length > MAX_LENGTH) return null
        if (!profile.isWord(lower)) return null
        if (isKnown(lower)) return null // never "correct" an already-real word

        val scored = ArrayList<Scored>(SCORED_CAPACITY)
        collectCandidates(lower, correctionFloor, context, maxEdits, taps, scored)
        correctSplitCandidate(lower, context)?.let { scored += it }
        return scored.maxByOrNull { it.score }?.text
    }

    /** Real-vocabulary neighbours of [word] exactly one edit away — the candidate set a
     * context-aware caller ranks to catch "real-word errors": a typo that is itself a valid word
     * (so [correct] won't touch it) but isn't what the surrounding context suggests was meant.
     * Deliberately does *not* filter by frequency — ranking is the caller's job. */
    fun realWordNeighbors(word: String): Set<String> {
        val languageModel = model ?: return emptySet()
        val lower = word.toLookupForm()
        if (lower.length < MIN_LENGTH || lower.length > MAX_LENGTH) return emptySet()
        if (!profile.isWord(lower)) return emptySet()
        val neighbours = mutableSetOf<String>()
        forEachCandidate(lower) { id, _ ->
            val distance = editDistance(lower, id, 1)
            if (distance != null && distance > 0) neighbours += languageModel.wordAt(id)
        }
        return neighbours
    }

    private class Scored(val text: String, val score: Float)

    /**
     * Scores every vocabulary word within [MAX_EDITS] edits of [typed] that clears [floor],
     * appending them to [into].
     *
     * The integer edit distance is used only to bound the candidate set — it decides *whether* a
     * word is considered, never which one wins. That ranking is the combined channel + language
     * score, which is why a two-edit correction into a very likely word can now beat a one-edit
     * correction into an unlikely one.
     */
    private fun collectCandidates(
        typed: String,
        floor: Float,
        context: Context,
        editBound: Int,
        taps: TouchTrace.Taps?,
        into: MutableList<Scored>,
    ) {
        val languageModel = model ?: return
        // A candidate reached by assuming the *first* letter was itself mistyped has already spent
        // evidence before any of the rest of the word is examined, so it is held to the same
        // stricter bar the distance-2 fallback uses. Same tiering idea, same reason: a less
        // certain route into the vocabulary needs a more certain destination.
        val offFirstLetterFloor = maxOf(floor, strictFloor)
        forEachCandidate(typed) { id, offFirstLetter ->
            val prior = languageModel.unigramLogProbability(id)
            if (prior < (if (offFirstLetter) offFirstLetterFloor else floor)) return@forEachCandidate
            val distance = editDistance(typed, id, editBound) ?: return@forEachCandidate
            if (distance == 0) return@forEachCandidate
            val cost = channelCost(typed, id, taps)
            val languageScore = personal.adjustById(
                id,
                languageModel.logProbability(id, context.previousId, context.beforePreviousId),
            )
            into += Scored(
                languageModel.wordAt(id),
                -cost + channel.languageModelWeight * languageScore,
            )
        }
    }

    /**
     * Checks whether [lower] is two real words typed without a space — common for fast typists
     * whose thumb missed the spacebar — optionally with one stray extra character where the space
     * should have been ("thisbis" = "this" + stray 'b' + "is").
     *
     * Scored on the same scale as a single-word candidate: the channel pays for the missing space
     * (and for the stray character, when there was one), and the language term is the probability
     * of the **whole two-word sequence**, `log P(left) + log P(right | left)`.
     *
     * That comparability is the entire point. An earlier version scored a split by its *weaker
     * half's* frequency and compared that against a single word's frequency, which is not a
     * like-for-like comparison: a split gets to explain the same letters using two words, and
     * because short common words are individually very probable, almost any long word could be
     * beaten by some pair of short ones. Real damage, caught by the evaluation harness — "seperate"
     * was being "corrected" to "see rate" and "wierd" to "ie rd".
     */
    private fun correctSplitCandidate(lower: String, context: Context): Scored? {
        val languageModel = model ?: return null
        if (lower.length < MIN_SPLIT_LENGTH) return null
        var best: String? = null
        var bestScore = Float.NEGATIVE_INFINITY
        // The string as typed, plus every single-character-deleted variant of it.
        for (removed in -1 until lower.length) {
            val candidate = if (removed < 0) lower else lower.removeRange(removed, removed + 1)
            if (candidate.length < MIN_SPLIT_LENGTH) continue
            // Missing space is always a deletion; a stray character is an extra insertion.
            val channelCost = channel.deletion() + if (removed < 0) 0f else channel.insertion()
            for (split in MIN_SPLIT_WORD_LENGTH..(candidate.length - MIN_SPLIT_WORD_LENGTH)) {
                val leftId = languageModel.indexOf(candidate.substring(0, split))
                if (leftId == LanguageModel.NO_WORD) continue
                if (languageModel.unigramLogProbability(leftId) < strictFloor) continue
                val rightId = languageModel.indexOf(candidate.substring(split))
                if (rightId == LanguageModel.NO_WORD) continue
                if (languageModel.unigramLogProbability(rightId) < strictFloor) continue
                // The right half is scored *in context of the left*, so a pair that genuinely
                // occurs together ("this is") is rewarded over one that merely consists of two
                // common words ("see rate").
                val languageScore =
                    languageModel.logProbability(leftId, context.previousId, context.beforePreviousId) +
                        languageModel.logProbability(rightId, leftId, context.previousId)
                val score = -channelCost + channel.languageModelWeight * languageScore
                if (score > bestScore) {
                    bestScore = score
                    best = "${candidate.substring(0, split)} ${candidate.substring(split)}"
                }
            }
        }
        return best?.let { Scored(it, bestScore) }
    }

    /**
     * Every vocabulary word worth scoring as a correction of [typed], with a flag for whether it
     * was reached by assuming [typed]'s **first** letter is itself wrong (the caller holds those to
     * a stricter frequency bar — see [collectCandidates]).
     *
     * This used to be a single contiguous id range: the words sharing [typed]'s exact first letter,
     * the classic spelling-correction prune. It is fast and mostly right — the first character of a
     * word really is the one least often mistyped — but "mostly" hid a whole class of typos the
     * engine simply could not see. "qccount" cannot reach "account", "wpple" cannot reach "apple",
     * "hte" cannot reach "the": the intended word is one obvious slip away, but it lives in a range
     * that was never scanned, so no amount of better ranking could ever surface it.
     *
     * The fix is *not* to drop the prune. That was measured (see AGENTS.md §6) and made accuracy
     * worse while costing 14× the latency, because scanning all 26 ranges under a flat edit bound
     * mostly admits unrelated words that happen to fall within two edits. The prune was doing real
     * work; it was just drawn in the wrong place — on letter *identity* rather than on how
     * plausible the slip is.
     *
     * So the first letter is now treated like every other letter, with the search bounded the same
     * way the rest of the word already is — by the channel model. Four routes, all of them a
     * single-character error at position 0:
     *
     *  - **As typed.** The overwhelmingly likely case, and the only tier held to the ordinary floor.
     *  - **Substitution**, restricted to keys physically adjacent to the one typed
     *    ([KeyboardGeometry.areAdjacent]) — the thumb landed one key off. `q` sits directly above
     *    `a`, which is the entire "qccount" story. A jump across the keyboard is not a slip, and
     *    admitting those is exactly what made removing the prune wholesale perform badly.
     *  - **Insertion / transposition**, both of which leave the intended word starting with
     *    `typed[1]` ("xaccount", "hte") — one extra range, not a guess about which.
     *  - **Deletion**, where the first letter never got typed at all ("ccount" → "account"). The
     *    intended word is [typed] with one letter put back in front, which is 26 binary searches
     *    rather than 26 more ranges to scan — so it costs essentially nothing and needs no
     *    adjacency restriction, there being no typed key to be near.
     *
     * That is ~8 ranges instead of 1 or 26, and the extra ones are pre-filtered by a float compare
     * before any distance matrix is built.
     */
    private inline fun forEachCandidate(typed: String, action: (id: Int, offFirstLetter: Boolean) -> Unit) {
        val languageModel = model ?: return
        if (typed.isEmpty()) return

        for (id in languageModel.prefixRange(typed.substring(0, 1))) action(id, false)

        val alphabet = firstLetters
        val typedFirst = alphabet.indexOf(typed[0])
        val alternates = alternateFirstLetters(typed, alphabet)
        for (letter in alternates.indices) {
            if (alternates[letter]) for (id in languageModel.prefixRange(alphabet.prefixes[letter])) action(id, true)
        }

        // Deletion at position 0. Skips letters whose whole range was just scanned, so the same id
        // is never handed to `action` twice.
        if (typed.length in (MIN_LENGTH - 1) until MAX_LENGTH) {
            if (typedFirst >= 0) alternates[typedFirst] = true
            val prepended = StringBuilder(typed.length + 1).append(' ').append(typed)
            for (letter in alternates.indices) {
                if (alternates[letter]) continue
                prepended.setCharAt(0, alphabet.letters[letter])
                val id = languageModel.indexOf(prepended)
                if (id != LanguageModel.NO_WORD) action(id, true)
            }
        }
    }

    /** First letters, other than [typed]'s own, that a correction of [typed] may start with, as a
     * flag per letter of [alphabet] — see [forEachCandidate] for what each one represents. Iterated
     * in alphabet order, which for English is the a–z order the previous bitmask produced. */
    private fun alternateFirstLetters(typed: String, alphabet: FirstLetters): BooleanArray {
        val alternates = BooleanArray(alphabet.letters.size)
        val first = alphabet.indexOf(typed[0])
        if (first >= 0) for (neighbour in alphabet.neighbours[first]) alternates[neighbour] = true
        if (typed.length > 1) {
            val second = alphabet.indexOf(typed[1])
            if (second >= 0) alternates[second] = true
        }
        // The typed letter's own range is always scanned separately, and a letter counts itself as
        // adjacent to nothing, but clear it explicitly: typed[1] == typed[0] ("aardvark") would
        // otherwise put it back.
        if (first >= 0) alternates[first] = false
        return alternates
    }

    // --- distance and cost ----------------------------------------------------------------------

    /** Plain Damerau-Levenshtein against the vocabulary word [id], used only to bound the candidate
     * set. Reads characters straight out of the mapped blob so scanning thousands of candidates
     * allocates nothing. Null once the true distance is known to exceed [maxDistance]. */
    private fun editDistance(a: String, id: Int, maxDistance: Int): Int? {
        val languageModel = model ?: return null
        val length = languageModel.wordLength(id)
        if (abs(a.length - length) > maxDistance) return null
        if (a.length > MAX_LENGTH || length > MAX_LENGTH) return null
        return editDistance(a, null, id, length, maxDistance)
    }

    private fun editDistance(a: String, b: String, maxDistance: Int): Int? {
        if (abs(a.length - b.length) > maxDistance) return null
        if (a.length > MAX_LENGTH || b.length > MAX_LENGTH) return null
        return editDistance(a, b, -1, b.length, maxDistance)
    }

    private fun editDistance(text: String, b: String?, id: Int, bLength: Int, maxDistance: Int): Int? {
        val languageModel = model
        val d = editScratch.get()
        val a = charsOf(text)
        val aLength = text.length
        val candidate = candidateScratch.get()
        if (b == null) languageModel!!.copyWord(id, candidate) else b.toCharArray(candidate, 0, 0, minOf(b.length, candidate.size))
        for (i in 0..aLength) d[i][0] = i
        for (j in 0..bLength) d[0][j] = j
        for (i in 1..aLength) {
            var rowBest = Int.MAX_VALUE
            val aChar = a[i - 1]
            for (j in 1..bLength) {
                val bChar = candidate[j - 1]
                val cost = if (aChar == bChar) 0 else 1
                var value = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1) {
                    val bPrevious = candidate[j - 2]
                    if (aChar == bPrevious && a[i - 2] == bChar) value = minOf(value, d[i - 2][j - 2] + cost)
                }
                d[i][j] = value
                if (value < rowBest) rowBest = value
            }
            // A whole row above the bound means no completion can come back under it.
            if (rowBest > maxDistance) return null
        }
        val result = d[aLength][bLength]
        return if (result <= maxDistance) result else null
    }

    /** `-log P(typed | candidate)` under [ChannelModel] — the same Damerau recurrence, but with
     * real per-edit costs instead of 1 apiece, so which keys were involved actually matters. */
    private fun channelCost(typedText: String, id: Int, taps: TouchTrace.Taps?): Float {
        val languageModel = model ?: return Float.MAX_VALUE
        val length = languageModel.wordLength(id)
        val d = costScratch.get()
        val typed = charsOf(typedText)
        val typedLength = typedText.length
        val intended = candidateScratch.get()
        languageModel.copyWord(id, intended)
        val channel = channel
        val insertion = channel.insertion()
        val deletion = channel.deletion()
        for (i in 0..typedLength) d[i][0] = i * insertion
        for (j in 0..length) d[0][j] = j * deletion
        for (i in 1..typedLength) {
            val typedChar = typed[i - 1]
            for (j in 1..length) {
                val intendedChar = intended[j - 1]
                val substitution = channel.substitutionAt(typedChar, intendedChar, taps, i - 1)
                var value = minOf(
                    d[i - 1][j] + insertion,
                    d[i][j - 1] + deletion,
                    d[i - 1][j - 1] + substitution,
                )
                if (i > 1 && j > 1) {
                    val intendedPrevious = intended[j - 2]
                    if (typedChar == intendedPrevious && typed[i - 2] == intendedChar) {
                        value = minOf(value, d[i - 2][j - 2] + channel.transposition())
                    }
                }
                d[i][j] = value
            }
        }
        return d[typedLength][length]
    }

    private companion object {
        const val MIN_LENGTH = 3
        const val MAX_LENGTH = 24
        const val MIN_SPLIT_WORD_LENGTH = 2
        const val MIN_SPLIT_LENGTH = MIN_SPLIT_WORD_LENGTH * 2

        /**
         * Structural bound on the candidate set for the **silent auto-apply** path. Ranking within
         * it is the combined score's job.
         *
         * Two, not three, and measured rather than assumed: allowing a third edit finds 1.6 points
         * more correct answers but produces 9.9 points more *wrong* ones, because the candidate
         * pool at distance 3 is mostly unrelated words that happen to be reachable. For a change
         * applied without asking, that trade is clearly bad.
         */
        const val MAX_EDITS = 2

        /**
         * The same bound for the **suggestion strip**, which is deliberately looser.
         *
         * The strip is browsable — the user reads it and picks — so an extra speculative candidate
         * costs a glance, while a missing one costs a manual retype. The measurement that rules
         * distance 3 out for auto-apply simultaneously argues *for* it here: strip recall rises
         * from 48.6% to 55.4%. Same evidence, opposite conclusion, because the two paths have
         * genuinely different costs of being wrong.
         */
        const val MAX_BROWSABLE_EDITS = 3

        /** Typical number of candidates that clear the floor and the edit bound — sizing the list
         * up front avoids regrowth on the typing hot path. */
        const val SCORED_CAPACITY = 32

        /** Correction targets must be at least this common — a rank in the frequency ordering, so
         * the bar doesn't move when the vocabulary size changes. */
        const val CORRECTION_RANK = 25_000
        const val STRICT_RANK = 8_000

        /** Fuzzy contraction matching only applies to keys at least this long — a 2-3 letter key
         * fuzzy-matched against arbitrary text collides with unrelated short words too readily. */
        const val MIN_FUZZY_CONTRACTION_LENGTH = 5
    }
}
