package dev.omakey.core.translit

import dev.omakey.core.predict.lm.LanguageModel
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Every vocabulary word, keyed by the [TransliterationScheme.skeleton] of its romanization and sorted by that
 * key, so the words a Latin spelling could mean are one binary search away (AGENTS.md §66 Phase 9).
 *
 * Built on the phone from the installed model, not shipped in the pack: all romanization logic then
 * lives in one place (Kotlin, the [TransliterationScheme]), where the ranking also uses it — a builder-side copy in Python would
 * be a second implementation to keep in step. Building takes a second or two, once, on first use;
 * the result is written next to the model and memory-mapped from then on, like the model itself.
 *
 * File layout (little-endian): "OMTI", version, count, blob bytes; u32 key offsets[count + 1];
 * ASCII key blob (4-byte padded); u32 word ids[count]. Within one key, ids are in descending
 * probability order.
 */
class TransliterationIndex private constructor(
    private val offsets: ByteBuffer,
    private val blob: ByteBuffer,
    private val ids: ByteBuffer,
    val size: Int,
) {

    fun wordId(entry: Int): Int = ids.getInt(entry * 4)

    /** Entries whose key is exactly [key]. */
    fun exactRange(key: String): IntRange {
        val first = lowerBound(key, prefix = false)
        var end = first
        while (end < size && compare(key, end, prefix = false) == 0) end++
        return first until end
    }

    /** Entries whose key starts with [prefix]. */
    fun prefixRange(prefix: String): IntRange {
        if (prefix.isEmpty()) return 0 until size
        val first = lowerBound(prefix, prefix = true)
        var low = first
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compare(prefix, mid, prefix = true) == 0) low = mid + 1 else high = mid
        }
        return first until low
    }

    private fun lowerBound(key: String, prefix: Boolean): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (compare(key, mid, prefix) > 0) low = mid + 1 else high = mid
        }
        return low
    }

    /** [key] against entry [entry]'s key; with [prefix], an entry that merely starts with [key]
     * compares equal. */
    private fun compare(key: String, entry: Int, prefix: Boolean): Int {
        val start = offsets.getInt(entry * 4)
        val length = offsets.getInt((entry + 1) * 4) - start
        val shared = minOf(key.length, length)
        for (i in 0 until shared) {
            val difference = key[i].code - (blob.get(start + i).toInt() and 0xFF)
            if (difference != 0) return difference
        }
        return if (prefix && key.length <= length) 0 else key.length - length
    }

    companion object {
        private val MAGIC = byteArrayOf('O'.code.toByte(), 'M'.code.toByte(), 'T'.code.toByte(), 'I'.code.toByte())
        private const val VERSION = 1

        /** Opens [file], building it from [model] first if it is missing or out of date. */
        fun openOrBuild(file: File, model: LanguageModel, scheme: TransliterationScheme): TransliterationIndex {
            val existing = runCatching { open(file) }.getOrNull()
            if (existing != null && existing.size == model.vocabularySize) return existing
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeBytes(build(model, scheme))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
            return open(file)
        }

        fun open(file: File): TransliterationIndex =
            RandomAccessFile(file, "r").use { from(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())) }

        /** Builds the index in memory; [openOrBuild] writes it to disk. */
        fun build(model: LanguageModel, scheme: TransliterationScheme): ByteArray {
            val count = model.vocabularySize
            val keys = arrayOfNulls<String>(count)
            for (id in 0 until count) keys[id] = scheme.skeleton(scheme.romanize(model.wordAt(id)))
            val order = (0 until count).sortedWith(
                compareBy<Int> { keys[it] }.thenByDescending { model.unigramLogProbability(it) },
            )
            val blob = java.io.ByteArrayOutputStream()
            val offsets = IntArray(count + 1)
            order.forEachIndexed { position, id ->
                offsets[position] = blob.size()
                blob.write(keys[id]!!.toByteArray(Charsets.US_ASCII))
            }
            offsets[count] = blob.size()
            while (blob.size() % 4 != 0) blob.write(0)
            val blobBytes = blob.toByteArray()
            val buffer = ByteBuffer.allocate(16 + (count + 1) * 4 + blobBytes.size + count * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(MAGIC).putInt(VERSION).putInt(count).putInt(blobBytes.size)
            offsets.forEach { buffer.putInt(it) }
            buffer.put(blobBytes)
            order.forEach { buffer.putInt(it) }
            return buffer.array()
        }

        fun from(source: ByteBuffer): TransliterationIndex {
            val buffer = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            for (i in MAGIC.indices) require(buffer.get(i) == MAGIC[i]) { "not a transliteration index" }
            require(buffer.getInt(4) == VERSION) { "transliteration index version ${buffer.getInt(4)}" }
            val count = buffer.getInt(8)
            val blobBytes = buffer.getInt(12)
            fun slice(offset: Int, length: Int): ByteBuffer {
                val s = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                s.position(offset).limit(offset + length)
                return s.slice().order(ByteOrder.LITTLE_ENDIAN)
            }
            val offsetsStart = 16
            val blobStart = offsetsStart + (count + 1) * 4
            val idsStart = blobStart + blobBytes
            return TransliterationIndex(slice(offsetsStart, (count + 1) * 4), slice(blobStart, blobBytes), slice(idsStart, count * 4), count)
        }
    }
}
