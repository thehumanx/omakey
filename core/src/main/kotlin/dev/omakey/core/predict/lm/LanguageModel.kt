package dev.omakey.core.predict.lm

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The bundled English n-gram model: a memory-mapped view over `assets/lm_en_us.bin`, built by
 * `scripts/build_lm.py`.
 *
 * **Nothing is parsed at load time.** The file is mapped and read through in place, so constructing
 * this costs a few page faults rather than materialising 150k strings and a million boxed counts.
 * That matters more here than in an ordinary app: an `InputMethodService` is a lightweight
 * component the OS creates, kills and recreates freely, so "startup" happens constantly and any
 * heap the model holds is heap the keyboard can be killed for. Mapped pages are clean, file-backed
 * and reclaimable under pressure — the OS can evict them and fault them back in without the model
 * ever noticing.
 *
 * This replaces the previous design, where the same data was inserted row by row into SQLite on
 * first run and then queried — with a `LIKE 'prefix%'` — on *every keystroke*, against a budget
 * where a keypress is expected to produce visible feedback within about 20ms.
 *
 * ## Layout
 *
 * Little-endian, 4-byte aligned, sections in the order the header declares them. Word ids are
 * indices into a lexicographically sorted vocabulary, which is what makes [prefixRange] a pair of
 * binary searches instead of a scan. Log-probabilities are int16 scaled by [LOGP_SCALE]
 * (see the builder for why that precision is ample).
 *
 * Bigrams are stored compressed-sparse-row style keyed by previous-word id; trigram contexts are
 * a sorted `(first, second)` table searched with [trigramRow]. Every continuation row is written
 * in descending probability order, so "the best N continuations" is a prefix of a row rather than
 * a sort at query time.
 */
class LanguageModel private constructor(
    private val blob: ByteBuffer,
    private val wordOffset: ByteBuffer,
    private val unigramLogP: ByteBuffer,
    private val topUnigram: ByteBuffer,
    private val bigramStart: ByteBuffer,
    private val bigramWordIds: ByteBuffer,
    private val bigramLogP: ByteBuffer,
    private val trigramFirst: ByteBuffer,
    private val trigramSecond: ByteBuffer,
    private val trigramStart: ByteBuffer,
    private val trigramWordIds: ByteBuffer,
    private val trigramLogP: ByteBuffer,
    val vocabularySize: Int,
    private val topUnigramCount: Int,
    private val trigramContextCount: Int,
) {

    // --- vocabulary ---------------------------------------------------------------------------

    /** Word id for [word], or [NO_WORD] if it isn't in the vocabulary.
     *
     * Compares UTF-8 bytes directly against the blob rather than decoding candidates into
     * `String`s — this runs several times per keystroke, and allocating ~17 throwaway strings per
     * binary search is exactly the kind of steady garbage an IME cannot afford. Safe because the
     * vocabulary is ASCII (`a`–`z` plus apostrophe), where byte order and code-point order agree.
     */
    fun indexOf(word: CharSequence): Int {
        var low = 0
        var high = vocabularySize - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val comparison = compareToBlob(word, mid)
            when {
                comparison == 0 -> return mid
                comparison < 0 -> high = mid - 1
                else -> low = mid + 1
            }
        }
        return NO_WORD
    }

    fun wordAt(id: Int): String {
        val start = wordOffset.getInt(id * 4)
        val end = wordOffset.getInt((id + 1) * 4)
        val bytes = ByteArray(end - start)
        for (i in bytes.indices) bytes[i] = blob.get(start + i)
        return String(bytes, Charsets.UTF_8)
    }

    fun wordLength(id: Int): Int = wordOffset.getInt((id + 1) * 4) - wordOffset.getInt(id * 4)

    /** Character [index] of word [id] without materialising the word. The edit-distance search
     * touches thousands of candidates per keystroke; going through [wordAt] there would allocate a
     * `String` and a `ByteArray` for every one of them. ASCII-only by construction. */
    fun charAt(id: Int, index: Int): Char =
        (blob.get(wordOffset.getInt(id * 4) + index).toInt() and 0xFF).toChar()

    /** The contiguous id range whose words begin with [prefix] — empty if none do. The vocabulary
     * being lexicographically sorted is precisely what makes prefix completion two binary searches
     * rather than a table scan. */
    fun prefixRange(prefix: CharSequence): IntRange {
        if (prefix.isEmpty()) return 0 until vocabularySize
        val first = lowerBound(prefix)
        if (first >= vocabularySize || !startsWith(first, prefix)) return IntRange.EMPTY
        var low = first
        var high = vocabularySize
        while (low < high) {
            val mid = (low + high) ushr 1
            if (startsWith(mid, prefix)) low = mid + 1 else high = mid
        }
        return first until low
    }

    fun startsWith(id: Int, prefix: CharSequence): Boolean {
        val start = wordOffset.getInt(id * 4)
        if (wordLength(id) < prefix.length) return false
        for (i in prefix.indices) {
            if ((blob.get(start + i).toInt() and 0xFF) != prefix[i].code) return false
        }
        return true
    }

    // --- probabilities ------------------------------------------------------------------------

    fun unigramLogProbability(id: Int): Float =
        if (id == NO_WORD) UNKNOWN_LOG_PROBABILITY else unigramLogP.getShort(id * 2) / LOGP_SCALE

    /**
     * `log P(word | beforePrevious, previous)` under stupid backoff: use the trigram if the
     * context is known, otherwise fall back to the bigram, then to the unigram, charging
     * [BACKOFF_PENALTY] per step down.
     *
     * Stupid backoff rather than a smoothed estimator (Kneser-Ney and friends) because it needs no
     * backoff-weight table — halving the asset — and the difference is immaterial for ranking,
     * which is all this is ever used for. It is not a normalised distribution and shouldn't be
     * treated as one.
     */
    fun logProbability(id: Int, previousId: Int = NO_WORD, beforePreviousId: Int = NO_WORD): Float {
        if (id == NO_WORD) return UNKNOWN_LOG_PROBABILITY

        if (previousId != NO_WORD && beforePreviousId != NO_WORD) {
            val row = trigramRow(beforePreviousId, previousId)
            for (i in row) {
                if (trigramWordIds.getInt(i * 4) == id) return trigramLogP.getShort(i * 2) / LOGP_SCALE
            }
        }
        if (previousId != NO_WORD) {
            val row = bigramRow(previousId)
            for (i in row) {
                if (bigramWordIds.getInt(i * 4) == id) {
                    return bigramLogP.getShort(i * 2) / LOGP_SCALE + BACKOFF_PENALTY
                }
            }
        }
        return unigramLogProbability(id) + BACKOFF_PENALTY * 2
    }

    // --- continuation rows --------------------------------------------------------------------

    /** Indices into [bigramWordId]/[bigramLogProbability] for continuations of [previousId], in
     * descending probability order. */
    fun bigramRow(previousId: Int): IntRange {
        if (previousId == NO_WORD) return IntRange.EMPTY
        return bigramStart.getInt(previousId * 4) until bigramStart.getInt((previousId + 1) * 4)
    }

    fun bigramWordId(rowIndex: Int): Int = bigramWordIds.getInt(rowIndex * 4)

    fun bigramLogProbability(rowIndex: Int): Float = bigramLogP.getShort(rowIndex * 2) / LOGP_SCALE

    /** Indices into [trigramWordId]/[trigramLogProbability] for continuations of the context
     * `(firstId, secondId)`, in descending probability order; empty when the context is unseen. */
    fun trigramRow(firstId: Int, secondId: Int): IntRange {
        if (firstId == NO_WORD || secondId == NO_WORD) return IntRange.EMPTY
        var low = 0
        var high = trigramContextCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midFirst = trigramFirst.getInt(mid * 4)
            val midSecond = trigramSecond.getInt(mid * 4)
            val comparison = when {
                midFirst != firstId -> midFirst - firstId
                else -> midSecond - secondId
            }
            when {
                comparison == 0 ->
                    return trigramStart.getInt(mid * 4) until trigramStart.getInt((mid + 1) * 4)
                comparison > 0 -> high = mid - 1
                else -> low = mid + 1
            }
        }
        return IntRange.EMPTY
    }

    fun trigramWordId(rowIndex: Int): Int = trigramWordIds.getInt(rowIndex * 4)

    fun trigramLogProbability(rowIndex: Int): Float = trigramLogP.getShort(rowIndex * 2) / LOGP_SCALE

    /** The globally most frequent words, most frequent first — the last-resort ranking when there
     * is no context and no prefix to go on. */
    fun topUnigramId(rank: Int): Int = topUnigram.getInt(rank * 4)

    val topUnigramSize: Int get() = topUnigramCount

    // --- internals ----------------------------------------------------------------------------

    private fun compareToBlob(word: CharSequence, id: Int): Int {
        val start = wordOffset.getInt(id * 4)
        val length = wordOffset.getInt((id + 1) * 4) - start
        val shared = minOf(word.length, length)
        for (i in 0 until shared) {
            val difference = word[i].code - (blob.get(start + i).toInt() and 0xFF)
            if (difference != 0) return difference
        }
        return word.length - length
    }

    private fun lowerBound(prefix: CharSequence): Int {
        var low = 0
        var high = vocabularySize
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compareToBlob(prefix, mid) > 0) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        const val NO_WORD = -1

        /** Score for a word outside the vocabulary. Deliberately finite rather than
         * `NEGATIVE_INFINITY`: an unknown word should be heavily penalised but still comparable,
         * since a candidate list of all-unknown words must still come out in a sensible order. */
        const val UNKNOWN_LOG_PROBABILITY = -22f

        /** `ln(0.4)`, the conventional stupid-backoff discount. */
        const val BACKOFF_PENALTY = -0.9163f

        private const val LOGP_SCALE = 1000f
        private const val HEADER_BYTES = 64
        private val MAGIC = byteArrayOf('O'.code.toByte(), 'M'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte())
        private const val FORMAT_VERSION = 1

        fun load(context: Context, assetName: String = "lm_en_us.bin"): LanguageModel =
            from(mapAsset(context, assetName))

        /**
         * Maps the asset without copying it onto the heap. Requires the asset to be stored
         * uncompressed — see `noCompress` in `app/build.gradle.kts`; a compressed asset has no
         * file offset to map and `openFd` throws, which is why the fallback below exists rather
         * than being dead code.
         */
        private fun mapAsset(context: Context, assetName: String): ByteBuffer = try {
            context.assets.openFd(assetName).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    stream.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        descriptor.length,
                    )
                }
            }
        } catch (_: java.io.IOException) {
            context.assets.open(assetName).use { ByteBuffer.wrap(it.readBytes()) }
        }

        fun from(source: ByteBuffer): LanguageModel {
            val buffer = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            for (i in MAGIC.indices) {
                require(buffer.get(i) == MAGIC[i]) { "Not an omakey language model (bad magic)" }
            }
            val version = buffer.getInt(4)
            require(version == FORMAT_VERSION) {
                "Language model format $version, expected $FORMAT_VERSION — rebuild with scripts/build_lm.py"
            }
            val vocabularySize = buffer.getInt(8)
            val blobBytes = buffer.getInt(12)
            val bigramCount = buffer.getInt(16)
            val trigramContextCount = buffer.getInt(20)
            val trigramCount = buffer.getInt(24)
            val topUnigramCount = buffer.getInt(28)

            var offset = HEADER_BYTES
            fun section(bytes: Int): ByteBuffer {
                val slice = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                slice.position(offset).limit(offset + bytes)
                // Padded to 4 bytes so each following section stays aligned — the writer pads
                // identically; the two must agree exactly or every subsequent read is garbage.
                offset += bytes + ((-bytes) and 3)
                return slice.slice().order(ByteOrder.LITTLE_ENDIAN)
            }

            return LanguageModel(
                blob = section(blobBytes),
                wordOffset = section((vocabularySize + 1) * 4),
                unigramLogP = section(vocabularySize * 2),
                topUnigram = section(topUnigramCount * 4),
                bigramStart = section((vocabularySize + 1) * 4),
                bigramWordIds = section(bigramCount * 4),
                bigramLogP = section(bigramCount * 2),
                trigramFirst = section(trigramContextCount * 4),
                trigramSecond = section(trigramContextCount * 4),
                trigramStart = section((trigramContextCount + 1) * 4),
                trigramWordIds = section(trigramCount * 4),
                trigramLogP = section(trigramCount * 2),
                vocabularySize = vocabularySize,
                topUnigramCount = topUnigramCount,
                trigramContextCount = trigramContextCount,
            )
        }
    }
}
