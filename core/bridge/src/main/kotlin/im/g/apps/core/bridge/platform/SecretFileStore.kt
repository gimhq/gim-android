package im.g.apps.core.bridge.platform

import android.util.Log
import im.g.apps.core.bridge.GimException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.AEADBadTagException

/**
 * One file per secret under [dir], named by the SHA-256 hex of the key so keys never reach the
 * file system. Values are sealed by [cipher] and written atomically (temp file + rename).
 */
class SecretFileStore(private val dir: File, private val cipher: SecretCipher) {

    /**
     * Null when the key is missing or its sealed value is corrupted (logged; the value cannot be recovered).
     * Throws [GimException.Storage] when the file cannot be read or the keystore cannot open it right now, so
     * gim-core never mistakes a locked keystore for a missing secret.
     */
    @Synchronized
    fun get(key: String): ByteArray? {
        val file = fileFor(key)
        if (!file.exists()) return null
        return try {
            cipher.open(file.readBytes())
        } catch (e: AEADBadTagException) {
            Log.w(TAG, "Corrupted secret file ${file.name}", e)
            null
        } catch (e: IOException) {
            throw GimException.Storage("secure store read failed: ${e.message}").apply { initCause(e) }
        } catch (e: GeneralSecurityException) {
            throw GimException.Storage("secure store unavailable: ${e.message}").apply { initCause(e) }
        }
    }

    /** Throws [GimException.Storage] when the value cannot be sealed or written. */
    @Synchronized
    fun put(key: String, value: ByteArray) {
        val file = fileFor(key)
        val tmp = File(dir, "${file.name}.tmp")
        try {
            val sealed = cipher.seal(value)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")
            FileOutputStream(tmp).use {
                it.write(sealed)
                it.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("cannot rename ${tmp.name}")
        } catch (e: IOException) {
            tmp.delete()
            throw GimException.Storage("secure store write failed: ${e.message}")
        } catch (e: GeneralSecurityException) {
            throw GimException.Storage("secure store seal failed: ${e.message}")
        }
    }

    /** Removing a missing key is not an error. */
    @Synchronized
    fun delete(key: String) {
        val file = fileFor(key)
        if (file.exists() && !file.delete()) throw GimException.Storage("secure store delete failed")
    }

    /** Deletes every secret and [dir] itself; false when something could not be deleted. */
    @Synchronized
    fun clear(): Boolean = dir.deleteRecursively()

    internal fun fileFor(key: String): File = File(dir, sha256Hex(key))

    private companion object {
        const val TAG = "SecretFileStore"
    }
}

internal fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
