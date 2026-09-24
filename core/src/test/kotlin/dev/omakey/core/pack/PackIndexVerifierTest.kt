package dev.omakey.core.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PackIndexVerifierTest {

    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(data)
        sign()
    }

    private val index = """
        {"formatVersion":1,"packs":[{"id":"es_ES","displayName":"Spanish","nativeName":"Español","packVersion":"1.0.0",
         "packFormat":1,"sizeBytes":3072249,"sha256":"${"a".repeat(64)}","file":"es_ES-1.0.0.zip"}]}
    """.trimIndent().toByteArray()

    private fun rejects(block: () -> Unit): Boolean = try {
        block(); false
    } catch (_: InvalidPackException) {
        true
    }

    @Test
    fun `a correctly signed index parses`() {
        val parsed = PackIndexVerifier.verify(index, sign(index), publicKey)
        assertEquals("es_ES", parsed.packs.single().id)
    }

    @Test
    fun `a tampered index, a wrong key or garbage signature is refused`() {
        val signature = sign(index)
        val tampered = index.decodeToString().replace("3072249", "3072250").toByteArray()
        assertTrue(rejects { PackIndexVerifier.verify(tampered, signature, publicKey) })
        assertTrue(rejects { PackIndexVerifier.verify(index, signature) }) // the real key didn't sign this
        assertTrue(rejects { PackIndexVerifier.verify(index, byteArrayOf(1, 2, 3), publicKey) })
    }

    @Test
    fun `an index may not name a file outside the release or omit a real checksum`() {
        for (bad in listOf(
            index.decodeToString().replace("es_ES-1.0.0.zip", "../x.zip"),
            index.decodeToString().replace("es_ES-1.0.0.zip", "https://evil.example/x.zip"),
            index.decodeToString().replace("a".repeat(64), "abc"),
        )) {
            val bytes = bad.toByteArray()
            assertTrue(bad, rejects { PackIndexVerifier.verify(bytes, sign(bytes), publicKey) })
        }
    }

    /** OpenSSL signs, Java verifies — the real pipeline. Runs when `scripts/publish_index.py` has
     * produced an index in this checkout. */
    @Test
    fun `the published index verifies with the key built into the app`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "build/packs/index.json").isFile) dir = dir.parentFile
        assumeTrue("no build/packs/index.json in this checkout", dir != null)
        val parsed = PackIndexVerifier.verify(
            File(dir, "build/packs/index.json").readBytes(),
            File(dir, "build/packs/index.json.sig").readBytes(),
        )
        assertTrue(parsed.packs.isNotEmpty())
    }

    @Test
    fun `pack versions compare numerically`() {
        assertTrue(isNewerPackVersion("1.10.0", "1.2.0"))
        assertFalse(isNewerPackVersion("1.2.0", "1.10.0"))
        assertFalse(isNewerPackVersion("1.0.0", "1.0"))
        assertTrue(isNewerPackVersion("2", "1.9.9"))
    }
}
