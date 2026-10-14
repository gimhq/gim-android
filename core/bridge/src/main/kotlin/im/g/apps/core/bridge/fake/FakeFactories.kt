package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.ClientConfig
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.PlatformHooks
import im.g.apps.core.bridge.requireValidServerUrl
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [GimClientFactory] returning fake clients from [newClient]. Validates the server URL like gim-core's
 * `createClient` and records every client it creates in [created].
 */
class FakeGimClientFactory(
    private val newClient: (ClientConfig, PlatformHooks) -> FakeGimClient,
) : GimClientFactory {
    private val createdClients = mutableListOf<FakeGimClient>()

    val created: List<FakeGimClient>
        get() = synchronized(createdClients) { createdClients.toList() }

    override suspend fun create(config: ClientConfig, hooks: PlatformHooks): GimClient {
        requireValidServerUrl(config.serverUrl)
        return newClient(config, hooks).also { synchronized(createdClients) { createdClients += it } }
    }
}

/** In-memory, thread-safe [PlatformHooks]. */
class FakePlatformHooks(
    private val dataDirPath: String = File(System.getProperty("java.io.tmpdir"), "gim-fake").absolutePath,
    @Volatile var networkReachable: Boolean = true,
) : PlatformHooks {
    private val secrets = ConcurrentHashMap<String, ByteArray>()

    override fun secureStoreGet(key: String): ByteArray? = secrets[key]?.copyOf()

    override fun secureStoreSet(key: String, value: ByteArray) {
        secrets[key] = value.copyOf()
    }

    override fun secureStoreDelete(key: String) {
        secrets.remove(key)
    }

    override fun dataDir(): String = dataDirPath

    override fun isNetworkReachable(): Boolean = networkReachable
}
