package im.g.apps.core.bridge.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals secrets before [SecretFileStore] writes them to disk. Implementations must be thread-safe. */
interface SecretCipher {
    fun seal(plaintext: ByteArray): ByteArray

    /** Throws [GeneralSecurityException] when [sealed] is malformed or was tampered with. */
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key held in AndroidKeyStore (no user authentication, so it is
 * usable in the background). Output is `iv || ciphertext+tag`; the keystore picks a random IV for
 * every [seal]. The key is created on first use.
 */
class KeystoreSecretCipher(private val alias: String = KEY_ALIAS) : SecretCipher {
    @Volatile private var key: SecretKey? = null

    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < IV_SIZE + TAG_BITS / 8) throw GeneralSecurityException("sealed value too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_SIZE))
        return cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)
    }

    @Synchronized
    private fun key(): SecretKey = key ?: loadOrCreate().also { key = it }

    private fun loadOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    companion object {
        const val KEY_ALIAS = "gim_secret_store_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
