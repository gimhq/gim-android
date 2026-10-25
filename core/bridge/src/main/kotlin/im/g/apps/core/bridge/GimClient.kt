package im.g.apps.core.bridge

/**
 * Kotlin mirror of gim-core's UniFFI `GimClient` (gim-core `bindings/gim-ffi`, contract in
 * `docs/API.md` at gim-client-sdk commit 2b8b6b9).
 *
 * gim-core (Rust) owns storage, networking, sync, outbox, MLS and media crypto; this interface is the only
 * surface the UI layer sees. Features never import `im.g.gimcore`: the real adapter maps the generated
 * bindings onto these types mechanically. Until the bindings are vendored, use
 * [im.g.apps.core.bridge.fake.FakeGimClient].
 *
 * Every suspend method throws [GimException] on failure; calls that send to or change a chat that is not
 * [ChatState.ACTIVE] throw [GimException.ChatNotActive]. Sign-in on a hub whose URL does not match the URL the hub
 * proves throws [GimException.HubNotVerified]. Call suspend methods from a real dispatcher
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
 * | `HubInfo`, `HubState` | [HubInfo], [HubState] | `priority: UByte` -> `Int` |
 * | `ChatListListener`, `TimelineListener`, `ConnectionStateListener`, `TypingListener`, `HubListListener`, `LocalTimeZone` | same names | `fun interface` |
 * | `ChatListDiff`, `TimelineDiff` | [ChatListDiff], [TimelineDiff] | `index`/`length: UInt` -> `Int` |
 * | `Session`, `UserProfile`, `ChatKind`, `ChatState`, `ChatSummary`, `MessagePreview` | same names | `UInt` -> `Int`, `ULong` -> `Long`; `ChatSummary.mutedUntilMs` saturates (`mutedForever()` = `u64::MAX` -> [Long.MAX_VALUE]) |
 * | `TimelineItem`, `MembershipChangeKind`, `MessageItem`, `MessageContent` | same names | `ULong` -> `Long`, `UInt?` -> `Int?` |
 * | `MediaRef`, `ReplyPreview`, `ReactionGroup`, `SendState`, `ConnectionState` | same names | `size: ULong` -> `Long`, `count: UInt` -> `Int` |
 * | `PreviewKind` (`MessagePreview.kind`, `ReplyPreview.kind`) | [PreviewKind] | `Text`, `Photo`, `File(fileName)`, `Deleted`; `text` stays English, localized UI renders `kind` |
 * | `ClientConfig`, `ClientMode` | [ClientConfig], [ClientMode] | `mode: ClientMode?` -> non-null, default [ClientMode.APP] |
 * | `PrivacySettings`, `RecoveryRotation`, `ReportReason` | same names | |
 * | `mutedForever()` | [GimClient.mutedForever] | `u64::MAX` -> [Long.MAX_VALUE] |
 * | `formatRecoveryKit`, `parseRecoveryKit`, `recoveryKeyEnding`, `matchesRecoveryKeyEnding`, `RecoveryKitLabels`, `ParsedRecoveryKit` | [RecoveryKitCodec] (`format`, `parse`, `keyEnding`, `matchesKeyEnding`), [RecoveryKitLabels], [ParsedRecoveryKit] | top-level functions behind an interface |
 * | `PushRegistration`, `NotificationKind`, `NotificationContent` | same names | `timestamp: ULong` -> `Long` |
 * | `GimException.<Variant>` | [GimException]`.<Variant>` | `status: UShort` -> `Int`, `retryAfterSecs: ULong` -> `Long`, `groupCount: UInt` -> `Int` |
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

    /**
     * Seals the root key with a new recovery code and replaces the backup on the home hub (required, else the call
     * fails and nothing changes), then on every other listed hub this device reaches. The old code keeps working on
     * the hubs in [RecoveryRotation.unsyncedHubIds]. [GimException.NotRegistered] without a session.
     */
    suspend fun rotateRecoveryCode(): RecoveryRotation

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

    /**
     * The hubs of the user's hosting record known to this device, primary first (priority, then hub id), including
     * removed ones. With a single hub: the home hub.
     */
    suspend fun hubs(): List<HubInfo>

    /**
     * Adds the hub at [url] (entered by the user on this device) to the hosting record, listed last (least
     * preferred), and returns it; adding a listed hub again only records [url] for it on this device.
     * [GimException.InvalidInput] for a URL that is not `http(s)://host…` or does not serve a gim hub,
     * [GimException.Network] if it cannot be reached, [GimException.HubNotVerified] if it does not prove the hub at
     * that URL.
     */
    suspend fun addHub(url: String): HubInfo

    /**
     * Removes a hub from the hosting record. [GimException.HomeHub] for the home hub, [GimException.LastHub] for the
     * only listed hub, [GimException.HubOwnsGroups] if the hub owns chats of this user unless [confirmOwnedGroups],
     * [GimException.HubNotDrained] while something sent through it is not settled, [GimException.InvalidInput] for a
     * hub that is not listed.
     */
    suspend fun removeHub(hubId: String, confirmOwnedGroups: Boolean)

    /**
     * Makes a listed hub the primary one (priority 0, the others follow in their order); nothing happens for the
     * current primary. Every hub whose priority changes must be reachable from this device ([HubInfo.signedIn]),
     * else [GimException.InvalidInput], as for a hub that is not listed.
     */
    suspend fun setPrimaryHub(hubId: String)

    /** Changes the display name; [GimException.InvalidInput] if empty. */
    suspend fun setDisplayName(name: String)

    /** The signed-in user's name, handle and [UserProfile.hasAvatar], from local state; null without a session. */
    suspend fun ownProfile(): UserProfile?

    /**
     * Sets the profile photo from [filePath] ([mime] its type); crop and scale it first, every member of every chat
     * downloads it. It is encrypted and uploaded before this returns, then synced and announced like the display name.
     */
    suspend fun setAvatar(filePath: String, mime: String)

    /** Removes the profile photo. */
    suspend fun removeAvatar()

    /** Claims a handle in the hub directory; [GimException.Server] with status 409 if it is taken. */
    suspend fun claimHandle(handle: String)

    /** Releases the claimed handle; [GimException.InvalidInput] if none is claimed. */
    suspend fun releaseHandle()

    /**
     * Removes the push registration from the hubs (best effort, a few seconds at most), stops sync and deletes the
     * session, local data and keystore secrets. Create a new client afterwards.
     */
    suspend fun signOut()

    /**
     * Deletes the account for good: its data on the keep server (if configured), leaves every chat, deletes the user
     * on the home hub and then on the other hubs, and wipes this device like [signOut]. Returns the other hubs that
     * could not be reached. If the keep server or the home hub step fails, nothing local is deleted and the call can be
     * repeated. Create a new client afterwards.
     */
    suspend fun deleteAccount(): List<String>

    /**
     * Sends a report about [userId] with up to 50 [messageIds] from local history (text or media caption) and an
     * optional [note] (at most 1000 characters) to the keep server's moderators, in plaintext; returns the report id.
     * It does not block: offer [blockUser] alongside. [GimException.InvalidInput] for the signed-in user, too many
     * messages, a long note or no keep server configured; [GimException.NotFound] for an unknown message id.
     */
    suspend fun reportUser(userId: String, reason: ReportReason, note: String?, messageIds: List<String>): String

    /**
     * Registers this device for push on every hub of the user and keeps the registration on the device; gim-core
     * sends it again by itself to hubs added, re-verified or reconnected later. Call it with every new platform
     * token; before onboarding it is only stored. [GimException.InvalidInput] for a malformed field,
     * [GimException.Server] (400) if a hub rejects it.
     */
    suspend fun registerPush(registration: PushRegistration)

    /**
     * Removes the push registration from every hub, then from this device; throws the first hub failure, the local
     * copy is removed anyway. [signOut] does this too (best effort).
     */
    suspend fun unregisterPush()

    /**
     * Turns a received push data payload (the JSON object as delivered) into notification content, in at most about
     * 15 s. [GimException.InvalidInput] only if [payload] is not a JSON object; content that cannot be read comes back
     * as [NotificationKind.GENERIC] "New message" with [NotificationContent.shouldDisplay] set. Show nothing unless
     * [NotificationContent.shouldDisplay].
     */
    suspend fun processPush(payload: String): NotificationContent

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

    /** The profile photo of a user (the signed-in user included) at [destPath]; [GimException.NotFound] if none. */
    suspend fun fetchUserAvatar(userId: String, destPath: String): String

    /** The avatar of a group chat set with [setGroupInfo] at [destPath]; [GimException.NotFound] if none. */
    suspend fun fetchChatAvatar(chatId: String, destPath: String): String

    /** The privacy settings; all on and empty by default. */
    suspend fun privacy(): PrivacySettings

    /** Off: [markRead] sends no receipt and others' read receipts are not shown ([SendState] stops at Delivered). */
    suspend fun setReadReceipts(enabled: Boolean)

    /** Off: [setTyping] sends nothing and others' typing is not shown. */
    suspend fun setTypingIndicators(enabled: Boolean)

    /**
     * Blocks a user on this user's devices: their messages, reactions, receipts and typing are dropped and their
     * invites refused; creating a chat with them or adding them fails with [GimException.InvalidInput]. Existing chats
     * stay; a direct chat with them is [ChatSummary.blocked]. [GimException.InvalidInput] for the signed-in user.
     */
    suspend fun blockUser(userId: String)

    /** Lets new messages of [userId] through again; dropped ones do not come back. */
    suspend fun unblockUser(userId: String)

    /**
     * Mutes [chatId] until [mutedUntilMs] (Unix ms; [mutedForever] for good, 0 to unmute): [processPush] then returns
     * `shouldDisplay = false` for it. [GimException.NotFound] for an unknown chat.
     */
    suspend fun setChatMuted(chatId: String, mutedUntilMs: Long)

    /** The [ChatSummary.mutedUntilMs] of a chat muted until it is unmuted ([Long.MAX_VALUE]). Synchronous. */
    fun mutedForever(): Long

    /** Pinned chats come first in the chat list. [GimException.NotFound] for an unknown chat. */
    suspend fun setChatPinned(chatId: String, pinned: Boolean)

    /** Only sets [ChatSummary.archived]; a new message does not unarchive. [GimException.NotFound] for an unknown chat. */
    suspend fun setChatArchived(chatId: String, archived: Boolean)

    /** Subscribes to the chat list (pinned chats first, then the most recently updated); the first callback is a `Reset`. */
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

    /** Subscribes to the user's hubs; the first callback carries the current [hubs]. */
    suspend fun hubList(listener: HubListListener): TaskHandle
}

/**
 * Creates a [GimClient]; mirrors gim-core's `createClient`. Throws [GimException.InvalidInput] unless
 * [ClientConfig.serverUrl] is an `http://` or `https://` URL with a host.
 */
fun interface GimClientFactory {
    suspend fun create(config: ClientConfig, hooks: PlatformHooks): GimClient
}
