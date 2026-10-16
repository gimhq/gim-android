package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatListDiff
import im.g.apps.core.bridge.ChatListListener
import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.ConnectionStateListener
import im.g.apps.core.bridge.DeviceInfo
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.HubInfo
import im.g.apps.core.bridge.HubListListener
import im.g.apps.core.bridge.HubState
import im.g.apps.core.bridge.LinkSession
import im.g.apps.core.bridge.LocalTimeZone
import im.g.apps.core.bridge.MediaRef
import im.g.apps.core.bridge.MembershipChangeKind
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.MessagePreview
import im.g.apps.core.bridge.NotificationContent
import im.g.apps.core.bridge.NotificationKind
import im.g.apps.core.bridge.PendingApproval
import im.g.apps.core.bridge.PushRegistration
import im.g.apps.core.bridge.ReactionGroup
import im.g.apps.core.bridge.ReplyPreview
import im.g.apps.core.bridge.SendState
import im.g.apps.core.bridge.Session
import im.g.apps.core.bridge.TaskHandle
import im.g.apps.core.bridge.Timeline
import im.g.apps.core.bridge.TimelineDiff
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.TimelineListener
import im.g.apps.core.bridge.TypingListener
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.key
import im.g.apps.core.bridge.requireValidServerUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Deterministic in-memory [GimClient] for ViewModel/UI tests, previews and the app until gim-core's
 * bindings are vendored. Follows gim-core's semantics: chat list most recently updated first, timelines
 * oldest first with a [TimelineItem.DateSeparator] before the first item of each local day (in [zone], or in the
 * zone passed to [setLocalTimeZone]). [leaveChat] only submits the leave ([ChatState.LEAVING]): the own `Left` change
 * (and [ChatState.LEFT]) follows after [OutboxDelays.deliveredMs], or on [commitLeave] when `outboxDelays` is null.
 * Calls that send to or change a chat that is not [ChatState.ACTIVE] throw [GimException.ChatNotActive].
 *
 * All state lives behind one lock and listeners are invoked synchronously on the calling thread, in
 * mutation order. Outbound messages start [SendState.Pending] and advance to Sent, Delivered and Read
 * after [outboxDelays] (run on [scope]); pass `outboxDelays = null` to drive them with [advanceOutbox].
 *
 * Identity and devices are simulated deterministically: [createIdentity] returns [fakeRecoveryCode] of the new
 * user's number, [restoreFromRecovery] accepts users added with [addRecoverableUser], and the link flow completes
 * without a second device ([waitLink] signs in as [linkedUser]; [approveLink] accepts QR strings from
 * [startLink]). Both sides' SAS are the same fixed-format digits.
 *
 * Hubs follow gim-core's hub management: a session starts with the home hub at [homeHubUrl] (hub id [fakeHubId] of
 * the URL), [addHub] lists a hub last, [removeHub] refuses the home hub ([GimException.HomeHub]), the only listed hub
 * ([GimException.LastHub]), a hub owning chats ([GimException.HubOwnsGroups], see [simulateChatOwnedBy]) unless
 * confirmed, and a non-empty outbox ([GimException.HubNotDrained]); [setPrimaryHub] needs a session on every hub
 * whose priority changes ([simulateHubSharedByOtherDevice] lists one without).
 *
 * @param clock epoch-millisecond time source for timestamps.
 * @param pageSize number of newest history events (messages, membership changes) loaded before
 *   [Timeline.paginateBackwards].
 * @param discardedLocalState value of [localStateReset].
 */
class FakeGimClient(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val outboxDelays: OutboxDelays? = OutboxDelays(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val discardedLocalState: Boolean = false,
    private val homeHubUrl: String = DEFAULT_HOME_HUB_URL,
) : GimClient {

    data class OutboxDelays(
        val sentMs: Long = 400,
        val deliveredMs: Long = 800,
        val readMs: Long = 1_500,
    )

    private class FakeChat(
        val id: String,
        val kind: ChatKind,
        var name: String,
        val members: MutableList<UserProfile>,
        val createdAt: Long,
    ) {
        var avatar: MediaRef? = null

        /** Messages and membership changes, oldest first; the newest [loaded] are in the timeline. */
        val events = mutableListOf<TimelineItem>()
        var loaded = 0
        var unread = 0

        /** Timeline items as last delivered to listeners. */
        var rendered: List<TimelineItem> = emptyList()
    }

    private data class FakeHub(
        val hubId: String,
        val url: String?,
        val state: HubState,
        val priority: Int,
        val signedIn: Boolean,
    )

    private class MediaEntry(val ref: MediaRef, val width: Int?, val height: Int?, val sourcePath: String?)

    private data class MessageKey(val chatId: String, val messageId: String)

    private inner class Registration<L>(
        val chatId: String?,
        val listener: L,
        private val regs: MutableList<Registration<L>>,
    ) : TaskHandle {
        private var finished = false

        override fun cancel() {
            synchronized(lock) {
                finished = true
                regs.remove(this)
            }
        }

        override fun isFinished(): Boolean = synchronized(lock) { finished }

        override fun close() = cancel()
    }

    private inner class FakeTimeline(private val chatId: String) : Timeline {
        override fun chatId(): String = chatId

        override suspend fun addListener(listener: TimelineListener): TaskHandle = synchronized(lock) {
            listener.onUpdate(listOf(TimelineDiff.Reset(chats[chatId]?.rendered.orEmpty())))
            register(timelineRegs, chatId, listener)
        }

        override suspend fun paginateBackwards(count: Int): Boolean = synchronized(lock) {
            if (count <= 0) throw GimException.InvalidInput("count must be positive")
            val chat = chat(chatId)
            chat.loaded += minOf(count, chat.events.size - chat.loaded)
            refreshTimeline(chat)
            chat.loaded < chat.events.size
        }

        override fun chatState(): ChatState? = synchronized(lock) { chats[chatId]?.let { state(it.id) } }
    }

    private class FakeLinkSession(val owner: FakeGimClient, val qr: String) : LinkSession {
        override fun qrString(): String = qr
    }

    private class FakePendingApproval(val owner: FakeGimClient, val sas: String, val deviceId: String) :
        PendingApproval {
        override fun sas(): String = sas

        override fun deviceId(): String = deviceId
    }

    private val lock = Any()
    private var session: Session? = null
    private val ownDevices = mutableListOf<DeviceInfo>()
    private val recoverable = HashMap<String, Pair<UserProfile, String>>()
    private var startedLink: FakeLinkSession? = null
    private var localTimeZone: LocalTimeZone? = null
    private val leaving = HashSet<String>()
    private val left = HashSet<String>()
    private var linkAwaitingConfirm = false
    private val approvals = HashSet<String>()
    private var connection = ConnectionState.OFFLINE
    private var syncing = false
    private var reachable = true
    private val directory = LinkedHashMap<String, UserProfile>()
    private val chats = LinkedHashMap<String, FakeChat>()
    private val media = HashMap<MessageKey, MediaEntry>()
    private val typingUsers = HashMap<String, List<String>>()
    private val myTyping = HashMap<String, Boolean>()
    private val outbox = LinkedHashSet<MessageKey>()
    private val doomed = HashMap<MessageKey, String>()
    private var failNextReason: String? = null
    private var nextId = 1
    private var chatList: List<ChatSummary> = emptyList()
    private val hubs = LinkedHashMap<String, FakeHub>()
    private val chatOwners = HashMap<String, String>()
    private val unverifiableHubUrls = HashSet<String>()
    private var pushRegistration: PushRegistration? = null

    private val chatListRegs = mutableListOf<Registration<ChatListListener>>()
    private val timelineRegs = mutableListOf<Registration<TimelineListener>>()
    private val connectionRegs = mutableListOf<Registration<ConnectionStateListener>>()
    private val typingRegs = mutableListOf<Registration<TypingListener>>()
    private val hubListRegs = mutableListOf<Registration<HubListListener>>()

    /** Number of live subscriptions of every kind; lets tests assert that flows close their handles. */
    val activeSubscriptions: Int
        get() = synchronized(lock) {
            chatListRegs.size + timelineRegs.size + connectionRegs.size + typingRegs.size + hubListRegs.size
        }

    // region GimClient

    override suspend fun session(): Session? = synchronized(lock) { session }

    override fun userId(): String? = synchronized(lock) { session?.userId }

    override fun localStateReset(): Boolean = discardedLocalState

    override suspend fun createIdentity(displayName: String): String = synchronized(lock) {
        requireSignedOut()
        val name = requireDisplayName(displayName)
        val number = nextId++
        signIn(Session("u-$number", "d-${nextId++}", "", name))
        fakeRecoveryCode(number)
    }

    override suspend fun restoreFromRecovery(userId: String, recoveryCode: String, revokeOthers: Boolean) {
        synchronized(lock) {
            requireSignedOut()
            val code = normalizeRecoveryCode(recoveryCode)
                ?: throw GimException.InvalidInput("recovery code: wrong length or invalid character")
            val (profile, expected) = recoverable[userId] ?: throw GimException.NotFound("device list of $userId")
            if (normalizeRecoveryCode(expected) != code) throw GimException.Crypto("recovery code does not open the backup")
            val previous = ownDevices.toList()
            signIn(Session(profile.userId, "d-${nextId++}", profile.handle, profile.displayName))
            ownDevices.addAll(0, previous.map { if (revokeOthers) it.copy(createdAtMs = 0, revoked = true) else it })
        }
    }

    override fun startLink(hubs: List<String>): LinkSession = synchronized(lock) {
        requireSignedOut()
        FakeLinkSession(this, "$LINK_PREFIX${nextId++}").also { startedLink = it }
    }

    override suspend fun waitLink(link: LinkSession): String = synchronized(lock) {
        requireSignedOut()
        if (link !is FakeLinkSession || link.owner !== this || link !== startedLink) {
            throw GimException.InvalidInput("link: unknown or already used session")
        }
        startedLink = null
        val user = linkedUser
        signIn(Session(user.userId, "d-${nextId++}", user.handle, user.displayName))
        linkAwaitingConfirm = true
        fakeSas(link.qr)
    }

    override suspend fun confirmLink(accepted: Boolean) {
        synchronized(lock) {
            if (!linkAwaitingConfirm) throw GimException.InvalidInput("no link to confirm")
            linkAwaitingConfirm = false
        }
        if (!accepted) signOut()
    }

    override suspend fun approveLink(qrString: String): PendingApproval = synchronized(lock) {
        requireSession()
        if (!qrString.startsWith(LINK_PREFIX)) throw GimException.InvalidInput("qr: not a gim link code")
        val deviceId = "d-${nextId++}"
        approvals += deviceId
        FakePendingApproval(this, fakeSas(qrString), deviceId)
    }

    override suspend fun confirmApproval(approval: PendingApproval, accepted: Boolean) = synchronized(lock) {
        requireSession()
        if (approval !is FakePendingApproval || approval.owner !== this || !approvals.remove(approval.deviceId)) {
            throw GimException.InvalidInput("approval: unknown or already confirmed")
        }
        ownDevices += DeviceInfo(approval.deviceId, if (accepted) clock() else 0, isCurrent = false, revoked = !accepted)
    }

    override suspend fun devices(): List<DeviceInfo> = synchronized(lock) {
        requireSession()
        ownDevices.toList()
    }

    override suspend fun userDevices(userId: String): List<DeviceInfo> = synchronized(lock) {
        requireSession()
        if (userId == session?.userId) return@synchronized ownDevices.map { it.copy(isCurrent = false) }
        if (directory[userId] == null) emptyList() else listOf(DeviceInfo("d-$userId", 0, isCurrent = false, revoked = false))
    }

    override suspend fun revokeDevice(deviceId: String) = synchronized(lock) {
        val me = requireSession()
        if (deviceId == me.deviceId) throw GimException.InvalidInput("cannot revoke the current device")
        val index = ownDevices.indexOfFirst { it.deviceId == deviceId }
        if (index < 0) throw GimException.NotFound("device $deviceId")
        ownDevices[index] = ownDevices[index].copy(createdAtMs = 0, revoked = true)
    }

    override suspend fun hubs(): List<HubInfo> = synchronized(lock) {
        requireSession()
        hubInfos()
    }

    override suspend fun addHub(url: String): HubInfo = synchronized(lock) {
        requireSession()
        val normalized = normalizeHubUrl(url)
        if (normalized in unverifiableHubUrls) throw GimException.HubNotVerified("$normalized did not prove its hub")
        val hubId = fakeHubId(normalized)
        val existing = hubs[hubId]
        if (existing != null && existing.state != HubState.REMOVED) {
            if (hubId != homeHubId()) {
                hubs[hubId] = existing.copy(url = normalized, state = HubState.VERIFIED, signedIn = true)
            }
        } else {
            if (listedHubs().size >= MAX_HUBS) {
                throw GimException.InvalidInput("a hosting record lists at most $MAX_HUBS hubs")
            }
            val priority = listedHubs().maxOfOrNull { it.priority + 1 } ?: 1
            hubs[hubId] = FakeHub(hubId, normalized, HubState.VERIFIED, priority, signedIn = true)
        }
        publishHubs()
        hubInfos().first { it.hubId == hubId }
    }

    override suspend fun removeHub(hubId: String, confirmOwnedGroups: Boolean) = synchronized(lock) {
        requireSession()
        val listed = listedHubs()
        val hub = listed.firstOrNull { it.hubId == hubId }
            ?: throw GimException.InvalidInput("hub $hubId is not listed by the hosting record")
        if (hubId == homeHubId()) throw GimException.HomeHub(hubId)
        if (listed.size < 2) throw GimException.LastHub(hubId)
        val owned = chats.keys.count { chatOwners[it] == hubId && it !in left }
        if (owned > 0 && !confirmOwnedGroups) throw GimException.HubOwnsGroups(hubId, owned)
        if (outbox.isNotEmpty()) {
            throw GimException.HubNotDrained(hubId, "${outbox.size} messages and 0 commits not acknowledged yet")
        }
        hubs[hubId] = hub.copy(state = HubState.REMOVED, signedIn = false)
        publishHubs()
    }

    override suspend fun setPrimaryHub(hubId: String) = synchronized(lock) {
        requireSession()
        val order = listedHubs().toMutableList()
        val position = order.indexOfFirst { it.hubId == hubId }
        if (position < 0) throw GimException.InvalidInput("hub $hubId is not listed by the hosting record")
        if (position == 0) return@synchronized
        order.add(0, order.removeAt(position))
        val changed = order.mapIndexedNotNull { priority, hub ->
            hub.takeIf { it.priority != priority }?.copy(priority = priority)
        }
        changed.firstOrNull { !it.signedIn }?.let {
            throw GimException.InvalidInput("hub ${it.hubId} is not reachable from this device; set its URL first")
        }
        changed.forEach { hubs[it.hubId] = it }
        publishHubs()
    }

    override suspend fun setDisplayName(name: String) = synchronized(lock) {
        val me = requireSession()
        updateSession(me.copy(displayName = requireDisplayName(name)))
    }

    override suspend fun claimHandle(handle: String) = synchronized(lock) {
        val me = requireSession()
        val normalized = normalizeHandle(handle)
        if (!HANDLE_PATTERN.matches(normalized)) {
            throw GimException.InvalidInput("handle: use 3-32 of a-z, 0-9, '_'")
        }
        if (directory.values.any { it.handle == normalized && it.userId != me.userId }) {
            throw GimException.Server(409, "handle taken")
        }
        updateSession(me.copy(handle = normalized))
    }

    override suspend fun releaseHandle() = synchronized(lock) {
        val me = requireSession()
        if (me.handle.isEmpty()) throw GimException.InvalidInput("no handle claimed")
        updateSession(me.copy(handle = ""))
    }

    override suspend fun signOut() = synchronized(lock) {
        val me = requireSession()
        directory.remove(me.userId)
        session = null
        ownDevices.clear()
        approvals.clear()
        linkAwaitingConfirm = false
        syncing = false
        chats.clear()
        leaving.clear()
        left.clear()
        media.clear()
        outbox.clear()
        doomed.clear()
        myTyping.clear()
        chatList = emptyList()
        hubs.clear()
        chatOwners.clear()
        pushRegistration = null
        hubListRegs.toList().forEach { it.listener.onUpdate(emptyList()) }
        chatListRegs.toList().forEach { it.listener.onUpdate(listOf(ChatListDiff.Clear)) }
        timelineRegs.toList().forEach { it.listener.onUpdate(listOf(TimelineDiff.Clear)) }
        val typingChats = typingUsers.keys.toSet()
        typingUsers.clear()
        typingRegs.toList().filter { it.chatId in typingChats }.forEach { it.listener.onUpdate(emptyList()) }
        refreshConnection()
    }

    override suspend fun registerPush(registration: PushRegistration) = synchronized(lock) {
        pushRegistration = validatePushRegistration(registration)
    }

    override suspend fun unregisterPush() = synchronized(lock) {
        pushRegistration = null
    }

    override suspend fun processPush(payload: String): NotificationContent {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw GimException.InvalidInput("payload: not a JSON object")
        }
        pushContent?.let { return it(payload) }
        return NotificationContent(
            chatId = null,
            chatTitle = null,
            senderUserId = null,
            senderDisplayName = null,
            body = GENERIC_NOTIFICATION_BODY,
            messageId = null,
            collapseId = null,
            kind = NotificationKind.GENERIC,
            shouldDisplay = true,
            timestamp = clock(),
        )
    }

    override suspend fun startSync() = synchronized(lock) {
        requireSession()
        syncing = true
        refreshConnection()
    }

    override suspend fun stopSync() = synchronized(lock) {
        syncing = false
        refreshConnection()
    }

    /**
     * Like gim-core: becoming unreachable changes nothing by itself; becoming reachable again reconnects at
     * once if sync is running and not connected (e.g. after [setConnection] to OFFLINE). The fake outbox never
     * stalls, so there are no pending sends to retry.
     */
    override fun setNetworkReachable(reachable: Boolean) = synchronized(lock) {
        val was = this.reachable
        this.reachable = reachable
        if (reachable && !was && syncing && session != null && connection != ConnectionState.CONNECTED) {
            goOnline()
        }
    }

    override suspend fun findUser(handle: String): UserProfile? = synchronized(lock) {
        requireSession()
        val normalized = normalizeHandle(handle)
        directory.values.firstOrNull { it.handle == normalized }
    }

    override suspend fun createDirectChat(userId: String): String = synchronized(lock) {
        val me = me()
        if (userId == me.userId) throw GimException.InvalidInput("cannot chat with yourself")
        val other = user(userId)
        chats.values.firstOrNull { it.kind == ChatKind.DIRECT && it.members.any { m -> m.userId == userId } }
            ?.let { return@synchronized it.id }
        val chat = FakeChat("chat-${nextId++}", ChatKind.DIRECT, other.displayName, mutableListOf(me, other), clock())
        chats[chat.id] = chat
        publishChat(chat.id)
        chat.id
    }

    override suspend fun createGroupChat(name: String, memberUserIds: List<String>): String = synchronized(lock) {
        val me = me()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw GimException.InvalidInput("name must not be blank")
        val invitees = memberUserIds.filter { it != me.userId }.distinct()
        val others = invitees.mapNotNull { directory[it] }
        if (invitees.isNotEmpty() && others.isEmpty()) throw GimException.NotFound("users: none of the invitees exist")
        val chat = FakeChat("chat-${nextId++}", ChatKind.GROUP, trimmed, (listOf(me) + others).toMutableList(), clock())
        others.forEach { chat.events += membership(it, MembershipChangeKind.ADDED, actor = me) }
        chat.loaded = chat.events.size
        chat.rendered = render(chat)
        chats[chat.id] = chat
        publishChat(chat.id)
        chat.id
    }

    override suspend fun addMembers(chatId: String, userIds: List<String>) = synchronized(lock) {
        val me = me()
        val chat = groupChat(chatId)
        requireActive(chatId)
        if (userIds.isEmpty()) throw GimException.InvalidInput("userIds must not be empty")
        val users = userIds.distinct().mapNotNull { directory[it] }
        if (users.isEmpty()) throw GimException.NotFound("users: none of the users exist")
        users.firstOrNull { u -> chat.members.any { it.userId == u.userId } }
            ?.let { throw GimException.InvalidInput("${it.userId} is already a member") }
        users.forEach {
            chat.members += it
            appendEvent(chat, membership(it, MembershipChangeKind.ADDED, actor = me))
        }
        refreshTimeline(chat)
        publishChat(chat.id)
    }

    override suspend fun removeMember(chatId: String, userId: String) = synchronized(lock) {
        val me = me()
        val chat = groupChat(chatId)
        requireActive(chatId)
        if (userId == me.userId) throw GimException.InvalidInput("use leaveChat to leave")
        val member = chat.members.firstOrNull { it.userId == userId } ?: throw GimException.NotFound("member $userId")
        chat.members.remove(member)
        appendEvent(chat, membership(member, MembershipChangeKind.REMOVED, actor = me))
        refreshTimeline(chat)
        publishChat(chat.id)
    }

    override suspend fun leaveChat(chatId: String) {
        synchronized(lock) {
            me()
            groupChat(chatId)
            if (chatId in left) throw GimException.ChatNotActive(chatId, ChatState.LEFT)
            leaving += chatId
            publishChat(chatId)
        }
        val delays = outboxDelays ?: return
        scope.launch {
            delay(delays.deliveredMs)
            commitLeave(chatId)
        }
    }

    override suspend fun setGroupInfo(chatId: String, name: String?, avatarFilePath: String?) = synchronized(lock) {
        me()
        val chat = groupChat(chatId)
        requireActive(chatId)
        if (name == null && avatarFilePath == null) throw GimException.InvalidInput("nothing to change")
        val trimmed = name?.trim()
        if (trimmed != null && trimmed.isEmpty()) throw GimException.InvalidInput("name must not be blank")
        val avatarFile = avatarFilePath?.let(::File)
        if (avatarFile != null && !avatarFile.isFile) throw GimException.InvalidInput("avatar file not found")
        if (trimmed != null) chat.name = trimmed
        if (avatarFile != null) {
            val ref = MediaRef(chatId, "avatar-${nextId++}", imageMime(avatarFile), avatarFile.length(), hasThumbnail = true)
            media[MessageKey(chatId, ref.messageId)] = MediaEntry(ref, null, null, avatarFile.absolutePath)
            chat.avatar = ref
        }
        publishChat(chatId)
    }

    override suspend fun sendText(chatId: String, body: String, replyTo: String?): String = synchronized(lock) {
        if (body.isBlank()) throw GimException.InvalidInput("body must not be blank")
        enqueue(chatId, replyTo) { MessageContent.Text(body) }
    }

    override suspend fun sendMedia(
        chatId: String,
        filePath: String,
        mime: String,
        caption: String?,
        replyTo: String?,
    ): String = synchronized(lock) {
        val file = File(filePath)
        if (!file.isFile) throw GimException.InvalidInput("file not found: $filePath")
        val isImage = mime.startsWith("image/")
        enqueue(chatId, replyTo) { messageId ->
            val ref = MediaRef(chatId, messageId, mime, file.length(), hasThumbnail = isImage)
            media[MessageKey(chatId, messageId)] = MediaEntry(ref, null, null, file.absolutePath)
            if (isImage) MessageContent.Image(ref, null, null, caption) else MessageContent.File(ref, file.name, caption)
        }
    }

    override suspend fun editMessage(chatId: String, messageId: String, newBody: String) = synchronized(lock) {
        me()
        requireActive(chatId)
        if (newBody.isBlank()) throw GimException.InvalidInput("newBody must not be blank")
        updateMessage(chatId, messageId) { message ->
            if (!message.isOwn || message.content !is MessageContent.Text) {
                throw GimException.InvalidInput("only own text messages can be edited")
            }
            message.copy(content = MessageContent.Text(newBody), edited = true)
        }
    }

    override suspend fun deleteMessage(chatId: String, messageId: String) = synchronized(lock) {
        me()
        requireActive(chatId)
        updateMessage(chatId, messageId) { message ->
            if (!message.isOwn) throw GimException.InvalidInput("only own messages can be deleted")
            message.copy(content = MessageContent.Deleted, reactions = emptyList())
        }
    }

    override suspend fun react(chatId: String, messageId: String, emoji: String) = synchronized(lock) {
        me()
        requireActive(chatId)
        if (emoji.isBlank()) throw GimException.InvalidInput("emoji must not be blank")
        updateMessage(chatId, messageId) { message ->
            requireNotDeleted(message)
            val existing = message.reactions.firstOrNull { it.emoji == emoji }
            val reactions = when {
                existing == null -> message.reactions + ReactionGroup(emoji, 1, own = true)
                existing.own -> message.reactions
                else -> message.reactions.map { if (it == existing) it.copy(count = it.count + 1, own = true) else it }
            }
            message.copy(reactions = reactions)
        }
    }

    override suspend fun unreact(chatId: String, messageId: String, emoji: String) = synchronized(lock) {
        me()
        requireActive(chatId)
        updateMessage(chatId, messageId) { message ->
            requireNotDeleted(message)
            val existing = message.reactions.firstOrNull { it.emoji == emoji && it.own }
            val reactions = when {
                existing == null -> message.reactions
                existing.count <= 1 -> message.reactions - existing
                else -> message.reactions.map { if (it == existing) it.copy(count = it.count - 1, own = false) else it }
            }
            message.copy(reactions = reactions)
        }
    }

    override suspend fun retrySend(chatId: String, messageId: String) = synchronized(lock) {
        me()
        requireActive(chatId)
        updateMessage(chatId, messageId) { message ->
            if (message.sendState !is SendState.Failed) throw GimException.InvalidInput("message is not failed")
            message.copy(sendState = SendState.Pending)
        }
        startDelivery(MessageKey(chatId, messageId))
    }

    override suspend fun cancelSend(chatId: String, messageId: String) = synchronized(lock) {
        me()
        val chat = chat(chatId)
        val index = messageIndex(chat, messageId)
        val message = (chat.events[index] as TimelineItem.Message).item
        if (message.sendState != SendState.Pending && message.sendState !is SendState.Failed) {
            throw GimException.InvalidInput("message was already sent")
        }
        val key = MessageKey(chatId, messageId)
        outbox.remove(key)
        doomed.remove(key)
        media.remove(key)
        if (index >= chat.events.size - chat.loaded) chat.loaded--
        chat.events.removeAt(index)
        refreshTimeline(chat)
        publishChat(chatId)
    }

    override suspend fun markRead(chatId: String, upToMessageId: String) = synchronized(lock) {
        me()
        val chat = chat(chatId)
        val index = messageIndex(chat, upToMessageId)
        chat.unread = chat.events.drop(index + 1).count { it is TimelineItem.Message && !it.item.isOwn }
        publishChat(chatId)
    }

    override suspend fun setTyping(chatId: String, active: Boolean) = synchronized(lock) {
        me()
        chat(chatId)
        myTyping[chatId] = active
    }

    override suspend fun stopTyping(chatId: String) = setTyping(chatId, false)

    override suspend fun fetchMedia(chatId: String, messageId: String, destPath: String): String = synchronized(lock) {
        me()
        val entry = mediaEntry(chatId, messageId)
        val target = File(destPath).absoluteFile
        target.parentFile?.mkdirs()
        val source = entry.sourcePath?.let(::File)
        when {
            source != null && source.isFile -> source.copyTo(target, overwrite = true)
            entry.ref.mime.startsWith("image/") ->
                writePlaceholderPng(target, entry.width ?: 4, entry.height ?: 3, messageId.hashCode())
            else -> target.writeText("Placeholder for $messageId\n")
        }
        target.path
    }

    override suspend fun fetchThumbnail(chatId: String, messageId: String, destPath: String): String? =
        synchronized(lock) {
            me()
            val entry = mediaEntry(chatId, messageId)
            if (!entry.ref.hasThumbnail) return@synchronized null
            val target = File(destPath).absoluteFile
            target.parentFile?.mkdirs()
            writePlaceholderPng(target, entry.width ?: 4, entry.height ?: 3, messageId.hashCode())
            target.path
        }

    override suspend fun chatList(listener: ChatListListener): TaskHandle = synchronized(lock) {
        listener.onUpdate(listOf(ChatListDiff.Reset(chatList)))
        register(chatListRegs, null, listener)
    }

    override fun setLocalTimeZone(zone: LocalTimeZone?) = synchronized(lock) {
        localTimeZone = zone
        chats.values.forEach(::refreshTimeline)
    }

    override suspend fun chatState(chatId: String): ChatState = synchronized(lock) {
        me()
        chat(chatId)
        state(chatId)
    }

    override suspend fun timeline(chatId: String): Timeline = synchronized(lock) {
        me()
        chat(chatId)
        FakeTimeline(chatId)
    }

    override suspend fun connectionState(listener: ConnectionStateListener): TaskHandle = synchronized(lock) {
        listener.onChange(connection)
        register(connectionRegs, null, listener)
    }

    override suspend fun typing(chatId: String, listener: TypingListener): TaskHandle = synchronized(lock) {
        listener.onUpdate(typingUsers[chatId].orEmpty())
        register(typingRegs, chatId, listener)
    }

    override suspend fun hubList(listener: HubListListener): TaskHandle = synchronized(lock) {
        requireSession()
        listener.onUpdate(hubInfos())
        register(hubListRegs, null, listener)
    }

    override suspend fun chatMembers(chatId: String): List<UserProfile> = synchronized(lock) {
        me()
        chat(chatId).members.toList()
    }

    // endregion

    // region Test controls

    /** Adds a user that [findUser] can discover. */
    fun addDirectoryUser(user: UserProfile) = synchronized(lock) { directory[user.userId] = user }

    /** Lets [restoreFromRecovery] sign in as [user] with [recoveryCode]; [devices] lists earlier devices. */
    fun addRecoverableUser(user: UserProfile, recoveryCode: String, devices: List<DeviceInfo> = emptyList()) =
        synchronized(lock) {
            recoverable[user.userId] = user to recoveryCode
            if (session == null) {
                ownDevices.clear()
                ownDevices += devices
            }
        }

    /** The registration stored by [registerPush]; null after [unregisterPush] and [signOut]. */
    val registeredPush: PushRegistration?
        get() = synchronized(lock) { pushRegistration }

    /**
     * What [processPush] returns for a JSON object payload; null (the default) returns gim-core's fallback,
     * [NotificationKind.GENERIC] "New message" to display.
     */
    @Volatile var pushContent: ((payload: String) -> NotificationContent)? = null

    /** The account [waitLink] signs in as. */
    @Volatile var linkedUser: UserProfile = UserProfile("u-linked", "linked", "Linked Account")

    /** Another member commits the pending [leaveChat] of [chatId]: the own `Left` change appears. */
    fun commitLeave(chatId: String) = synchronized(lock) {
        if (!leaving.remove(chatId)) return@synchronized
        val chat = chats[chatId] ?: return@synchronized
        val me = me()
        chat.members.removeAll { it.userId == me.userId }
        left += chatId
        appendEvent(chat, membership(me, MembershipChangeKind.LEFT, actor = me))
        refreshTimeline(chat)
        publishChat(chatId)
    }

    /** Member [by] removes the signed-in user from group [chatId]: the chat becomes [ChatState.LEFT]. */
    fun simulateRemovedBy(chatId: String, by: String) = synchronized(lock) {
        val chat = groupChat(chatId)
        val actor = member(chat, by)
        val me = me()
        if (!chat.members.removeAll { it.userId == me.userId }) return@synchronized
        leaving -= chatId
        left += chatId
        appendEvent(chat, membership(me, MembershipChangeKind.REMOVED, actor = actor))
        refreshTimeline(chat)
        publishChat(chatId)
    }

    /**
     * Another own device shared [url] for the listed hub [hubId]: it is listed last, [HubState.UNVERIFIED] and without
     * a session here until [addHub] with its URL.
     */
    fun simulateHubSharedByOtherDevice(hubId: String, url: String?) = synchronized(lock) {
        requireSession()
        val priority = listedHubs().maxOfOrNull { it.priority + 1 } ?: 1
        hubs[hubId] = FakeHub(hubId, url, HubState.UNVERIFIED, priority, signedIn = false)
        publishHubs()
    }

    /** Another own device removed the hub [hubId] from the hosting record (also possible for this client's home hub). */
    fun simulateHubRemovedByOtherDevice(hubId: String) = synchronized(lock) {
        val hub = hubs[hubId] ?: return@synchronized
        hubs[hubId] = hub.copy(state = HubState.REMOVED, signedIn = false)
        publishHubs()
    }

    /** Makes the hub [hubId] the owner of [chatId] (as if another own device created it there). */
    fun simulateChatOwnedBy(chatId: String, hubId: String) = synchronized(lock) {
        chat(chatId)
        chatOwners[chatId] = hubId
    }

    /** Makes [addHub] of [url] fail with [GimException.HubNotVerified], as for a relay or a wrong public URL. */
    fun rejectHubUrl(url: String) = synchronized(lock) { unverifiableHubUrls += normalizeHubUrl(url) }

    /** Makes the next outbound send (or retry) end in [SendState.Failed] with [reason]. */
    fun failNextSend(reason: String = "Simulated send failure") = synchronized(lock) { failNextReason = reason }

    /** Advances every in-flight outbound message by one send state (manual outbox mode). */
    fun advanceOutbox() = synchronized(lock) { outbox.toList().forEach(::step) }

    /** Overrides the connection state, as if the sync loop changed it. */
    fun setConnection(state: ConnectionState) = synchronized(lock) { updateConnection(state) }

    /** Delivers an incoming text message from [from] (a member) into [chatId]; returns its message id. */
    fun simulateIncoming(chatId: String, text: String, from: String, replyTo: String? = null): String =
        synchronized(lock) {
            val chat = chat(chatId)
            val sender = member(chat, from)
            val reply = replyTo?.let { replyPreview(chat, it) }
            val id = "m-${nextId++}"
            val item = MessageItem(
                id, chatId, sender, isOwn = false, sentAtMs = clock(), content = MessageContent.Text(text),
                replyTo = reply, reactions = emptyList(), edited = false, sendState = null,
            )
            appendEvent(chat, TimelineItem.Message(item))
            chat.unread++
            refreshTimeline(chat)
            publishChat(chatId)
            id
        }

    /** Marks [userId] (a member) as typing or not in [chatId] and notifies that chat's typing listeners. */
    fun simulateTyping(chatId: String, userId: String, typing: Boolean) = synchronized(lock) {
        member(chat(chatId), userId)
        val current = typingUsers[chatId].orEmpty()
        val updated = if (typing) current.filter { it != userId } + userId else current.filter { it != userId }
        if (updated == current) return@synchronized
        if (updated.isEmpty()) typingUsers.remove(chatId) else typingUsers[chatId] = updated
        typingRegs.toList().filter { it.chatId == chatId }.forEach { it.listener.onUpdate(updated) }
    }

    /** Last value passed to [setTyping] for [chatId]. */
    fun isTyping(chatId: String): Boolean = synchronized(lock) { myTyping[chatId] == true }

    // endregion

    // region Seeding (used by DemoData)

    internal fun seedSession(session: Session) = synchronized(lock) {
        this.session = session
        hubs.clear()
        val home = normalizeHubUrl(homeHubUrl)
        hubs[fakeHubId(home)] = FakeHub(fakeHubId(home), home, HubState.VERIFIED, 0, signedIn = true)
        publishHubs()
        directory[session.userId] = UserProfile(session.userId, session.handle, session.displayName)
        ownDevices.clear()
        ownDevices += DeviceInfo(session.deviceId, clock(), isCurrent = true, revoked = false)
    }

    internal fun seedMedia(ref: MediaRef, width: Int?, height: Int?) = synchronized(lock) {
        media[MessageKey(ref.chatId, ref.messageId)] = MediaEntry(ref, width, height, null)
    }

    /** Adds a chat whose [events] (messages and membership changes) are ordered oldest first. */
    internal fun seedChat(
        id: String,
        kind: ChatKind,
        name: String,
        members: List<UserProfile>,
        createdAt: Long,
        events: List<TimelineItem>,
        unread: Int,
    ) = synchronized(lock) {
        val chat = FakeChat(id, kind, name, members.toMutableList(), createdAt)
        chat.events.addAll(events)
        chat.loaded = minOf(pageSize, events.size)
        chat.unread = unread
        chat.rendered = render(chat)
        chats[id] = chat
        publishChat(id)
    }

    // endregion

    // region Internals (callers hold the lock)

    private fun <L> register(regs: MutableList<Registration<L>>, chatId: String?, listener: L): TaskHandle =
        Registration(chatId, listener, regs).also { regs.add(it) }

    private fun requireSession(): Session = session ?: throw GimException.NotRegistered()

    private fun requireSignedOut() {
        if (session != null) throw GimException.InvalidInput("already signed in")
    }

    /** gim-core's `PushRegistration` checks. */
    private fun validatePushRegistration(registration: PushRegistration): PushRegistration = registration.also {
        fun invalid(field: String): Nothing = throw GimException.InvalidInput("push registration: invalid $field")
        if (!PUSH_PROVIDER.matches(it.provider)) invalid("provider")
        val tokenBytes = it.token.toByteArray(Charsets.UTF_8).size
        if (tokenBytes !in 1..MAX_PUSH_TOKEN_BYTES || it.token.any(Char::isISOControl)) invalid("token")
        if (it.appId != null && !PUSH_APP_ID.matches(it.appId)) invalid("app_id")
        fun isKey(value: String?) = value == null || (value.length <= MAX_PUSH_KEY_LENGTH && PUSH_KEY.matches(value))
        if (!isKey(it.p256dh)) invalid("p256dh")
        if (!isKey(it.auth)) invalid("auth")
    }

    private fun requireDisplayName(value: String): String =
        value.trim().ifEmpty { throw GimException.InvalidInput("displayName must not be blank") }

    /** Signs in as a fresh device of [session]'s user (no chats, empty outbox). */
    private fun signIn(session: Session) {
        seedSession(session)
        refreshConnection()
    }

    private fun updateSession(updated: Session) {
        session = updated
        directory[updated.userId] = UserProfile(updated.userId, updated.handle, updated.displayName)
    }

    private fun homeHubId(): String = fakeHubId(homeHubUrl)

    /** Listed (not removed) hubs, primary first. */
    private fun listedHubs(): List<FakeHub> =
        hubs.values.filter { it.state != HubState.REMOVED }.sortedWith(HUB_ORDER)

    /** Like gim-core's `hubs()`: primary first (priority, then hub id), removed ones last. */
    private fun hubInfos(): List<HubInfo> {
        val listed = listedHubs()
        val removed = hubs.values.filter { it.state == HubState.REMOVED }.sortedWith(HUB_ORDER)
        val primary = listed.firstOrNull()?.hubId
        return (listed + removed).map {
            HubInfo(
                hubId = it.hubId,
                url = it.url,
                state = it.state,
                priority = it.priority,
                isPrimary = it.hubId == primary,
                isHome = it.hubId == homeHubId(),
                signedIn = it.signedIn,
            )
        }
    }

    private fun publishHubs() {
        if (hubListRegs.isEmpty()) return
        val current = hubInfos()
        hubListRegs.toList().forEach { it.listener.onUpdate(current) }
    }

    private fun me(): UserProfile = requireSession().let { UserProfile(it.userId, it.handle, it.displayName) }

    private fun user(userId: String): UserProfile = directory[userId] ?: throw GimException.NotFound("user $userId")

    private fun chat(chatId: String): FakeChat = chats[chatId] ?: throw GimException.NotFound("chat $chatId")

    private fun groupChat(chatId: String): FakeChat = chat(chatId).also {
        if (it.kind != ChatKind.GROUP) throw GimException.InvalidInput("$chatId is not a group chat")
    }

    private fun member(chat: FakeChat, userId: String): UserProfile =
        chat.members.firstOrNull { it.userId == userId } ?: throw GimException.NotFound("member $userId")

    private fun mediaEntry(chatId: String, messageId: String): MediaEntry =
        media[MessageKey(chatId, messageId)] ?: throw GimException.NotFound("media $chatId/$messageId")

    private fun messageIndex(chat: FakeChat, messageId: String): Int =
        chat.events.indexOfFirst { it is TimelineItem.Message && it.item.messageId == messageId }
            .takeIf { it >= 0 } ?: throw GimException.NotFound("message $messageId")

    private fun requireNotDeleted(message: MessageItem) {
        if (message.content == MessageContent.Deleted) throw GimException.InvalidInput("message was deleted")
    }

    private fun replyPreview(chat: FakeChat, messageId: String): ReplyPreview {
        val message = (chat.events[messageIndex(chat, messageId)] as TimelineItem.Message).item
        return ReplyPreview(message.messageId, message.sender.displayName, previewText(message.content))
    }

    private fun membership(user: UserProfile, change: MembershipChangeKind, actor: UserProfile?) =
        TimelineItem.MembershipChange(user.displayName, change, clock(), user.userId, actor?.userId)

    private fun state(chatId: String): ChatState = when (chatId) {
        in left -> ChatState.LEFT
        in leaving -> ChatState.LEAVING
        else -> ChatState.ACTIVE
    }

    private fun requireActive(chatId: String) {
        val state = state(chatId)
        if (state != ChatState.ACTIVE) throw GimException.ChatNotActive(chatId, state)
    }

    private fun enqueue(chatId: String, replyTo: String?, content: (messageId: String) -> MessageContent): String {
        val me = me()
        val chat = chat(chatId)
        requireActive(chatId)
        val reply = replyTo?.let { replyPreview(chat, it) }
        val id = "c-${nextId++}"
        val item = MessageItem(
            id, chatId, me, isOwn = true, sentAtMs = clock(), content = content(id), replyTo = reply,
            reactions = emptyList(), edited = false, sendState = SendState.Pending,
        )
        appendEvent(chat, TimelineItem.Message(item))
        refreshTimeline(chat)
        publishChat(chatId)
        startDelivery(MessageKey(chatId, id))
        return id
    }

    private fun updateMessage(chatId: String, messageId: String, transform: (MessageItem) -> MessageItem) {
        val chat = chat(chatId)
        val index = messageIndex(chat, messageId)
        chat.events[index] = TimelineItem.Message(transform((chat.events[index] as TimelineItem.Message).item))
        refreshTimeline(chat)
        publishChat(chatId)
    }

    private fun startDelivery(key: MessageKey) {
        failNextReason?.let {
            failNextReason = null
            doomed[key] = it
        }
        outbox.add(key)
        val delays = outboxDelays ?: return
        scope.launch {
            for (ms in listOf(delays.sentMs, delays.deliveredMs, delays.readMs)) {
                delay(ms)
                if (!synchronized(lock) { step(key) }) break
            }
        }
    }

    /** Moves [key] one send state forward; returns true while it stays in flight. */
    private fun step(key: MessageKey): Boolean {
        if (key !in outbox) return false
        val message = chats[key.chatId]?.events
            ?.firstNotNullOfOrNull { (it as? TimelineItem.Message)?.item?.takeIf { m -> m.messageId == key.messageId } }
        val failure = doomed.remove(key)
        val next = when {
            message == null -> null
            failure != null -> SendState.Failed(failure)
            message.sendState == SendState.Pending -> SendState.Sent
            message.sendState == SendState.Sent -> SendState.Delivered
            message.sendState == SendState.Delivered -> SendState.Read
            else -> null
        }
        if (next == null || next is SendState.Failed || next == SendState.Read) outbox.remove(key)
        if (next != null) updateMessage(key.chatId, key.messageId) { it.copy(sendState = next) }
        return key in outbox
    }

    private fun appendEvent(chat: FakeChat, event: TimelineItem) {
        chat.events += event
        chat.loaded++
    }

    private fun dayStart(ms: Long): Long {
        val local = localTimeZone ?: return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()
        val offsetMs = local.utcOffsetSecs(ms) * 1_000L
        return Math.floorDiv(ms + offsetMs, DAY_MS) * DAY_MS - offsetMs
    }

    /** Loaded events with a date separator before the first item of each local day. */
    private fun render(chat: FakeChat): List<TimelineItem> {
        val out = ArrayList<TimelineItem>()
        var day: Long? = null
        for (event in chat.events.subList(chat.events.size - chat.loaded, chat.events.size)) {
            val start = dayStart(eventTime(event))
            if (start != day) {
                out += TimelineItem.DateSeparator(start)
                day = start
            }
            out += event
        }
        return out
    }

    /** Re-renders [chat] and sends listeners the diffs from the previously delivered items. */
    private fun refreshTimeline(chat: FakeChat) {
        val new = render(chat)
        val diffs = timelineDiffs(chat.rendered, new)
        chat.rendered = new
        if (diffs.isEmpty()) return
        timelineRegs.toList().filter { it.chatId == chat.id }.forEach { it.listener.onUpdate(diffs) }
    }

    private fun refreshConnection() {
        when {
            !syncing || session == null -> updateConnection(ConnectionState.OFFLINE)
            connection == ConnectionState.OFFLINE -> goOnline()
        }
    }

    private fun goOnline() {
        updateConnection(ConnectionState.CONNECTING)
        updateConnection(ConnectionState.SYNCING)
        updateConnection(ConnectionState.CONNECTED)
    }

    private fun updateConnection(state: ConnectionState) {
        if (state == connection) return
        connection = state
        connectionRegs.toList().forEach { it.listener.onChange(state) }
    }

    private fun summary(chat: FakeChat): ChatSummary {
        val me = session?.userId
        val name = if (chat.kind == ChatKind.DIRECT) {
            chat.members.firstOrNull { it.userId != me }?.displayName ?: chat.name
        } else {
            chat.name
        }
        val last = chat.events.lastOrNull { it is TimelineItem.Message } as TimelineItem.Message?
        return ChatSummary(
            chatId = chat.id,
            kind = chat.kind,
            name = name,
            avatar = chat.avatar,
            lastMessage = last?.item?.let {
                MessagePreview(it.sender.displayName, previewText(it.content), it.sentAtMs, it.isOwn)
            },
            unreadCount = chat.unread,
            updatedAtMs = maxOf(chat.createdAt, chat.events.lastOrNull()?.let(::eventTime) ?: chat.createdAt),
            memberCount = chat.members.size,
            state = state(chat.id),
        )
    }

    /** Recomputes the ordered chat list and emits the minimal diff for the single changed [chatId]. */
    private fun publishChat(chatId: String) {
        val old = chatList
        val new = chats.values.map(::summary)
            .sortedWith(compareByDescending<ChatSummary> { it.updatedAtMs }.thenBy { it.chatId })
        chatList = new
        val from = old.indexOfFirst { it.chatId == chatId }
        val to = new.indexOfFirst { it.chatId == chatId }
        val diffs = when {
            from == -1 && to == -1 -> return
            to == -1 -> listOf(ChatListDiff.Remove(from))
            from == -1 -> listOf(ChatListDiff.Insert(to, new[to]))
            from == to -> if (old[from] == new[to]) return else listOf(ChatListDiff.Set(to, new[to]))
            else -> listOf(ChatListDiff.Remove(from), ChatListDiff.Insert(to, new[to]))
        }
        chatListRegs.toList().forEach { it.listener.onUpdate(diffs) }
    }

    // endregion

    companion object {
        const val DEFAULT_PAGE_SIZE = 20

        /** Home hub of a [FakeGimClient] created without a server URL. */
        const val DEFAULT_HOME_HUB_URL = "https://hub.example.eu"

        /** Body of gim-core's [NotificationKind.GENERIC] notification content. */
        const val GENERIC_NOTIFICATION_BODY = "New message"
        private val PUSH_PROVIDER = Regex("[a-z0-9._-]{1,64}")
        private val PUSH_APP_ID = Regex("[A-Za-z0-9.\\-_:]{1,255}")
        /** Base64url with up to two `=` of padding; at most [MAX_PUSH_KEY_LENGTH] characters. */
        private val PUSH_KEY = Regex("[A-Za-z0-9_-]+={0,2}")
        private const val MAX_HUBS = 16
        private const val MAX_PUSH_TOKEN_BYTES = 4096
        private const val MAX_PUSH_KEY_LENGTH = 256
        private val HUB_ORDER = compareBy<FakeHub>({ it.priority }, { it.hubId })
        private const val DAY_MS = 86_400_000L

        private val HANDLE_PATTERN = Regex("[a-z0-9_]{3,32}")
        private const val LINK_PREFIX = "gimlink:fake:"
        private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val RECOVERY_CODE_CHARS = 39

        /**
         * The recovery code [createIdentity] returns for the [number]th user: 39 Crockford base32 characters in
         * groups of four separated by `-` (the last group has three), like gim-core's.
         */
        fun fakeRecoveryCode(number: Int): String =
            (0 until RECOVERY_CODE_CHARS).map { CROCKFORD[(number * 7 + it * 11) % CROCKFORD.length] }
                .chunked(4).joinToString("-") { it.joinToString("") }

        /** Like gim-core's parser: case-insensitive, I/L -> 1, O -> 0, `-` and whitespace ignored; null if invalid. */
        private fun normalizeRecoveryCode(code: String): String? {
            val symbols = code.uppercase().filterNot { it == '-' || it.isWhitespace() }
                .replace('I', '1').replace('L', '1').replace('O', '0')
            return symbols.takeIf { it.length == RECOVERY_CODE_CHARS && it.all { c -> c in CROCKFORD } }
        }

        /** The hub id the fake gives the hub at [url]: derived from its normalized URL, stable across clients. */
        fun fakeHubId(url: String): String =
            "hub-" + normalizeHubUrl(url).substringAfter("://").replace(Regex("[^a-z0-9]+"), "-")

        /** Like gim-core: an `http(s)://host` URL, else [GimException.InvalidInput]; lowercased, no trailing `/`. */
        private fun normalizeHubUrl(url: String): String {
            try {
                requireValidServerUrl(url.trim())
            } catch (e: GimException.InvalidInput) {
                throw GimException.InvalidInput("url" + e.reason.removePrefix("server_url"))
            }
            return url.trim().trimEnd('/').lowercase()
        }

        private fun fakeSas(qr: String): String = "%03d %03d".format(Math.floorMod(qr.hashCode(), 1000), qr.length)

        private fun normalizeHandle(handle: String) = handle.trim().removePrefix("@").lowercase()

        private fun imageMime(file: File) = if (file.extension.equals("png", ignoreCase = true)) "image/png" else "image/jpeg"

        /** A registered client seeded with [DemoData]: three contacts, a direct chat and a group. */
        fun withDemoData(
            scope: CoroutineScope,
            clock: () -> Long = System::currentTimeMillis,
            zone: ZoneId = ZoneId.systemDefault(),
            outboxDelays: OutboxDelays? = OutboxDelays(),
            pageSize: Int = DEFAULT_PAGE_SIZE,
        ): FakeGimClient = FakeGimClient(scope, clock, zone, outboxDelays, pageSize).also { DemoData.seed(it, clock()) }
    }
}

/** Plain-text rendering used for chat list previews and reply quotes, as gim-core renders them. */
internal fun previewText(content: MessageContent): String = when (content) {
    is MessageContent.Text -> content.body
    is MessageContent.Image -> "Photo"
    is MessageContent.File -> "File: ${content.fileName ?: "attachment"}"
    MessageContent.Deleted -> "Message deleted"
    MessageContent.Unsupported -> "Unsupported message"
}

private fun eventTime(item: TimelineItem): Long = when (item) {
    is TimelineItem.Message -> item.item.sentAtMs
    is TimelineItem.DateSeparator -> item.dayStartMs
    is TimelineItem.MembershipChange -> item.atMs
}

/**
 * Diffs from [old] to [new] in gim-core's style: removals first, then insertions and in-place updates in
 * ascending index order. Pure tail growth is a `PushBack`/`Append`. Falls back to `Reset` if the common
 * items were reordered (never happens in the fake).
 */
internal fun timelineDiffs(old: List<TimelineItem>, new: List<TimelineItem>): List<TimelineDiff> {
    if (old == new) return emptyList()
    if (new.size > old.size && new.subList(0, old.size) == old) {
        val tail = new.subList(old.size, new.size).toList()
        return listOf(if (tail.size == 1) TimelineDiff.PushBack(tail[0]) else TimelineDiff.Append(tail))
    }
    val newKeys = new.mapTo(HashSet()) { it.key }
    val oldKeys = old.mapTo(HashSet()) { it.key }
    val diffs = mutableListOf<TimelineDiff>()
    var index = 0
    for (item in old) {
        if (item.key in newKeys) index++ else diffs += TimelineDiff.Remove(index)
    }
    val kept = old.filter { it.key in newKeys }
    var next = 0
    for ((i, item) in new.withIndex()) {
        when {
            next < kept.size && kept[next].key == item.key -> {
                if (kept[next] != item) diffs += TimelineDiff.Set(i, item)
                next++
            }
            item.key !in oldKeys -> diffs += if (i == 0) TimelineDiff.PushFront(item) else TimelineDiff.Insert(i, item)
            else -> return listOf(TimelineDiff.Reset(new))
        }
    }
    return diffs
}
