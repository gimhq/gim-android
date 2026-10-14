package im.g.apps.core.bridge.platform

import java.security.GeneralSecurityException

/** Reversible XOR "cipher" with a magic prefix so corrupted input is detected like a GCM tag failure. */
class FakeSecretCipher : SecretCipher {
    override fun seal(plaintext: ByteArray): ByteArray = MAGIC + plaintext.map { (it.toInt() xor 0x5A).toByte() }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < MAGIC.size || !sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw GeneralSecurityException("bad tag")
        }
        return sealed.copyOfRange(MAGIC.size, sealed.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private companion object {
        val MAGIC = byteArrayOf(0x47, 0x49, 0x4D)
    }
}
