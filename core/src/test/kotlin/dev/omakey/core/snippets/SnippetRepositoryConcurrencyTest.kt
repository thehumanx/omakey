package dev.omakey.core.snippets

import dev.omakey.core.db.SnippetDao
import dev.omakey.core.db.SnippetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A double tap on Save, or quick ↑ taps, run repository calls at the same time. Each one reads the
 * rows and then writes, so without serialising them two saves both took position = count and
 * left two snippets in one slot.
 */
class SnippetRepositoryConcurrencyTest {

    /** Thread-safe, and slow to read, so two unserialised calls reliably interleave. */
    private class SlowDao : SnippetDao {
        private val lock = Any()
        val rows = MutableStateFlow<List<SnippetEntity>>(emptyList())
        private var nextId = 1L
        override fun observeAll(): Flow<List<SnippetEntity>> = rows
        override suspend fun all(): List<SnippetEntity> {
            val snapshot = synchronized(lock) { rows.value.sortedBy { it.position } }
            delay(20)
            return snapshot
        }
        override suspend fun findById(id: Long) = synchronized(lock) { rows.value.firstOrNull { it.id == id } }
        override suspend fun insert(e: SnippetEntity): Long = synchronized(lock) {
            val id = nextId++
            rows.value = rows.value + e.copy(id = id)
            id
        }
        override suspend fun update(e: SnippetEntity) = synchronized(lock) { rows.value = rows.value.map { if (it.id == e.id) e else it } }
        override suspend fun delete(id: Long) = synchronized(lock) { rows.value = rows.value.filterNot { it.id == id } }
        override suspend fun insertAll(list: List<SnippetEntity>) { list.forEach { insert(it) } }
        override suspend fun updateAll(list: List<SnippetEntity>) = synchronized(lock) {
            val byId = list.associateBy { it.id }
            rows.value = rows.value.map { byId[it.id] ?: it }
        }
    }

    private object PlainCipher : SecretCipher {
        override fun encrypt(plain: String) = SecretCipher.Sealed(byteArrayOf(0), plain.toByteArray())
        override fun decrypt(sealed: SecretCipher.Sealed) = String(sealed.bytes)
    }

    @Test
    fun `saves made at the same time each get their own position`() = runBlocking {
        val dao = SlowDao()
        val repo = SnippetRepository(dao, PlainCipher)
        (1..3).map { n -> async(Dispatchers.Default) { repo.save(null, "S$n", "t$n", secret = false) } }.awaitAll()
        assertEquals(listOf(0, 1, 2), dao.rows.value.map { it.position }.sorted())
    }
}
