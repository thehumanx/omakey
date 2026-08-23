package dev.omakey.core.predict

import android.content.Context
import dev.omakey.core.db.BigramDao
import dev.omakey.core.db.WordDao
import dev.omakey.core.language.LanguageDefinition

/**
 * Owns one [AutocorrectIndex] + [PredictionEngine] pair per *enabled* language, built lazily on
 * first use and cached for the process lifetime — swapping the active language (see
 * KeyboardViewModel) looks an already-built pair up instead of reseeding/reloading the whole
 * dictionary from Room every time the user switches back and forth.
 */
class LanguageDictionaries(
    private val context: Context,
    private val wordDao: WordDao,
    private val bigramDao: BigramDao,
) {
    private val seeder = DictionarySeeder(wordDao, bigramDao)
    private val cache = mutableMapOf<String, Pair<AutocorrectIndex, PredictionEngine>>()

    /** Synchronous, unloaded pair for [language] — a valid [AutocorrectIndex] (just empty until
     * something loads it) and a fully-functional [PredictionEngine] (queries Room directly, so it
     * works correctly even before seeding finishes, just returns nothing yet). Used for the
     * instant a language becomes active, before [forLanguage]'s seed-and-load has had a chance to
     * run — matches how the single English instance used to be constructed empty and filled in
     * moments later (see [dev.omakey.app.keyboard.OmakeyInputMethodService]'s doc on
     * `onCreateInputView` not blocking on seeding). */
    fun placeholderFor(language: LanguageDefinition): Pair<AutocorrectIndex, PredictionEngine> =
        AutocorrectIndex() to FrequencyNgramPredictionEngine(wordDao, bigramDao, language.id)

    /** Seeds [language]'s bundled dictionary/bigram corpus if it has one and hasn't been seeded
     * yet (see [DictionarySeeder] — a no-op after the first successful run, and skipped entirely
     * for a language with no bundled asset, e.g. Nepali today), then loads its full word list into
     * an in-memory [AutocorrectIndex]. Cached after the first call so switching back to an
     * already-resolved language is instant. */
    suspend fun forLanguage(language: LanguageDefinition): Pair<AutocorrectIndex, PredictionEngine> {
        cache[language.id]?.let { return it }
        language.dictionaryAsset?.let { seeder.seedIfNeeded(context, language.id, it) }
        language.bigramAsset?.let { seeder.seedBigramsIfNeeded(context, language.id, it) }
        val index = AutocorrectIndex().apply { load(wordDao.all(language.id)) }
        val engine = FrequencyNgramPredictionEngine(wordDao, bigramDao, language.id)
        val pair = index to engine
        cache[language.id] = pair
        return pair
    }
}
