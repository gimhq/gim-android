package im.g.apps.core.bridge

// Callback interfaces implemented by the app. gim-core calls them (and PlatformHooks / LocalTimeZone) on its single
// `gim-callbacks` thread, one call at a time, in order: copy the data and return quickly, hop to the main thread or
// a channel for anything else, and never call back into GimClient (nested calls abort the process on Android).
// Declared as `fun interface` for lambda ergonomics.

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

/**
 * The local time zone timeline date separators split calendar days in; mirrors gim-core's `LocalTimeZone`, set with
 * [GimClient.setLocalTimeZone]. Called on gim-core's `gim-callbacks` thread: keep it fast and never call back into
 * [GimClient] from it.
 */
fun interface LocalTimeZone {
    /** Offset from UTC in seconds in effect at [atMs] (Unix milliseconds), DST included. */
    fun utcOffsetSecs(atMs: Long): Int
}

/** Receives the users currently typing in one chat (never the signed-in user); first the current set. */
fun interface TypingListener {
    fun onUpdate(userIds: List<String>)
}

/**
 * Receives the user's hubs (see [GimClient.hubs]) whenever they may have changed: added, removed, verified,
 * reprioritized, or a URL shared by another own device. The first callback carries the current list.
 */
fun interface HubListListener {
    fun onUpdate(hubs: List<HubInfo>)
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
     * history exists. Pages through locally stored history first, then server history; messages this device
     * cannot decrypt are skipped, and it returns false once nothing older is readable. gim-core takes a `u16`;
     * the adapter converts.
     */
    suspend fun paginateBackwards(count: Int): Boolean

    /** The chat's current [ChatState] from local state; null while the chat is not known locally. Synchronous. */
    fun chatState(): ChatState?
}

/**
 * Services the native app provides to gim-core; mirrors `hooks.rs`. Called on gim-core's `gim-callbacks` thread
 * (shared with every listener), so implementations must be fast, thread-safe and never call into [GimClient].
 */
interface PlatformHooks {
    /**
     * Reads a secret from the platform keystore; null when the key does not exist. Throws [GimException] when the
     * keystore cannot be read (e.g. locked), so gim-core fails the call instead of treating the secret as missing.
     */
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
