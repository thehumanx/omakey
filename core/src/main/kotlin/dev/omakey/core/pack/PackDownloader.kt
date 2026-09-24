package dev.omakey.core.pack

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches the pack index and pack zips from the `packs` release on GitHub. Called only on an
 * explicit user action in Settings — never in the background (AGENTS.md §14, §66 Phase 7).
 *
 * Plain `HttpURLConnection`, same as `UpdateChecker`: one more call site doesn't justify an HTTP
 * client dependency. Downloads are streamed to disk with a byte cap taken from the signed index and
 * hashed as they arrive, so a pack is never held in memory and an oversized response is cut off
 * rather than filling storage.
 */
class PackDownloader(private val baseUrl: String = DEFAULT_BASE_URL) {

    init {
        require(baseUrl.startsWith("https://")) { "pack downloads must use HTTPS" }
    }

    fun fetchIndex(): PackIndex {
        val index = get("index.json", MAX_INDEX_BYTES)
        val signature = get("index.json.sig", MAX_SIGNATURE_BYTES)
        return PackIndexVerifier.verify(index, signature)
    }

    /**
     * Downloads [entry] to [destination], reporting progress as a fraction, and returns once its
     * SHA-256 matches the index. The installer checks the hash again; this check exists so a bad
     * download fails here, with a clear message, rather than as an install error.
     */
    fun download(entry: PackIndexEntry, destination: File, onProgress: (Float) -> Unit = {}) {
        val connection = open(entry.file)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                destination.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        received += read
                        if (received > entry.sizeBytes) throw IOException("download larger than the index says")
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        onProgress(received.toFloat() / entry.sizeBytes)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(entry.sha256, ignoreCase = true)) {
                destination.delete()
                throw InvalidPackException("downloaded ${entry.file} does not match its checksum")
            }
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun get(name: String, limit: Int): ByteArray {
        val connection = open(name)
        try {
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > limit) throw IOException("$name is larger than expected")
                }
                out.toByteArray()
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun open(name: String): HttpURLConnection {
        val connection = URL(baseUrl + name).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        // GitHub release downloads redirect to its asset CDN; HttpURLConnection follows HTTPS→HTTPS
        // redirects and refuses a downgrade to HTTP, which is the behaviour wanted.
        connection.instanceFollowRedirects = true
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            val code = connection.responseCode
            connection.disconnect()
            throw IOException("HTTP $code for $name")
        }
        return connection
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://github.com/thehumanx/omakey/releases/download/packs/"
        private const val TIMEOUT_MS = 20_000
        private const val MAX_INDEX_BYTES = 256 * 1024
        private const val MAX_SIGNATURE_BYTES = 1024
    }
}
