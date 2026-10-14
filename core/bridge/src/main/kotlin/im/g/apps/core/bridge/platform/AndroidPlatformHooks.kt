package im.g.apps.core.bridge.platform

import android.content.Context
import im.g.apps.core.bridge.PlatformHooks
import java.io.File

/**
 * [PlatformHooks] backed by Android: secrets sealed by [cipher] under `noBackupFilesDir/secrets`,
 * data in `filesDir/gim`, reachability from [reachabilityMonitor] (share the app's instance).
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

    override fun dataDir(): String = File(appContext.filesDir, "gim").apply { mkdirs() }.absolutePath

    override fun isNetworkReachable(): Boolean = reachabilityMonitor.reachable.value
}
