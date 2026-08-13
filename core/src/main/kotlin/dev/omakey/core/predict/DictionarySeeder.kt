package dev.omakey.core.predict

import android.content.Context
import dev.omakey.core.db.BigramDao
import dev.omakey.core.db.BigramEntity
import dev.omakey.core.db.WordDao
import dev.omakey.core.db.WordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Imports the bundled frequency-ranked wordlist and bigram (previous-word -> next-word) corpus
 * into Room on first run. Runs off the main thread and is safe to call every onCreate — both
 * seed steps no-op once *fully* seeded, so this never blocks onCreateInputView and the keyboard
 * is typeable immediately; suggestions simply stay basic until this finishes the first time the
 * app ever runs.
 *
 * Before this, the bigrams table only ever got populated from the user's own accepted words
 * (recordAcceptedWord) — a fresh install had zero next-word prediction until you'd typed enough
 * for the app to learn your own patterns. Seeding a real bigram corpus (derived from Google Books
 * Ngrams via Peter Norvig's public frequency lists, filtered to clean alphabetic pairs) gives
 * every install working next-word prediction from the first keystroke.
 *
 * **Resumability**: completion is tracked in SharedPreferences as the bundled asset's line count
 * at the time seeding last fully finished, not with a plain boolean and not with
 * `wordDao.count() > 0` — the table being non-empty doesn't mean seeding *finished*. An
 * `InputMethodService` can be torn down by the OS mid-seed (it's a lightweight, frequently
 * killed/recreated component, not a foreground Activity), which used to leave the table with only
 * however many of the ~120 word batches / ~240 bigram batches had committed before the process
 * died — and since that partial count was still `> 0`, every later launch skipped seeding
 * forever, permanently stuck with a tiny fraction of the real dictionary. `insertSeedBatch` uses
 * `OnConflictStrategy.IGNORE` against each table's primary key, so re-running the full seed loop
 * on top of leftover partial data is safe and idempotent — already-present rows are silently
 * skipped, missing ones get filled in — which is what makes "just retry from the top until the
 * stored count actually reaches the bundled total" a correct resume strategy rather than needing
 * real checkpointing. Storing the count rather than a boolean also means an app update that grows
 * the bundled wordlist/bigram file (new words added) reseeds automatically on already-installed
 * devices instead of staying frozen at whatever shipped on first install.
 */
class DictionarySeeder(private val wordDao: WordDao, private val bigramDao: BigramDao) {

    suspend fun seedIfNeeded(context: Context, assetFileName: String = "wordlist_en_us.txt") {
        withContext(Dispatchers.IO) {
            val prefs = seedStatePrefs(context)
            val lines = context.assets.open(assetFileName).use { stream ->
                BufferedReader(InputStreamReader(stream)).readLines()
            }
            val total = lines.size
            // Gated on the bundled list's line count, not a plain boolean: an app update that
            // adds new words to wordlist_en_us.txt (e.g. "autocorrect", "emoji") needs already-
            // seeded installs to pick them up too, not just fresh installs. Safe to just rerun the
            // whole loop when the count grows — insertSeedBatch's OnConflictStrategy.IGNORE means
            // already-present words (and any frequency/rank drift among them) are left untouched,
            // only genuinely new words get inserted.
            if (prefs.getInt(KEY_WORDS_SEEDED_COUNT, 0) >= total) return@withContext

            val now = System.currentTimeMillis()

            lines.chunked(BATCH_SIZE).forEachIndexed { batchIndex, batch ->
                val entities = batch.mapIndexed { indexInBatch, word ->
                    val globalIndex = batchIndex * BATCH_SIZE + indexInBatch
                    WordEntity(
                        word = word.trim().lowercase(),
                        frequency = total - globalIndex,
                        isUserAdded = false,
                        lastUsedTimestamp = now,
                    )
                }.filter { it.word.isNotEmpty() }
                wordDao.insertSeedBatch(entities)
            }
            // Only recorded once every batch above has actually committed — if the process dies
            // partway through the loop, this line never runs, so the next launch retries the
            // whole thing (safely, per the class doc) instead of being stuck at a partial table.
            prefs.edit().putInt(KEY_WORDS_SEEDED_COUNT, total).apply()
        }
    }

    suspend fun seedBigramsIfNeeded(context: Context, assetFileName: String = "bigrams_en_us.txt") {
        withContext(Dispatchers.IO) {
            val prefs = seedStatePrefs(context)
            val lines = context.assets.open(assetFileName).use { stream ->
                BufferedReader(InputStreamReader(stream)).readLines()
            }
            val total = lines.size
            if (prefs.getInt(KEY_BIGRAMS_SEEDED_COUNT, 0) >= total) return@withContext

            // Frequency counts from the source corpus range into the billions for common pairs
            // ("of the") — far past Int range for BigramEntity.count. Only relative order matters
            // for ranking (ORDER BY count DESC), so rank position is used as the count instead of
            // the raw source frequency, same scheme as the word-frequency seeding above.
            lines.chunked(BATCH_SIZE).forEachIndexed { batchIndex, batch ->
                val entities = batch.mapIndexedNotNull { indexInBatch, line ->
                    val globalIndex = batchIndex * BATCH_SIZE + indexInBatch
                    val spaceIndex = line.indexOf(' ')
                    if (spaceIndex <= 0 || spaceIndex == line.length - 1) return@mapIndexedNotNull null
                    BigramEntity(
                        previousWord = line.substring(0, spaceIndex).trim().lowercase(),
                        word = line.substring(spaceIndex + 1).trim().lowercase(),
                        count = total - globalIndex,
                    )
                }
                bigramDao.insertSeedBatch(entities)
            }
            prefs.edit().putInt(KEY_BIGRAMS_SEEDED_COUNT, total).apply()
        }
    }

    private fun seedStatePrefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val BATCH_SIZE = 500
        private const val PREFS_NAME = "omakey_seed_state"
        private const val KEY_WORDS_SEEDED_COUNT = "words_seeded_count"
        private const val KEY_BIGRAMS_SEEDED_COUNT = "bigrams_seeded_count"
    }
}
