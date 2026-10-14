package im.g.apps.core.bridge.platform

/**
 * Platform hooks gim-core calls back into through UniFFI callback interfaces.
 * Android implementations live in this package.
 */
interface SecretStore {
    fun get(key: String): ByteArray?

    fun put(key: String, value: ByteArray)

    fun delete(key: String)
}

interface DataDirProvider {
    /** Absolute path of the persistent app data directory. */
    fun dataDir(): String

    /** Absolute path of the evictable cache directory. */
    fun cacheDir(): String
}

fun interface ReachabilityListener {
    fun onChange(reachable: Boolean)
}

interface ReachabilityProvider {
    fun isReachable(): Boolean

    fun setListener(listener: ReachabilityListener?)
}

data class PlatformHooks(
    val secretStore: SecretStore,
    val dataDir: DataDirProvider,
    val reachability: ReachabilityProvider,
)
