package im.g.apps.core.bridge.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises [KeystoreSecretCipher] against the device's real AndroidKeyStore. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretCipherTest {
    private val suffix = UUID.randomUUID().toString()
    private val alias = "gim_test_$suffix"
    private val otherAlias = "gim_test_other_$suffix"
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "secret-store-$suffix")
    }

    @After
    fun tearDown() {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
            deleteEntry(otherAlias)
        }
        dir.deleteRecursively()
    }

    @Test
    fun sealThenOpenRoundTrips() {
        val cipher = KeystoreSecretCipher(alias)
        val plaintext = "mls signature key".toByteArray()

        assertArrayEquals(plaintext, cipher.open(cipher.seal(plaintext)))
        assertArrayEquals(ByteArray(0), cipher.open(cipher.seal(ByteArray(0))))
    }

    @Test
    fun sealUsesFreshIvEveryTime() {
        val cipher = KeystoreSecretCipher(alias)
        val plaintext = "same input".toByteArray()

        val first = cipher.seal(plaintext)
        val second = cipher.seal(plaintext)

        assertFalse(first.contentEquals(second))
        assertFalse(first.copyOfRange(0, 12).contentEquals(second.copyOfRange(0, 12)))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val cipher = KeystoreSecretCipher(alias)
        val sealed = cipher.seal("payload".toByteArray())

        for (index in listOf(0, 12, sealed.lastIndex)) {
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertThrows(GeneralSecurityException::class.java) { cipher.open(tampered) }
        }
    }

    @Test
    fun tooShortInputIsRejected() {
        val cipher = KeystoreSecretCipher(alias)

        assertThrows(GeneralSecurityException::class.java) { cipher.open(ByteArray(0)) }
        assertThrows(GeneralSecurityException::class.java) { cipher.open(ByteArray(27)) }
    }

    @Test
    fun keyPersistsAcrossInstancesWithSameAlias() {
        val sealed = KeystoreSecretCipher(alias).seal("persisted".toByteArray())

        assertArrayEquals("persisted".toByteArray(), KeystoreSecretCipher(alias).open(sealed))
    }

    @Test
    fun distinctAliasesCannotOpenEachOther() {
        val cipher = KeystoreSecretCipher(alias)
        val other = KeystoreSecretCipher(otherAlias)

        assertThrows(GeneralSecurityException::class.java) { other.open(cipher.seal("a".toByteArray())) }
        assertThrows(GeneralSecurityException::class.java) { cipher.open(other.seal("b".toByteArray())) }
    }

    @Test
    fun secretFileStoreRoundTripsThroughDisk() {
        val value = "device identity secret".toByteArray()
        SecretFileStore(dir, KeystoreSecretCipher(alias)).put("identity", value)

        val onDisk = SecretFileStore(dir, KeystoreSecretCipher(alias)).fileFor("identity").readBytes()
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains("device identity secret"))
        assertArrayEquals(value, SecretFileStore(dir, KeystoreSecretCipher(alias)).get("identity"))
    }
}
