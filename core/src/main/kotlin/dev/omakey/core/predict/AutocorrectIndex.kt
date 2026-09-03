package dev.omakey.core.predict

import dev.omakey.core.predict.lm.LanguageModel
import dev.omakey.core.predict.spatial.ChannelModel
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
    private val channel: ChannelModel = ChannelModel(),
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
    @Volatile private var correctionFloor: Float = Float.NEGATIVE_INFINITY
    @Volatile private var strictFloor: Float = Float.NEGATIVE_INFINITY

    /** Reused across the thousands of distance computations one correction performs, instead of
     * allocating a matrix per candidate. Thread-local because corrections run on whatever
     * `Dispatchers.Default` thread the refresh coroutine landed on. */
    private val editScratch = ThreadLocal.withInitial { Array(MAX_LENGTH + 2) { IntArray(MAX_LENGTH + 2) } }
    private val costScratch = ThreadLocal.withInitial { Array(MAX_LENGTH + 2) { FloatArray(MAX_LENGTH + 2) } }

    /** Left context for scoring, as word ids. Two words, because the language model's trigram tier
     * is where most of its discriminating power is. */
    data class Context(val previousId: Int = LanguageModel.NO_WORD, val beforePreviousId: Int = LanguageModel.NO_WORD) {
        companion object { val NONE = Context() }
    }

    fun contextOf(previousWord: String?, beforePreviousWord: String?): Context {
        val languageModel = model ?: return Context.NONE
        return Context(
            previousId = previousWord?.lowercase()?.let { languageModel.indexOf(it) } ?: LanguageModel.NO_WORD,
            beforePreviousId = beforePreviousWord?.lowercase()?.let { languageModel.indexOf(it) } ?: LanguageModel.NO_WORD,
        )
    }

    fun load(languageModel: LanguageModel, personalModel: PersonalLanguageModel) {
        personal = personalModel
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
        val lower = word.lowercase()
        if (lower.isEmpty() || isKnown(lower)) return
        personal.record(lower, explicit = true)
    }

    /** Reverses [learn] — removes [word] so it goes back to being correctable, e.g. swiping up a
     * second time on an already-learned word. Only ever touches words the user added: a bundled
     * vocabulary word can never be unlearned this way, so "unlearn" can't silently turn autocorrect
     * against an ordinary word like "cat". */
    fun unlearn(word: String) {
        val lower = word.lowercase()
        if (!personal.isExplicit(lower)) return
        personal.forget(lower)
    }

    /** Whether [word] is a real/known word — bundled vocabulary or the user's own. */
    fun isKnown(word: String): Boolean {
        val lower = word.lowercase()
        // isTrusted, not contains: a word picked up from casual typing must not gain immunity from
        // correction just by having been typed once. See PersonalLanguageModel's class doc.
        if (personal.isTrusted(lower)) return true
        val languageModel = model ?: return false
        return languageModel.indexOf(lower) != LanguageModel.NO_WORD
    }

    /** Whether [word] came from the user's own swipe-up save rather than the bundled vocabulary —
     * exactly what determines whether a second swipe-up can [unlearn] it. */
    fun isUserAdded(word: String): Boolean = personal.isExplicit(word.lowercase())

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
        val lower = typed.lowercase()
        CONTRACTIONS[lower]?.let { return it }
        var best: String? = null
        var bestDistance = 2
        for ((key, expansion) in CONTRACTIONS) {
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
        val lower = word.lowercase()
        if (limit <= 0 || lower.isEmpty()) return emptyList()
        if (!lower.all { it.isLetter() }) return emptyList()

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
        val lower = typed.lowercase()
        if (lower.length < MIN_LENGTH || lower.length > MAX_LENGTH) return null
        if (!lower.all { it.isLetter() }) return null
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
        val lower = word.lowercase()
        if (lower.length < MIN_LENGTH || lower.length > MAX_LENGTH) return emptySet()
        if (!lower.all { it.isLetter() }) return emptySet()
        val neighbours = mutableSetOf<String>()
        for (id in candidatesNear(lower)) {
            val distance = editDistance(lower, id, 1) ?: continue
            if (distance > 0) neighbours += languageModel.wordAt(id)
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
        for (id in candidatesNear(typed)) {
            val prior = languageModel.unigramLogProbability(id)
            if (prior < floor) continue
            val distance = editDistance(typed, id, editBound) ?: continue
            if (distance == 0) continue
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

    /** Vocabulary words sharing [word]'s first letter — the classic spelling-correction prune, and
     * free here: the vocabulary is lexicographically ordered, so this is a contiguous id range
     * rather than a bucket map that has to be built and held in memory.
     *
     * Known limitation: a mistyped *first* letter is unreachable, so "hte" cannot find "the".
     * Lifting it means walking a trie with an edit-distance cutoff instead of scanning a range. */
    private fun candidatesNear(word: String): IntRange {
        val languageModel = model ?: return IntRange.EMPTY
        if (word.isEmpty()) return IntRange.EMPTY
        return languageModel.prefixRange(word.substring(0, 1))
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

    private fun editDistance(a: String, b: String?, id: Int, bLength: Int, maxDistance: Int): Int? {
        val languageModel = model
        val d = editScratch.get()
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..bLength) d[0][j] = j
        for (i in 1..a.length) {
            var rowBest = Int.MAX_VALUE
            val aChar = a[i - 1]
            for (j in 1..bLength) {
                val bChar = b?.get(j - 1) ?: languageModel!!.charAt(id, j - 1)
                val cost = if (aChar == bChar) 0 else 1
                var value = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1) {
                    val bPrevious = b?.get(j - 2) ?: languageModel!!.charAt(id, j - 2)
                    if (aChar == bPrevious && a[i - 2] == bChar) value = minOf(value, d[i - 2][j - 2] + cost)
                }
                d[i][j] = value
                if (value < rowBest) rowBest = value
            }
            // A whole row above the bound means no completion can come back under it.
            if (rowBest > maxDistance) return null
        }
        val result = d[a.length][bLength]
        return if (result <= maxDistance) result else null
    }

    /** `-log P(typed | candidate)` under [ChannelModel] — the same Damerau recurrence, but with
     * real per-edit costs instead of 1 apiece, so which keys were involved actually matters. */
    private fun channelCost(typed: String, id: Int, taps: TouchTrace.Taps?): Float {
        val languageModel = model ?: return Float.MAX_VALUE
        val length = languageModel.wordLength(id)
        val d = costScratch.get()
        for (i in 0..typed.length) d[i][0] = i * channel.insertion()
        for (j in 0..length) d[0][j] = j * channel.deletion()
        for (i in 1..typed.length) {
            val typedChar = typed[i - 1]
            for (j in 1..length) {
                val intendedChar = languageModel.charAt(id, j - 1)
                var value = minOf(
                    d[i - 1][j] + channel.insertion(),
                    d[i][j - 1] + channel.deletion(),
                    d[i - 1][j - 1] + channel.substitutionAt(typedChar, intendedChar, taps, i - 1),
                )
                if (i > 1 && j > 1) {
                    val intendedPrevious = languageModel.charAt(id, j - 2)
                    if (typedChar == intendedPrevious && typed[i - 2] == intendedChar) {
                        value = minOf(value, d[i - 2][j - 2] + channel.transposition())
                    }
                }
                d[i][j] = value
            }
        }
        return d[typed.length][length]
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

        // Comprehensive coverage of standard English contractions whose apostrophe-less spelling
        // is itself a real word, or which are ambiguous enough that auto-applying would be wrong.
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
            // modal + have — routinely typed without the apostrophe and mistyped on top of that
            "couldve" to "could've", "shouldve" to "should've", "wouldve" to "would've",
            "mightve" to "might've", "mustve" to "must've",
            // let's, y'all, ain't, o'clock
            "lets" to "let's", "yall" to "y'all", "aint" to "ain't", "oclock" to "o'clock",
        )
    }
}
