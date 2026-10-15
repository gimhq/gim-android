package im.g.apps.core.bridge.platform

import android.content.Context
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.PlatformHooks
import java.io.File

/**
 * [PlatformHooks] backed by Android: secrets sealed by [cipher] under `noBackupFilesDir/secrets`,
 * data in `filesDir/gim` (owner-only), reachability from [reachabilityMonitor] (share the app's instance).
 */
class AndroidPlatformHooks(
    context: Context,
    cipher: SecretCipher = KeystoreSecretCipher(),
    private val reachabilityMonitor: AndroidReachabilityMonitor = AndroidReachabilityMonitor(context),
) : PlatformHooks {
    private val appContext = context.applicationContext
    private val secrets = SecretFileStore(File(appContext.noBackupFilesDir, "secrets"), cipher)

    override fun secureStoreGet(key: String): ByteArray? = secrets.get(key)

    override fun secureStoreSet(key: String, value: ByteArray) = secrets.put(key, value)

    override fun secureStoreDelete(key: String) = secrets.delete(key)

    /** Absolute app-private directory for gim-core's database and media, created on first use with mode 0700. */
    override fun dataDir(): String {
        val dir = File(appContext.filesDir, "gim").absoluteFile
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
}
