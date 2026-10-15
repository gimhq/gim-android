package im.g.apps.core.bridge

/**
 * Kotlin mirror of gim-core's UniFFI `GimClient` (gim-core `bindings/gim-ffi`, contract in
 * `docs/API.md` at gim-core commit b810805).
 *
 * gim-core (Rust) owns storage, networking, sync, outbox, MLS and media crypto; this interface is the only
 * surface the UI layer sees. Features never import `im.g.gimcore`: the real adapter maps the generated
 * bindings onto these types mechanically. Until the bindings are vendored, use
 * [im.g.apps.core.bridge.fake.FakeGimClient].
 *
 * Every suspend method throws [GimException] on failure. Listeners are called on a background thread, in
 * order; see [im.g.apps.core.bridge.flow] for main-safe flows.
 *
 * ## Mapping (gim-core `im.g.gimcore` -> `im.g.apps.core.bridge`)
 *
 * | gim-core | mirror | notes |
 * |---|---|---|
 * | `GimClient` / `GimClientInterface` | [GimClient] | same methods, names, parameters, suspend-ness |
 * | `createClient(config, hooks)` | [GimClientFactory.create] | |
 * | `Timeline` / `TimelineInterface` | [Timeline] | `paginateBackwards(count: UShort)` -> `Int` |
 * | `TaskHandle` | [TaskHandle] | Kotlin interface; `close()` cancels like `destroy()` |
 * | `PlatformHooks` | [PlatformHooks] | |
 * | `ChatListListener`, `TimelineListener`, `ConnectionStateListener`, `TypingListener` | same names | `fun interface` |
 * | `ChatListDiff`, `TimelineDiff` | [ChatListDiff], [TimelineDiff] | `index`/`length: UInt` -> `Int` |
 * | `Session`, `UserProfile`, `ChatKind`, `ChatSummary`, `MessagePreview` | same names | `UInt` -> `Int`, `ULong` -> `Long` |
 * | `TimelineItem`, `MembershipChangeKind`, `MessageItem`, `MessageContent` | same names | `ULong` -> `Long`, `UInt?` -> `Int?` |
 * | `MediaRef`, `ReplyPreview`, `ReactionGroup`, `SendState`, `ConnectionState`, `ClientConfig` | same names | `size: ULong` -> `Long`, `count: UInt` -> `Int` |
 * | `GimException.<Variant>` | [GimException]`.<Variant>` | `Server.status: UShort` -> `Int` |
 *
 * Enum constants keep UniFFI's Kotlin spelling (`ChatKind.DIRECT`, `ConnectionState.CONNECTED`, ...).
 */
interface GimClient {
    /** The persisted session, or null before [register]. */
    suspend fun session(): Session?

    /** Creates the account and this device on the server and persists the session. */
    suspend fun register(handle: String, displayName: String): Session

    /** Stops sync and deletes the session, local data and keystore secrets. Create a new client afterwards. */
    suspend fun signOut()

    /**
     * Starts the background sync loop (WebSocket stream with a long-poll fallback) and the outbox sender. After a
     * failure the loop reports [ConnectionState.OFFLINE] and retries with exponential backoff (up to 60 s).
     */
    suspend fun startSync()

    /** Stops the background sync loop and outbox sender. */
    suspend fun stopSync()

    /**
     * Reports a reachability change from the platform network monitor. Synchronous, never throws. Becoming
     * reachable after unreachable reconnects at once and retries pending sends; becoming unreachable neither
     * pauses sync nor forces [ConnectionState.OFFLINE].
     */
    fun setNetworkReachable(reachable: Boolean)

    /** Looks up a user by handle; null if no such user exists. */
    suspend fun findUser(handle: String): UserProfile?

    /** Returns the chat id of the direct chat with [userId], creating it if needed. */
    suspend fun createDirectChat(userId: String): String

    /** Creates a group chat and returns its chat id. The name is shared with the members in a group info message. */
    suspend fun createGroupChat(name: String, memberUserIds: List<String>): String

    /**
     * Current members of [chatId], one entry per user (not per device), including the signed-in user.
     * There is no members listener: re-query when a [ChatListDiff] touches the chat or a
     * [TimelineItem.MembershipChange] arrives.
     */
    suspend fun chatMembers(chatId: String): List<UserProfile>

    suspend fun addMembers(chatId: String, userIds: List<String>)

    suspend fun removeMember(chatId: String, userId: String)

    suspend fun leaveChat(chatId: String)

    /**
     * Updates the group name and/or avatar and shares them with the members; null leaves a field unchanged, but
     * at least one must be set. The avatar file is encrypted and uploaded before this returns (MIME type from the
     * file extension); the new values then arrive as a [ChatListDiff]. Throws [GimException.InvalidInput] for
     * direct chats, a blank name or nothing to change.
     */
    suspend fun setGroupInfo(chatId: String, name: String?, avatarFilePath: String?)

    /** Queues a text message and returns its message id; it appears immediately as [SendState.Pending]. */
    suspend fun sendText(chatId: String, body: String, replyTo: String?): String

    /** Encrypts, uploads and sends a file; returns its message id. The file is copied before this returns. */
    suspend fun sendMedia(chatId: String, filePath: String, mime: String, caption: String?, replyTo: String?): String

    suspend fun editMessage(chatId: String, messageId: String, newBody: String)

    suspend fun deleteMessage(chatId: String, messageId: String)

    suspend fun react(chatId: String, messageId: String, emoji: String)

    suspend fun unreact(chatId: String, messageId: String, emoji: String)

    /** Re-queues a message in [SendState.Failed]. */
    suspend fun retrySend(chatId: String, messageId: String)

    /** Removes an unsent message from the outbox and the timeline. */
    suspend fun cancelSend(chatId: String, messageId: String)

    /** Marks everything up to and including [upToMessageId] as read and sends a read receipt. */
    suspend fun markRead(chatId: String, upToMessageId: String)

    suspend fun setTyping(chatId: String, active: Boolean)

    /** Downloads and decrypts an attachment to [destPath] and returns the path of the decrypted file. */
    suspend fun fetchMedia(chatId: String, messageId: String, destPath: String): String

    /** Like [fetchMedia] for the thumbnail; null if there is none. */
    suspend fun fetchThumbnail(chatId: String, messageId: String, destPath: String): String?

    /** Subscribes to the chat list; the first callback is a `Reset`. */
    suspend fun chatList(listener: ChatListListener): TaskHandle

    /** The timeline of one chat. Cheap; listeners are added on the result. */
    suspend fun timeline(chatId: String): Timeline

    /**
     * Subscribes to connection state changes; the first callback carries the current state. [ConnectionState.OFFLINE]
     * until [startSync] and after a failure while waiting to retry; then CONNECTING -> SYNCING (catching up) ->
     * CONNECTED.
     */
    suspend fun connectionState(listener: ConnectionStateListener): TaskHandle

    /** Subscribes to typing notifications of one chat; the first callback carries the current set. */
    suspend fun typing(chatId: String, listener: TypingListener): TaskHandle
}

/**
 * Creates a [GimClient]; mirrors gim-core's `createClient`. Throws [GimException.InvalidInput] unless
 * [ClientConfig.serverUrl] is an `http://` or `https://` URL with a host.
 */
fun interface GimClientFactory {
    suspend fun create(config: ClientConfig, hooks: PlatformHooks): GimClient
}
