package im.g.apps.core.bridge.platform

import android.content.Context
import android.util.Log
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.PlatformHooks
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * [PlatformHooks] backed by Android: secrets sealed by [cipher] under `noBackupFilesDir/secrets`,
 * data in `filesDir/gim` (owner-only), reachability from [reachabilityMonitor] (share the app's instance).
 */
class AndroidPlatformHooks(
    context: Context,
    private val cipher: SecretCipher = KeystoreSecretCipher(),
    private val reachabilityMonitor: AndroidReachabilityMonitor = AndroidReachabilityMonitor(context),
) : PlatformHooks {
    private val appContext = context.applicationContext
    private val secrets = SecretFileStore(File(appContext.noBackupFilesDir, "secrets"), cipher)
    private val dataDirFile get() = File(appContext.filesDir, "gim").absoluteFile

    override fun secureStoreGet(key: String): ByteArray? = secrets.get(key)

    override fun secureStoreSet(key: String, value: ByteArray) = secrets.put(key, value)

    override fun secureStoreDelete(key: String) = secrets.delete(key)

    /** Absolute app-private directory for gim-core's database and media, created on first use with mode 0700. */
    override fun dataDir(): String {
        val dir = dataDirFile
        if (!dir.isDirectory) {
            if (!dir.mkdirs() && !dir.isDirectory) throw GimException.Storage("cannot create $dir")
            dir.setReadable(false, false)
            dir.setWritable(false, false)
            dir.setExecutable(false, false)
            dir.setReadable(true, true)
            dir.setWritable(true, true)
            dir.setExecutable(true, true)
        }
        return dir.path
    }

    override fun isNetworkReachable(): Boolean = reachabilityMonitor.reachable.value

    /**
     * Deletes the secrets directory, the [cipher] key and whatever gim-core left in [dataDir]. Idempotent;
     * failures are logged and the remaining steps still run, so sign-out always completes.
     */
    fun wipe() {
        if (!secrets.clear()) Log.w(TAG, "Cannot delete the secrets directory")
        try {
            cipher.deleteKey()
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Cannot delete the secret store key", e)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot delete the secret store key", e)
        }
        if (!dataDirFile.deleteRecursively()) Log.w(TAG, "Cannot delete the data directory")
    }

    private companion object {
        const val TAG = "AndroidPlatformHooks"
    }
}
