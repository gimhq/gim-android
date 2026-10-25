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
 * remembered in [serverUrlStore] so it can be [restore]d on the next launch. Calls are serialized. Every client is
 * created with [keepServerUrl] ([ClientConfig.keepServerUrl]; null: none configured).
 */
class GimClientManager(
    private val factory: GimClientFactory,
    private val hooks: PlatformHooks,
    private val serverUrlStore: ServerUrlStore,
    private val keepServerUrl: String? = null,
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
     *
     * A client for another URL replaces the current one, which is signed out (failures ignored) and dropped first:
     * all clients share one data dir, and gim-core serializes their store changes on `<dataDir>/gim.lock`, so two
     * live clients would contend for it (`Storage` "store is locked"). Only onboarding connects, before any session.
     */
    suspend fun connect(serverUrl: String): GimClient = mutex.withLock {
        val url = serverUrl.trim()
        requireValidServerUrl(url)
        val current = _client.value
        if (current != null && url == clientUrl) return current
        if (current != null) {
            try {
                current.signOut()
            } catch (_: GimException) {
                // Not signed in (the usual case) or already signed out; the client is dropped either way.
            }
            clientUrl = null
            _client.value = null
        }
        create(url).also { serverUrlStore.set(url) }
    }

    /**
     * Replaces the current client with a new one for the same server URL, as a process restart would; null without a
     * client. For device tests: gim-core keeps per-run state in memory only, such as the 10-minute sign-in back-off
     * after `UserSuspended` / `UserNotAdmitted`. The old client's sync is stopped and the client dropped, not signed
     * out (session and store stay); screens created before keep it until they are recreated.
     */
    suspend fun reopen(): GimClient? = mutex.withLock {
        val url = clientUrl ?: return null
        try {
            _client.value?.stopSync()
        } catch (_: GimException) {
            // Dropped either way.
        }
        create(url)
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

    /**
     * Deletes the account of the current client ([GimClient.deleteAccount]) and returns the hubs that could not be
     * reached; then forgets the server URL and drops the client, as [signOut]. On failure nothing changes and the call
     * can be repeated. [GimException.NotRegistered] without a client.
     */
    suspend fun deleteAccount(): List<String> = mutex.withLock {
        val client = _client.value ?: throw GimException.NotRegistered()
        client.deleteAccount().also {
            serverUrlStore.set(null)
            clientUrl = null
            _client.value = null
        }
    }

    private suspend fun create(url: String): GimClient =
        factory.create(ClientConfig(url, keepServerUrl = keepServerUrl), hooks).also {
            clientUrl = url
            _client.value = it
        }
}
