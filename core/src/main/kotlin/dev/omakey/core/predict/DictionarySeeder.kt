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
 * seed steps no-op once their table is populated, so this never blocks onCreateInputView and the
 * keyboard is typeable immediately; suggestions simply stay basic until this finishes the first
 * time the app ever runs.
 *
 * Before this, the bigrams table only ever got populated from the user's own accepted words
 * (recordAcceptedWord) — a fresh install had zero next-word prediction until you'd typed enough
 * for the app to learn your own patterns. Seeding a real bigram corpus (derived from Google Books
 * Ngrams via Peter Norvig's public frequency lists, filtered to clean alphabetic pairs) gives
 * every install working next-word prediction from the first keystroke.
 */
class DictionarySeeder(private val wordDao: WordDao, private val bigramDao: BigramDao) {

    suspend fun seedIfNeeded(context: Context, assetFileName: String = "wordlist_en_us.txt") {
        withContext(Dispatchers.IO) {
            if (wordDao.count() > 0) return@withContext

            val lines = context.assets.open(assetFileName).use { stream ->
                BufferedReader(InputStreamReader(stream)).readLines()
            }
            val now = System.currentTimeMillis()
            val total = lines.size

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
        }
    }

    suspend fun seedBigramsIfNeeded(context: Context, assetFileName: String = "bigrams_en_us.txt") {
        withContext(Dispatchers.IO) {
            if (bigramDao.count() > 0) return@withContext

            val lines = context.assets.open(assetFileName).use { stream ->
                BufferedReader(InputStreamReader(stream)).readLines()
            }
            val total = lines.size

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
        }
    }

    companion object {
        private const val BATCH_SIZE = 500
    }
}
