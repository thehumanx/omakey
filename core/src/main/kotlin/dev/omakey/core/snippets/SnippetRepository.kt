package dev.omakey.core.snippets

import dev.omakey.core.db.SnippetDao
import dev.omakey.core.db.SnippetEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The user's snippets, in their order. Secrets are sealed by [cipher] on save and opened only in
 * [textToType], at the moment one is typed or shown; [snippets] never carries a secret's text.
 */
class SnippetRepository(
    private val dao: SnippetDao,
    private val cipher: SecretCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Folds the write-ahead log into the database and empties it. With SQLite's secure_delete on
     * (OmakeyDatabase), freed pages are zeroed, so after this a plain text that was replaced (made
     * a password) or deleted is gone from the files on disk, not just from the table.
     */
    private val wipeFreedSpace: suspend () -> Unit = {},
) {

    /** Every write reads the rows and then writes them, so writes run one at a time: a double
     * tap on Save made two snippets share one position. Shared per database, since Settings
     * and the keyboard can each hold their own repository. */
    private val writes: Mutex = synchronized(WRITE_LOCKS) { WRITE_LOCKS.getOrPut(dao) { Mutex() } }

    val snippets: Flow<List<Snippet>> = dao.observeAll().map { rows -> rows.map { it.toSnippet() } }

    /**
     * Saves a new snippet ([id] null, added at the end) or replaces one. False when a secret
     * couldn't be sealed, in which case nothing is written. A secret never keeps a [shortcut]:
     * codes are offered in the suggestion bar, which a secret must never be.
     */
    suspend fun save(id: Long?, name: String, text: String, secret: Boolean, shortcut: String? = null): Boolean = writes.withLock {
        val sealed = if (secret) cipher.encrypt(text) ?: return@withLock false else null
        val now = clock()
        val existing = id?.let { dao.findById(it) }
        val row = SnippetEntity(
            id = existing?.id ?: 0,
            name = name.trim(),
            text = if (secret) null else text,
            secretCipher = sealed?.bytes,
            secretIv = sealed?.iv,
            shortcut = if (secret) null else shortcut?.trim()?.ifEmpty { null },
            position = existing?.position ?: dao.all().size,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        if (existing == null) dao.insert(row) else dao.update(row)
        // A plain text that just became a password: its old text must not linger in freed pages.
        if (secret && existing != null && existing.text != null) wipeFreedSpace()
        true
    }

    /**
     * Adds imported snippets (plain, from [SnippetTransfer.plan]) after the existing ones, in one
     * write under the lock. Saving them one by one left a half-done import when one failed or the
     * screen was closed midway, and reloaded the whole list for each.
     */
    suspend fun importAll(entries: List<SnippetTransfer.Entry>) = writes.withLock {
        if (entries.isEmpty()) return@withLock
        val now = clock()
        val start = dao.all().size
        dao.insertAll(
            entries.mapIndexed { i, e ->
                SnippetEntity(
                    name = e.name.trim().take(MAX_NAME),
                    text = e.text,
                    secretCipher = null,
                    secretIv = null,
                    shortcut = e.shortcut,
                    position = start + i,
                    createdAt = now,
                    updatedAt = now,
                )
            },
        )
    }

    /** The text to type for snippet [id]: plain text, or a secret opened just now. Null when it
     * is gone or can't be opened. */
    suspend fun textToType(id: Long): String? {
        val row = dao.findById(id) ?: return null
        val bytes = row.secretCipher ?: return row.text
        val iv = row.secretIv ?: return null
        return cipher.decrypt(SecretCipher.Sealed(iv, bytes))
    }

    suspend fun delete(id: Long) = writes.withLock {
        dao.delete(id)
        renumber(dao.all())
        wipeFreedSpace()
    }

    /** Moves snippet [id] [by] places (−1 up, +1 down), stopping at either end. */
    suspend fun move(id: Long, by: Int) = writes.withLock {
        val rows = dao.all().toMutableList()
        val from = rows.indexOfFirst { it.id == id }
        if (from < 0) return@withLock
        val to = (from + by).coerceIn(0, rows.lastIndex)
        if (to == from) return@withLock
        rows.add(to, rows.removeAt(from))
        renumber(rows)
    }

    private suspend fun renumber(rows: List<SnippetEntity>) {
        val changed = rows.mapIndexedNotNull { index, row -> if (row.position != index) row.copy(position = index) else null }
        if (changed.isNotEmpty()) dao.updateAll(changed)
    }

    private fun SnippetEntity.toSnippet() =
        Snippet(
            id, name,
            text = if (secretCipher != null) null else text,
            secret = secretCipher != null,
            position = position,
            shortcut = if (secretCipher != null) null else shortcut,
        )

    private companion object {
        const val MAX_NAME = 100

        // Keyed by DAO: Room hands out one DAO instance per database, so the IME's repository
        // and the snippet screen's share a lock.
        val WRITE_LOCKS = java.util.WeakHashMap<SnippetDao, Mutex>()
    }
}
