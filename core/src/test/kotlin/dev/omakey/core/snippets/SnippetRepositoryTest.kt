package dev.omakey.core.snippets

import dev.omakey.core.db.SnippetDao
import dev.omakey.core.db.SnippetEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetRepositoryTest {

    private class FakeDao : SnippetDao {
        val rows = MutableStateFlow<List<SnippetEntity>>(emptyList())
        private var nextId = 1L
        override fun observeAll(): Flow<List<SnippetEntity>> = rows
        override suspend fun all(): List<SnippetEntity> = rows.value.sortedBy { it.position }
        override suspend fun findById(id: Long): SnippetEntity? = rows.value.firstOrNull { it.id == id }
        override suspend fun insert(e: SnippetEntity): Long {
            val id = nextId++
            rows.value = (rows.value + e.copy(id = id)).sortedBy { it.position }
            return id
        }
        override suspend fun update(e: SnippetEntity) {
            rows.value = rows.value.map { if (it.id == e.id) e else it }.sortedBy { it.position }
        }
        override suspend fun delete(id: Long) {
            rows.value = rows.value.filterNot { it.id == id }
        }
        var insertAllCalls = 0
        override suspend fun insertAll(list: List<SnippetEntity>) {
            insertAllCalls++
            list.forEach { insert(it) }
        }
        override suspend fun updateAll(list: List<SnippetEntity>) {
            val byId = list.associateBy { it.id }
            rows.value = rows.value.map { byId[it.id] ?: it }.sortedBy { it.position }
        }
    }

    /** Reversible: each byte XOR 0x5A, IV fixed. [fail] makes every call fail, as a lost key would. */
    private class FakeCipher : SecretCipher {
        var fail = false
        override fun encrypt(plain: String): SecretCipher.Sealed? =
            if (fail) null else SecretCipher.Sealed(byteArrayOf(1, 2, 3), plain.toByteArray().map { (it.toInt() xor 0x5A).toByte() }.toByteArray())
        override fun decrypt(sealed: SecretCipher.Sealed): String? =
            if (fail) null else String(sealed.bytes.map { (it.toInt() xor 0x5A).toByte() }.toByteArray())
    }

    private val dao = FakeDao()
    private val cipher = FakeCipher()
    private var wipes = 0
    private val repo = SnippetRepository(dao, cipher, clock = { 1000L }, wipeFreedSpace = { wipes++ })

    @Test
    fun `a plain snippet round-trips`() = runBlocking {
        assertTrue(repo.save(null, "Email", "bibek@example.com", secret = false))
        val s = repo.snippets.first().single()
        assertEquals(Snippet(s.id, "Email", "bibek@example.com", secret = false, position = 0), s)
        assertEquals("bibek@example.com", repo.textToType(s.id))
    }

    @Test
    fun `a secret is stored without its plain text`() = runBlocking {
        assertTrue(repo.save(null, "Bank", "hunter2!X", secret = true))
        val row = dao.rows.value.single()
        assertNull(row.text)
        assertNotNull(row.secretCipher)
        assertFalse(String(row.secretCipher!!).contains("hunter2"))
        assertNull(repo.snippets.first().single().text)
        assertEquals("hunter2!X", repo.textToType(row.id))
    }

    @Test
    fun `nothing is stored when encryption fails`() = runBlocking {
        cipher.fail = true
        assertFalse(repo.save(null, "Bank", "hunter2!X", secret = true))
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `a secret that can't be decrypted types nothing`() = runBlocking {
        repo.save(null, "Bank", "hunter2!X", secret = true)
        cipher.fail = true
        assertNull(repo.textToType(dao.rows.value.single().id))
    }

    @Test
    fun `turning a secret plain clears the encrypted columns`() = runBlocking {
        repo.save(null, "Bank", "hunter2!X", secret = true)
        val id = dao.rows.value.single().id
        repo.save(id, "Bank", "now plain", secret = false)
        val row = dao.rows.value.single()
        assertEquals("now plain", row.text)
        assertNull(row.secretCipher)
        assertNull(row.secretIv)
    }

    @Test
    fun `new snippets go to the end and deleting renumbers`() = runBlocking {
        repo.save(null, "A", "a", false); repo.save(null, "B", "b", false); repo.save(null, "C", "c", false)
        assertEquals(listOf(0, 1, 2), dao.rows.value.map { it.position })
        repo.delete(dao.rows.value.first { it.name == "B" }.id)
        assertEquals(listOf("A" to 0, "C" to 1), dao.rows.value.map { it.name to it.position })
    }

    @Test
    fun `moving swaps with a neighbour and stops at the ends`() = runBlocking {
        repo.save(null, "A", "a", false); repo.save(null, "B", "b", false)
        val a = dao.rows.value.first { it.name == "A" }.id
        repo.move(a, -1)
        assertEquals(listOf("A", "B"), repo.snippets.first().map { it.name })
        repo.move(a, +1)
        assertEquals(listOf("B", "A"), repo.snippets.first().map { it.name })
        assertEquals(listOf(0, 1), repo.snippets.first().map { it.position })
    }

    @Test
    fun `a plain snippet keeps its short code and a secret drops it`() = runBlocking {
        repo.save(null, "Email", "bibek@example.com", secret = false, shortcut = "@@")
        val id = dao.rows.value.single().id
        assertEquals("@@", repo.snippets.first().single().shortcut)
        repo.save(id, "Email", "hunter2!X", secret = true, shortcut = "@@")
        assertNull(dao.rows.value.single().shortcut)
    }

    @Test
    fun `an import is written in one go after the existing snippets`() = runBlocking {
        repo.save(null, "A", "a", false)
        repo.importAll(listOf(SnippetTransfer.Entry("B", "b", "bb"), SnippetTransfer.Entry("C", "c", null)))
        assertEquals(1, dao.insertAllCalls)
        assertEquals(listOf("A" to 0, "B" to 1, "C" to 2), dao.rows.value.map { it.name to it.position })
        assertEquals("bb", dao.rows.value.first { it.name == "B" }.shortcut)
    }

    @Test
    fun `turning plain text into a password wipes the space its old text used`() = runBlocking {
        repo.save(null, "Bank", "hunter2!X", secret = false)
        assertEquals(0, wipes)
        repo.save(dao.rows.value.single().id, "Bank", "hunter2!X", secret = true)
        assertEquals(1, wipes)
    }

    @Test
    fun `deleting a snippet wipes the space it used`() = runBlocking {
        repo.save(null, "A", "a", false)
        repo.delete(dao.rows.value.single().id)
        assertEquals(1, wipes)
    }

    @Test
    fun `entity equality compares the encrypted bytes`() {
        val one = SnippetEntity(1, "n", null, byteArrayOf(1), byteArrayOf(2), null, 0, 0, 0)
        assertEquals(one, one.copy(secretCipher = byteArrayOf(1)))
        assertArrayEquals(byteArrayOf(1), one.secretCipher)
        assertFalse(one.toString().contains("[B@"))
    }
}
