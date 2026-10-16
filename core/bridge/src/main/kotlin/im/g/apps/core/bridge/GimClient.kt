package im.g.apps.core.bridge

/**
 * Kotlin mirror of gim-core's UniFFI `GimClient` (gim-core `bindings/gim-ffi`, contract in
 * `docs/API.md` at gim-core commit d5ead6d).
 *
 * gim-core (Rust) owns storage, networking, sync, outbox, MLS and media crypto; this interface is the only
 * surface the UI layer sees. Features never import `im.g.gimcore`: the real adapter maps the generated
 * bindings onto these types mechanically. Until the bindings are vendored, use
 * [im.g.apps.core.bridge.fake.FakeGimClient].
 *
 * Every suspend method throws [GimException] on failure; calls that send to or change a chat that is not
 * [ChatState.ACTIVE] throw [GimException.ChatNotActive]. Call suspend methods from a real dispatcher
 * (`Dispatchers.IO`, `Default`, `Main`, `viewModelScope`), never `Dispatchers.Unconfined`. Listeners and hooks are
 * called on gim-core's single `gim-callbacks` thread, in order: copy the data and return, never call back into
 * [GimClient] from them; see [im.g.apps.core.bridge.flow] for main-safe flows.
 *
 * ## Mapping (gim-core `im.g.gimcore` -> `im.g.apps.core.bridge`)
 *
 * | gim-core | mirror | notes |
 * |---|---|---|
 * | `GimClient` / `GimClientInterface` | [GimClient] | same methods, names, parameters, suspend-ness |
 * | `createClient(config, hooks)` | [GimClientFactory.create] | |
 * | `Timeline` / `TimelineInterface` | [Timeline] | `paginateBackwards(count: UShort)` -> `Int`; `chatState()` |
 * | `TaskHandle` | [TaskHandle] | Kotlin interface; `close()` cancels like `destroy()` |
 * | `PlatformHooks` | [PlatformHooks] | |
 * | `LinkSession`, `PendingApproval` | [LinkSession], [PendingApproval] | Kotlin interfaces wrapping the native objects |
 * | `DeviceInfo` | [DeviceInfo] | `createdAtMs: ULong` -> `Long` |
 * | `ChatListListener`, `TimelineListener`, `ConnectionStateListener`, `TypingListener`, `LocalTimeZone` | same names | `fun interface` |
 * | `ChatListDiff`, `TimelineDiff` | [ChatListDiff], [TimelineDiff] | `index`/`length: UInt` -> `Int` |
 * | `Session`, `UserProfile`, `ChatKind`, `ChatState`, `ChatSummary`, `MessagePreview` | same names | `UInt` -> `Int`, `ULong` -> `Long` |
 * | `TimelineItem`, `MembershipChangeKind`, `MessageItem`, `MessageContent` | same names | `ULong` -> `Long`, `UInt?` -> `Int?` |
 * | `MediaRef`, `ReplyPreview`, `ReactionGroup`, `SendState`, `ConnectionState`, `ClientConfig` | same names | `size: ULong` -> `Long`, `count: UInt` -> `Int` |
 * | `GimException.<Variant>` | [GimException]`.<Variant>` | `status: UShort` -> `Int`, `retryAfterSecs: ULong` -> `Long` |
 *
 * Enum constants keep UniFFI's Kotlin spelling (`ChatKind.DIRECT`, `ConnectionState.CONNECTED`, ...).
 */
interface GimClient {
    /** The persisted session, or null before [createIdentity], [waitLink] or [restoreFromRecovery]. */
    suspend fun session(): Session?

    /** The signed-in user's id; null without a session. Synchronous. */
    fun userId(): String?

    /**
     * Whether opening this client discarded an existing local database (its key was lost or it is incompatible).
     * When true, show onboarding and tell the user that local history is gone. Synchronous.
     */
    fun localStateReset(): Boolean

    /**
     * Creates a new user with this device as its first one and signs in. Returns the recovery code: it is stored
     * nowhere, so show it once. [Session.handle] stays empty until [claimHandle]. A failed call keeps nothing of the
     * attempt: call it again (it creates a new user); no [signOut] or new client is needed.
     */
    suspend fun createIdentity(displayName: String): String

    /**
     * Restores the user [userId] on this (fresh) client from its recovery code and adds this device to its
     * DeviceList; [revokeOthers] revokes every previously listed device. Throws [GimException.InvalidInput] for a
     * malformed code, [GimException.Crypto] for a wrong one, [GimException.NotFound] for an unknown user and
     * [GimException.RetryLater] while the hub throttles the read. A failed call keeps nothing of the attempt: call it
     * again with the same arguments.
     */
    suspend fun restoreFromRecovery(userId: String, recoveryCode: String, revokeOthers: Boolean)

    /** New device: starts linking to an existing device; show [LinkSession.qrString] as a QR code. Synchronous. */
    fun startLink(hubs: List<String>): LinkSession

    /**
     * New device: waits (up to 10 minutes) for the old device's bundle, signs in and returns the SAS ("123 456"). If
     * no bundle arrived, call it again with the same [link]; after a failure past the bundle, start a new link.
     */
    suspend fun waitLink(link: LinkSession): String

    /** New device: confirms the compared SAS; false wipes local state like [signOut] (create a new client). */
    suspend fun confirmLink(accepted: Boolean)

    /** Old device: approves the scanned QR code; show [PendingApproval.sas]. */
    suspend fun approveLink(qrString: String): PendingApproval

    /** Old device: true adds the new device to every chat, false revokes it. */
    suspend fun confirmApproval(approval: PendingApproval, accepted: Boolean)

    /** The signed-in user's devices, including revoked ones. */
    suspend fun devices(): List<DeviceInfo>

    /** Another user's devices; empty for an unknown user. */
    suspend fun userDevices(userId: String): List<DeviceInfo>

    /** Revokes another own device; [GimException.InvalidInput] for the current device. */
    suspend fun revokeDevice(deviceId: String)

    /** Changes the display name; [GimException.InvalidInput] if empty. */
    suspend fun setDisplayName(name: String)

    /** Claims a handle in the hub directory; [GimException.Server] with status 409 if it is taken. */
    suspend fun claimHandle(handle: String)

    /** Releases the claimed handle; [GimException.InvalidInput] if none is claimed. */
    suspend fun releaseHandle()

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

    /**
     * Returns the chat id of the direct chat with [userId], creating it if needed. [GimException.NotFound] (and no
     * chat) if the user does not exist or has no device; a user whose hub is unavailable is invited later.
     */
    suspend fun createDirectChat(userId: String): String

    /**
     * Creates a group chat and returns its chat id. The name is shared with the members in a group info message.
     * Unknown invitees (or ones without a device) are skipped; [GimException.NotFound] (and no chat) if that is every
     * invitee.
     */
    suspend fun createGroupChat(name: String, memberUserIds: List<String>): String

    /**
     * Current members of [chatId], one entry per user (not per device), including the signed-in user.
     * There is no members listener: re-query when a [ChatListDiff] touches the chat or a
     * [TimelineItem.MembershipChange] arrives.
     */
    suspend fun chatMembers(chatId: String): List<UserProfile>

    /** Skips unknown users like [createGroupChat]; [GimException.NotFound] (nothing committed) if that is all of them. */
    suspend fun addMembers(chatId: String, userIds: List<String>)

    suspend fun removeMember(chatId: String, userId: String)

    /**
     * Submits a leave proposal and returns once the hub accepted it, with the chat [ChatState.LEAVING]; another member
     * commits it, then the chat is [ChatState.LEFT] and shows the own [MembershipChangeKind.LEFT] change. gim-core
     * keeps and re-sends the leave across restarts. A leave the hub refuses for good (4xx) returns the chat to
     * [ChatState.ACTIVE] and throws the rejection; [GimException.ChatNotActive] once left.
     */
    suspend fun leaveChat(chatId: String)

    /** The chat's [ChatState] from local state, no network call; [GimException.NotFound] for an unknown chat. */
    suspend fun chatState(chatId: String): ChatState

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

    /** Same as `setTyping(chatId, false)`; does nothing unless typing was reported. */
    suspend fun stopTyping(chatId: String)

    /**
     * Downloads and decrypts an attachment to [destPath] and returns the path of the decrypted file. A blob uploaded
     * on another hub is fetched through the own hub: [GimException.RetryLater] (503) while the uploader's hub stays
     * unreachable and [GimException.Server] (502) while it keeps refusing.
     */
    suspend fun fetchMedia(chatId: String, messageId: String, destPath: String): String

    /** Like [fetchMedia] for the thumbnail; null if there is none. */
    suspend fun fetchThumbnail(chatId: String, messageId: String, destPath: String): String?

    /** Subscribes to the chat list; the first callback is a `Reset`. */
    suspend fun chatList(listener: ChatListListener): TaskHandle

    /** The timeline of one chat. Cheap; listeners are added on the result. */
    suspend fun timeline(chatId: String): Timeline

    /**
     * Sets the time zone date separators split calendar days in; null (the default) is UTC. Synchronous, never throws.
     * Set it after creating the client and again when the system time zone changes: open timelines move their date
     * separators through their listeners.
     */
    fun setLocalTimeZone(zone: LocalTimeZone?)

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
