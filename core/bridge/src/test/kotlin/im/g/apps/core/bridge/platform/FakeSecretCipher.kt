package im.g.apps.core.bridge.platform

import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

/** Reversible XOR "cipher" with a magic prefix so corrupted input is detected like a GCM tag failure. */
class FakeSecretCipher : SecretCipher {
    /** When set, [open] throws it, like a locked or unavailable keystore. */
    @Volatile var openFailure: GeneralSecurityException? = null

    override fun seal(plaintext: ByteArray): ByteArray = MAGIC + plaintext.map { (it.toInt() xor 0x5A).toByte() }

    override fun open(sealed: ByteArray): ByteArray {
        openFailure?.let { throw it }
        if (sealed.size < MAGIC.size || !sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw AEADBadTagException("bad tag")
        }
        return sealed.copyOfRange(MAGIC.size, sealed.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    var deletedKeys = 0
        private set

    override fun deleteKey() {
        deletedKeys++
    }

    private companion object {
        val MAGIC = byteArrayOf(0x47, 0x49, 0x4D)
    }
}
