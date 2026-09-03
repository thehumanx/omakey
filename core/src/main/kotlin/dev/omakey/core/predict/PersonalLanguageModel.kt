package dev.omakey.core.predict

import dev.omakey.core.predict.lm.LanguageModel
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * What the user's own typing has taught the keyboard, held **separately** from the bundled model
 * and mixed in at scoring time.
 *
 * ## Why separate
 *
 * The previous design added the user's words into the same frequency column as the seeded corpus,
 * where values ran to 60,000. A saved word therefore arrived with a count of 1 (or 50 for an
 * explicit save) and was outranked by essentially every word in the dictionary — a word the user
 * had deliberately taught the keyboard could not beat one they had never typed. Personalisation
 * was, in effect, decorative.
 *
 * Here a personal count is scored against *personal* mass, so a name typed twice is already
 * strong evidence within its own model, and [adjust] interpolates that against the corpus
 * probability. One use of an unusual word is enough to lift it above corpus words of similar
 * rarity, without letting it threaten genuinely common vocabulary.
 *
 * ## Knowing a word is not the same as trusting it
 *
 * This is the distinction that makes implicit learning safe, and getting it wrong is a bug this
 * project has already shipped once: learning on every word boundary meant any uncaught typo was
 * silently marked "known" and became **immune to correction forever**, confirmed on a device.
 * Learning was disabled entirely in response.
 *
 * So the two consequences of learning are separated:
 *
 *  - **Ranking** ([adjust]) applies to everything typed. A typo picks up a small, decaying boost;
 *    it cannot promote itself into a plausible correction target, and it ages out.
 *  - **Correction immunity** ([isTrusted]) is much harder to earn. An explicit swipe-up save grants
 *    it immediately — the user said so. Implicit learning grants it only after
 *    [IMPLICIT_TRUST_THRESHOLD] separate uses, on the reasoning that a typo is a slip and a slip
 *    does not reliably repeat, whereas a name does.
 *
 * ## Recency
 *
 * Counts decay exponentially with a half-life of [HALF_LIFE_DAYS], applied on write rather than on
 * read so the typing hot path never pays for it. Vocabulary follows what someone is currently
 * writing about; a burst of project names from six months ago should fade rather than compete
 * forever.
 *
 * Not thread-safe: written from the IME's main-thread key handling, read from scoring coroutines
 * only after [load] has populated it.
 */
class PersonalLanguageModel(private val clock: () -> Long = { System.currentTimeMillis() }) {

    /** One learned word. [count] is decayed to [lastUsed]; compare two entries only after bringing
     * both to the same instant with [decayedCount]. */
    data class Entry(
        val word: String,
        val count: Float,
        val lastUsed: Long,
        /** Saved deliberately via the swipe-up gesture, as opposed to picked up from typing. Only
         * these can be un-learned by swiping up again, and only these are trusted on sight. */
        val explicit: Boolean,
    )

    private val byWord = HashMap<String, Entry>()

    /** Personal entries that are also in the bundled vocabulary, keyed by word id.
     *
     * The correction hot loop walks candidates as integer ids and never materialises their strings
     * — that is what keeps it allocation-free across thousands of candidates. A string-keyed lookup
     * there would undo it, so in-vocabulary personal words are indexed by id up front. Words *not*
     * in the vocabulary can't appear in that loop at all, and reach suggestions through
     * [matching] instead. */
    private val byVocabularyId = HashMap<Int, Entry>()

    private var totalCount = 0f
    private var vocabulary: LanguageModel? = null

    val size: Int get() = byWord.size

    val isEmpty: Boolean get() = byWord.isEmpty()

    fun load(entries: List<Entry>, languageModel: LanguageModel?) {
        byWord.clear()
        byVocabularyId.clear()
        vocabulary = languageModel
        entries.forEach { index(it) }
        recomputeTotal()
    }

    /**
     * Records one use of [word], returning the entry to persist, or null if it wasn't worth
     * recording.
     *
     * Words the corpus already ranks well are skipped: re-counting "the" teaches nothing the corpus
     * doesn't say better, and would spend the [MAX_ENTRIES] budget on words that need no help.
     * Only vocabulary rarer than [IMPLICIT_LEARNING_RANK], or absent from it entirely, is worth
     * remembering — that is where a personal signal actually changes an outcome.
     */
    fun record(word: String, explicit: Boolean): Entry? {
        val lower = word.lowercase()
        if (lower.isEmpty()) return null
        val existing = byWord[lower]
        if (!explicit && existing == null && !isWorthLearning(lower)) return null

        val now = clock()
        val decayed = existing?.decayedCount(now) ?: 0f
        val updated = Entry(
            word = lower,
            count = decayed + if (explicit) EXPLICIT_SAVE_WEIGHT else 1f,
            lastUsed = now,
            // Explicitness is sticky: a word the user deliberately saved stays deliberately saved
            // even if it is later typed casually.
            explicit = explicit || existing?.explicit == true,
        )
        index(updated)
        recomputeTotal()
        evictIfOverCapacity()
        return updated
    }

    fun forget(word: String) {
        val lower = word.lowercase()
        val removed = byWord.remove(lower) ?: return
        vocabulary?.indexOf(lower)?.takeIf { it != LanguageModel.NO_WORD }?.let { byVocabularyId.remove(it) }
        totalCount -= removed.count
        if (totalCount < 0f) totalCount = 0f
    }

    fun contains(word: String): Boolean = word.lowercase() in byWord

    /** Whether this word may be treated as real, and so left alone by autocorrect. See the class
     * doc — this is deliberately much harder to earn than mere presence. */
    fun isTrusted(word: String): Boolean {
        val entry = byWord[word.lowercase()] ?: return false
        return entry.explicit || entry.decayedCount(clock()) >= IMPLICIT_TRUST_THRESHOLD
    }

    /** Whether the user saved this deliberately — exactly what decides if a second swipe-up can
     * un-learn it, so a casually-typed word is never removable that way. */
    fun isExplicit(word: String): Boolean = byWord[word.lowercase()]?.explicit == true

    /** Interpolates the personal estimate over the corpus one for a word already resolved to a
     * vocabulary id — the form the correction hot loop uses. */
    fun adjustById(vocabularyId: Int, staticLogProbability: Float): Float {
        if (byVocabularyId.isEmpty()) return staticLogProbability
        val entry = byVocabularyId[vocabularyId] ?: return staticLogProbability
        return interpolate(entry, staticLogProbability)
    }

    fun adjust(word: String, staticLogProbability: Float): Float {
        if (byWord.isEmpty()) return staticLogProbability
        val entry = byWord[word.lowercase()] ?: return staticLogProbability
        return interpolate(entry, staticLogProbability)
    }

    /**
     * `log((1 - α)·P_corpus + α·P_personal)`.
     *
     * Genuine linear interpolation rather than an additive bonus in log space: a bonus would scale
     * with nothing and would have to be capped by hand, whereas mixing probabilities keeps the
     * result a probability and makes α mean something — the share of belief assigned to "this user
     * types like this" over "English looks like this".
     *
     * The `exp`/`log` here only runs for words the user has actually typed, which is a set of a few
     * hundred at most, so it never appears in the general candidate loop's cost.
     */
    private fun interpolate(entry: Entry, staticLogProbability: Float): Float {
        if (totalCount <= 0f) return staticLogProbability
        val personal = entry.decayedCount(clock()) / totalCount
        val corpus = exp(staticLogProbability.toDouble())
        val mixed = (1.0 - PERSONAL_WEIGHT) * corpus + PERSONAL_WEIGHT * personal
        return if (mixed <= 0.0) staticLogProbability else ln(mixed).toFloat()
    }

    /** Learned words starting with [prefix], most-used first — the completion path, and the only
     * way words outside the bundled vocabulary can be suggested at all. An empty prefix matches
     * nothing: with no evidence the user is reaching for one of their own words, promoting them
     * over the language model's actual prediction would be noise. */
    fun matching(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty() || limit <= 0 || byWord.isEmpty()) return emptyList()
        val now = clock()
        return byWord.values.asSequence()
            .filter { it.word.startsWith(prefix) && it.word != prefix }
            .sortedByDescending { it.decayedCount(now) }
            .take(limit)
            .map { it.word }
            .toList()
    }

    /** Everything currently held, for persistence and for the Settings "Learned words" screen. */
    fun entries(): List<Entry> = byWord.values.toList()

    private fun isWorthLearning(word: String): Boolean {
        val languageModel = vocabulary ?: return true
        val id = languageModel.indexOf(word)
        if (id == LanguageModel.NO_WORD) return true // outside the corpus: exactly what to learn
        return languageModel.unigramLogProbability(id) < commonWordFloor(languageModel)
    }

    private var cachedFloor: Float? = null

    private fun commonWordFloor(languageModel: LanguageModel): Float = cachedFloor ?: run {
        val sorted = FloatArray(languageModel.vocabularySize) { languageModel.unigramLogProbability(it) }
        sorted.sort()
        val floor = sorted.getOrElse(sorted.size - 1 - IMPLICIT_LEARNING_RANK) { Float.NEGATIVE_INFINITY }
        cachedFloor = floor
        floor
    }

    private fun index(entry: Entry) {
        byWord[entry.word] = entry
        vocabulary?.indexOf(entry.word)?.takeIf { it != LanguageModel.NO_WORD }
            ?.let { byVocabularyId[it] = entry }
    }

    private fun recomputeTotal() {
        val now = clock()
        totalCount = byWord.values.fold(0f) { sum, entry -> sum + entry.decayedCount(now) }
    }

    /** Keeps the model bounded, dropping whatever has decayed furthest. Explicit saves are evicted
     * last: the user asked for those specifically. */
    private fun evictIfOverCapacity() {
        if (byWord.size <= MAX_ENTRIES) return
        val now = clock()
        val doomed = byWord.values
            .sortedWith(compareBy({ it.explicit }, { it.decayedCount(now) }))
            .take(byWord.size - MAX_ENTRIES)
        doomed.forEach { forget(it.word) }
    }

    private fun Entry.decayedCount(now: Long): Float {
        val elapsedDays = (now - lastUsed).coerceAtLeast(0L).toDouble() / MILLIS_PER_DAY
        return (count * 0.5.pow(elapsedDays / HALF_LIFE_DAYS)).toFloat()
    }

    companion object {
        /** Uses needed before implicit learning grants correction immunity. A typo is a slip and
         * slips do not reliably repeat; a name does. */
        const val IMPLICIT_TRUST_THRESHOLD = 3f

        /** An explicit save counts for this many ordinary uses, so a deliberately saved word ranks
         * usefully straight away rather than after being typed several more times. */
        const val EXPLICIT_SAVE_WEIGHT = 4f

        /** Share of belief given to the personal model when a word is present in both. */
        const val PERSONAL_WEIGHT = 0.35

        /** Corpus words more common than this rank are never learned implicitly — they are already
         * ranked correctly and would only consume the capacity budget. */
        const val IMPLICIT_LEARNING_RANK = 3_000

        const val MAX_ENTRIES = 2_000
        const val HALF_LIFE_DAYS = 45.0
        private const val MILLIS_PER_DAY = 86_400_000.0
    }
}
