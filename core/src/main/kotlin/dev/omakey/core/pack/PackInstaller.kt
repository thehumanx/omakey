package dev.omakey.core.pack

import dev.omakey.core.locale.KeyboardLocale
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Installs, lists and removes language packs under [root] (the app's private `files/languages`),
 * one directory per language, named by its id (AGENTS.md §66 Phase 7).
 *
 * A pack arrives from the network, so nothing about it is trusted until checked:
 *  1. **Integrity** — its SHA-256 must match the one in the signed index ([expectedSha256]).
 *  2. **Extraction** — every entry must land inside the staging directory (no `..`, no absolute
 *     paths: "zip slip"), with caps on entry count and on bytes actually written, counted as they
 *     are written rather than taken from the zip's own headers, which are just more untrusted data.
 *  3. **Content** — [PackLoader.load] must accept the result: manifest, format, layouts, profile,
 *     and the model.
 *
 * Only then does it replace the installed version, by directory rename, so a failure at any step
 * leaves the previous version exactly as it was. The IME may have the old model memory-mapped
 * while this runs; on Linux a mapping survives its file being renamed or deleted, so that is safe.
 */
class PackInstaller(private val root: File, private val appVersionCode: Int = Int.MAX_VALUE) {

    /** Installs the pack in [zip] and returns the language it provides. */
    fun install(zip: File, expectedSha256: String? = null): KeyboardLocale {
        if (expectedSha256 != null) {
            val actual = sha256(zip)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw InvalidPackException("checksum mismatch: expected $expectedSha256, got $actual")
            }
        }
        root.mkdirs()
        val staging = File(root, ".staging-${System.nanoTime()}")
        try {
            zip.inputStream().use { extract(it, staging) }
            val id = PackLoader.load(staging, appVersionCode).id
            val target = File(root, id)
            val previous = File(root, ".previous-$id-${System.nanoTime()}")
            if (target.exists() && !target.renameTo(previous)) throw InvalidPackException("could not replace the installed $id")
            if (!staging.renameTo(target)) {
                previous.renameTo(target)
                throw InvalidPackException("could not move $id into place")
            }
            previous.deleteRecursively()
            // Loaded again from its final location: the model path is absolute.
            return PackLoader.load(target, appVersionCode)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Every installed pack that still loads. One that doesn't (corrupted storage, or a pack format
     * this build no longer reads) is skipped and reported in [failures], never allowed to take the
     * keyboard down. */
    fun installed(failures: MutableMap<String, String>? = null): List<KeyboardLocale> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .mapNotNull { dir ->
                try {
                    PackLoader.load(dir, appVersionCode)
                } catch (e: InvalidPackException) {
                    failures?.put(dir.name, e.message ?: "invalid")
                    null
                }
            }

    /** Installed pack version by language id, for update checks. */
    fun installedVersions(): Map<String, String> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { dir -> runCatching { PackLoader.readManifest(dir) }.getOrNull()?.let { it.id to it.packVersion } }
            .toMap()

    fun uninstall(id: String) {
        require(id.matches(Regex("[a-z]{2,3}_[A-Z]{2}"))) { "bad id $id" }
        File(root, id).deleteRecursively()
    }

    /** Removes staging and previous-version directories left behind by a process killed mid-install. */
    fun cleanUp() {
        root.listFiles().orEmpty().filter { it.name.startsWith(".") }.forEach { it.deleteRecursively() }
    }

    private fun extract(input: InputStream, into: File) {
        into.mkdirs()
        val canonicalRoot = into.canonicalFile
        var entries = 0
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw InvalidPackException("too many entries")
                val target = File(canonicalRoot, entry.name).canonicalFile
                if (!target.path.startsWith(canonicalRoot.path + File.separator)) {
                    throw InvalidPackException("entry escapes the pack: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                var written = 0L
                target.outputStream().use { out ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        written += read
                        total += read
                        if (written > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) throw InvalidPackException("pack too large")
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 64
        const val MAX_ENTRY_BYTES = 48L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
