package dev.omakey.core.snippets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals a secret snippet's text. Implementations return null on any failure and never throw: a
 * snippet that can't be sealed isn't saved, and one that can't be opened types nothing (its edit
 * form asks for it again).
 */
interface SecretCipher {
    fun encrypt(plain: String): Sealed?
    fun decrypt(sealed: Sealed): String?

    class Sealed(val iv: ByteArray, val bytes: ByteArray)
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore (hardware-backed where the phone has
 * it) and never leaves it: a backup, a copied app folder or a rooted file read holds only
 * ciphertext. No user-authentication requirement: the developer chose one tap to type a secret,
 * with the phone's own lock as the protection (secrets are hidden while it is locked).
 */
class KeystoreSecretCipher : SecretCipher {

    override fun encrypt(plain: String): SecretCipher.Sealed? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        SecretCipher.Sealed(cipher.iv, cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    override fun decrypt(sealed: SecretCipher.Sealed): String? = runCatching {
        val existing = existingKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(TAG_BITS, sealed.iv))
        String(cipher.doFinal(sealed.bytes), Charsets.UTF_8)
    }.getOrNull()

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    /** One generation at a time across the process: two saves of the very first secret both found
     * no key, both generated one, and the second replaced the first, so the first secret could no
     * longer be opened. */
    private fun key(): SecretKey = synchronized(KEY_LOCK) { existingKey() ?: generate() }

    private fun generate(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "omakey_snippets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        val KEY_LOCK = Any()
    }
}
