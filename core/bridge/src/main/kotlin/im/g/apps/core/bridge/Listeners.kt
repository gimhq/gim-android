package im.g.apps.core.bridge

// Callback interfaces implemented by the app. gim-core calls them on a Rust background thread, in order;
// copy the data and return quickly. Declared as `fun interface` for lambda ergonomics.

/** Receives chat list changes, most recently updated chat first. The first callback is a `Reset`. */
fun interface ChatListListener {
    fun onUpdate(diffs: List<ChatListDiff>)
}

/** Receives timeline changes, oldest item first. The first callback is a `Reset`. */
fun interface TimelineListener {
    fun onUpdate(diffs: List<TimelineDiff>)
}

/** Receives connection state changes. The first callback carries the current state. */
fun interface ConnectionStateListener {
    fun onChange(state: ConnectionState)
}

/** Receives the users currently typing in one chat (never the signed-in user); first the current set. */
fun interface TypingListener {
    fun onUpdate(userIds: List<String>)
}

/**
 * Keeps a listener subscription alive; mirrors gim-core's `TaskHandle`. [cancel] or [close] ends the
 * subscription (both idempotent); no callback is delivered afterwards.
 */
interface TaskHandle : AutoCloseable {
    fun cancel()

    /** Whether the subscription has ended (cancelled or finished on its own). */
    fun isFinished(): Boolean

    override fun close()
}

/** The message timeline of one chat; mirrors gim-core's `Timeline` object. */
interface Timeline {
    fun chatId(): String

    /** Subscribes to timeline changes. The first callback is a `Reset` with the currently loaded items. */
    suspend fun addListener(listener: TimelineListener): TaskHandle

    /**
     * Loads up to [count] older items (delivered through the listeners as diffs). Returns whether more
     * history exists. gim-core takes a `u16`; the adapter converts.
     */
    suspend fun paginateBackwards(count: Int): Boolean
}

/**
 * Services the native app provides to gim-core; mirrors `hooks.rs`. Methods may be called from any
 * thread, so implementations must be fast and thread-safe.
 */
interface PlatformHooks {
    /** Reads a secret from the platform keystore; null when the key does not exist. */
    fun secureStoreGet(key: String): ByteArray?

    /** Writes a secret, replacing any existing value. Throws [GimException] on failure. */
    fun secureStoreSet(key: String, value: ByteArray)

    /** Deletes a secret; a missing key is not an error. */
    fun secureStoreDelete(key: String)

    /** Absolute path of an app-private directory, stable across launches. */
    fun dataDir(): String

    /** Current network reachability; changes are pushed with [GimClient.setNetworkReachable]. */
    fun isNetworkReachable(): Boolean
}
