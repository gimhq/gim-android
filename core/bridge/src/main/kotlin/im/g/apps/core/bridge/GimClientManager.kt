package im.g.apps.core.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persists the server URL the client was created for; null when signed out. */
interface ServerUrlStore {
    fun get(): String?

    fun set(url: String?)
}

/** Process-local [ServerUrlStore] for tests and previews. */
class InMemoryServerUrlStore(@Volatile private var url: String? = null) : ServerUrlStore {
    override fun get(): String? = url

    override fun set(url: String?) {
        this.url = url
    }
}

/**
 * Owns the process-wide [GimClient]: one client per server URL, created through [factory] and
 * remembered in [serverUrlStore] so it can be [restore]d on the next launch. Calls are serialized.
 */
class GimClientManager(
    private val factory: GimClientFactory,
    private val hooks: PlatformHooks,
    private val serverUrlStore: ServerUrlStore,
) {
    private val mutex = Mutex()
    private var clientUrl: String? = null
    private val _client = MutableStateFlow<GimClient?>(null)

    /** The current client, or null before [restore]/[connect] and after [signOut]. */
    val client: StateFlow<GimClient?> = _client.asStateFlow()

    /** Creates the client for the stored server URL; null when none is stored. */
    suspend fun restore(): GimClient? = mutex.withLock {
        _client.value?.let { return it }
        val url = serverUrlStore.get() ?: return null
        create(url)
    }

    /**
     * Returns a client for [serverUrl] (trimmed), reusing the current one when the URL is unchanged.
     * Throws [GimException.InvalidInput] for anything but an `http`/`https` URL with a host.
     */
    suspend fun connect(serverUrl: String): GimClient = mutex.withLock {
        val url = serverUrl.trim()
        requireValidServerUrl(url)
        val current = _client.value
        if (current != null && url == clientUrl) return current
        create(url).also { serverUrlStore.set(url) }
    }

    /** Signs the current client out, forgets the server URL and drops the client, even when sign-out throws. */
    suspend fun signOut() = mutex.withLock {
        try {
            _client.value?.signOut()
        } finally {
            serverUrlStore.set(null)
            clientUrl = null
            _client.value = null
        }
    }

    private suspend fun create(url: String): GimClient =
        factory.create(ClientConfig(url), hooks).also {
            clientUrl = url
            _client.value = it
        }
}
