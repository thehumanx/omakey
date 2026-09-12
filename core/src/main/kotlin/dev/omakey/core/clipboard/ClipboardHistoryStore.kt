package dev.omakey.core.clipboard

import android.content.Context
import dev.omakey.core.db.ClipboardDao
import java.io.File
import java.io.InputStream

/**
 * Clipboard history as one thing: the `clipboard_history` rows **and** the image files they point
 * at.
 *
 * Those two halves have to move together and previously didn't. `trimUnpinned` is raw SQL and
 * cannot touch the filesystem, so deleting rows anywhere except the single-item delete path left
 * orphaned PNGs behind forever. Now that Settings can clear history too, that would have been a
 * second place to get it wrong — hence one owner rather than the same reconciliation written twice.
 *
 * Every file operation is best-effort: a PNG that won't delete is not worth failing a clipboard
 * capture or a "clear history" tap over.
 */
class ClipboardHistoryStore(context: Context, private val dao: ClipboardDao) {

    private val imagesDir = File(context.applicationContext.filesDir, IMAGES_DIR)

    /**
     * Copies [stream] into app-private storage and returns the path, or null if it was too large or
     * unreadable.
     *
     * The bytes are copied at the moment of capture rather than the clip's `content://` URI being
     * stored, because that URI grant is only guaranteed valid right now, not whenever the user next
     * opens the panel.
     *
     * Capped because the source is an arbitrary stream from another app, of unknown length, being
     * written somewhere the user never sees. An oversized clip is dropped whole — the partial file
     * is deleted and null returned — so no half-written PNG is ever referenced by a row.
     */
    fun saveImage(stream: InputStream): String? = runCatching {
        imagesDir.mkdirs()
        val file = File(imagesDir, "clip_${System.currentTimeMillis()}.png")
        val copied = stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        if (copied > MAX_IMAGE_BYTES) {
            file.delete()
            null
        } else {
            file.absolutePath
        }
    }.getOrNull()

    /** Trims to the newest [KEEP] unpinned entries, then reconciles the image directory against
     * what survived. Pinned entries are exempt — see [ClipboardDao.trimUnpinned]. */
    suspend fun trim() {
        dao.trimUnpinned(KEEP)
        pruneOrphanedImages()
    }

    suspend fun delete(id: Long) {
        dao.findById(id)?.imagePath?.let { path -> runCatching { File(path).delete() } }
        dao.delete(id)
    }

    /** Clears everything, pinned included — this backs an explicit "delete all" the user confirmed,
     * where exempting pinned items would leave the history non-empty after being told it was
     * cleared. */
    suspend fun clearAll() {
        dao.deleteAll()
        runCatching { imagesDir.listFiles()?.forEach { it.delete() } }
    }

    /**
     * Deletes image files no surviving row references.
     *
     * Reconciling strays rather than deleting per-removed-row is deliberate: it is self-healing, so
     * files already orphaned by earlier versions (which had no cleanup on the trim path at all) get
     * collected on the next capture instead of leaking permanently.
     */
    private suspend fun pruneOrphanedImages() {
        runCatching {
            val referenced = dao.referencedImagePaths().toHashSet()
            imagesDir.listFiles()?.forEach { file ->
                if (file.absolutePath !in referenced) file.delete()
            }
        }
    }

    private companion object {
        const val IMAGES_DIR = "clipboard_images"
        const val KEEP = 50

        /** Generous enough for any screenshot or photo a user would plausibly paste, small enough
         * that [KEEP] of them is a bounded amount of storage rather than an open-ended one. */
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
    }
}
