package dev.omakey.core.pack

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * `index.json` on the `packs` GitHub release: every pack there is to download, with the SHA-256
 * each must match. Written and signed by `scripts/publish_index.py`.
 */
@Serializable
data class PackIndex(
    val formatVersion: Int,
    val generated: String = "",
    val packs: List<PackIndexEntry>,
)

@Serializable
data class PackIndexEntry(
    val id: String,
    val displayName: String,
    val nativeName: String,
    val packVersion: String,
    val packFormat: Int,
    val minAppVersionCode: Int = 0,
    val sizeBytes: Long,
    val sha256: String,
    /** File name of the zip on the same release. A name, not a URL, so the index can't point the
     * app anywhere else. */
    val file: String,
    val license: String = "",
)

/**
 * Verifies and parses a downloaded index.
 *
 * The index is what makes a pack trustworthy — it carries the SHA-256 every pack is checked
 * against — so it is itself signed (ECDSA P-256 over SHA-256). HTTPS proves the bytes came from
 * GitHub; the signature proves they came from whoever holds `keystore/pack-signing-key.pem`, which
 * is what matters if the GitHub account or a release asset is ever tampered with.
 */
object PackIndexVerifier {

    /** DER SubjectPublicKeyInfo of the key in `keystore/pack-signing-key.pem`, base64. Regenerating
     * that key means every already-installed app rejects every future index until it updates. */
    const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEf0NKGKy1G9koaVsZk7qk5AfGf5aoPGVf2GAy77z6EAPO7Afp6/ncy8rkQT8043HSF5qNH2D6c2R/dc6HkpiwbA=="

    const val FORMAT = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun verify(index: ByteArray, signature: ByteArray, publicKey: String = PUBLIC_KEY): PackIndex {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        val valid = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(index)
                verify(signature)
            }
        } catch (e: java.security.SignatureException) {
            false
        }
        if (!valid) throw InvalidPackException("language index signature does not verify")
        val parsed = try {
            json.decodeFromString(PackIndex.serializer(), index.decodeToString())
        } catch (e: SerializationException) {
            throw InvalidPackException("language index: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw InvalidPackException("language index: ${e.message}", e)
        }
        if (parsed.formatVersion != FORMAT) throw InvalidPackException("language index format ${parsed.formatVersion}")
        for (entry in parsed.packs) {
            if (!entry.file.matches(FILE_NAME)) throw InvalidPackException("bad file name ${entry.file}")
            if (!entry.sha256.matches(SHA256)) throw InvalidPackException("bad checksum for ${entry.id}")
        }
        return parsed
    }

    private val FILE_NAME = Regex("[A-Za-z0-9_.-]+\\.zip")
    private val SHA256 = Regex("[0-9a-fA-F]{64}")
}

/** Semver-ish comparison of pack versions ("1.2.0" > "1.10.0" is false). */
fun isNewerPackVersion(candidate: String, installed: String): Boolean {
    val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
    val b = installed.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
