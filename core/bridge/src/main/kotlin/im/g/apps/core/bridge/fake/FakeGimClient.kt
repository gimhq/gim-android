package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatListDiff
import im.g.apps.core.bridge.ChatListListener
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.ConnectionStateListener
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.MediaRef
import im.g.apps.core.bridge.MembershipChangeKind
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.MessagePreview
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Deterministic in-memory [GimClient] for ViewModel/UI tests, previews and the app until gim-core's
 * bindings are vendored. Follows gim-core's semantics: chat list most recently updated first, timelines
 * oldest first with a [TimelineItem.DateSeparator] before the first item of each local day (in [zone]).
 *
 * All state lives behind one lock and listeners are invoked synchronously on the calling thread, in
 * mutation order. Outbound messages start [SendState.Pending] and advance to Sent, Delivered and Read
 * after [outboxDelays] (run on [scope]); pass `outboxDelays = null` to drive them with [advanceOutbox].
 *
 * @param clock epoch-millisecond time source for timestamps.
 * @param pageSize number of newest history events (messages, membership changes) loaded before
 *   [Timeline.paginateBackwards].
 */
class FakeGimClient(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val outboxDelays: OutboxDelays? = OutboxDelays(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
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
    }

    private val lock = Any()
    private var session: Session? = null
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

    private val chatListRegs = mutableListOf<Registration<ChatListListener>>()
    private val timelineRegs = mutableListOf<Registration<TimelineListener>>()
    private val connectionRegs = mutableListOf<Registration<ConnectionStateListener>>()
    private val typingRegs = mutableListOf<Registration<TypingListener>>()

    /** Number of live subscriptions of every kind; lets tests assert that flows close their handles. */
    val activeSubscriptions: Int
        get() = synchronized(lock) { chatListRegs.size + timelineRegs.size + connectionRegs.size + typingRegs.size }

    // region GimClient

    override suspend fun session(): Session? = synchronized(lock) { session }

    override suspend fun register(handle: String, displayName: String): Session = synchronized(lock) {
        if (session != null) throw GimException.InvalidInput("already registered")
        val normalized = normalizeHandle(handle)
        if (!HANDLE_PATTERN.matches(normalized)) {
            throw GimException.InvalidInput("handle: use 3-32 of a-z, 0-9, '.', '_', '-'")
        }
        val name = displayName.trim()
        if (name.isEmpty()) throw GimException.InvalidInput("displayName must not be blank")
        if (directory.values.any { it.handle == normalized }) throw GimException.Server(409, "handle taken")
        Session("u-$normalized", "d-${nextId++}", normalized, name).also(::seedSession)
    }

    override suspend fun signOut() = synchronized(lock) {
        val me = requireSession()
        directory.remove(me.userId)
        session = null
        syncing = false
        chats.clear()
        media.clear()
        outbox.clear()
        doomed.clear()
        myTyping.clear()
        chatList = emptyList()
        chatListRegs.toList().forEach { it.listener.onUpdate(listOf(ChatListDiff.Clear)) }
        timelineRegs.toList().forEach { it.listener.onUpdate(listOf(TimelineDiff.Clear)) }
        val typingChats = typingUsers.keys.toSet()
        typingUsers.clear()
        typingRegs.toList().filter { it.chatId in typingChats }.forEach { it.listener.onUpdate(emptyList()) }
        refreshConnection()
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

    override fun setNetworkReachable(reachable: Boolean) = synchronized(lock) {
        this.reachable = reachable
        refreshConnection()
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
        val others = memberUserIds.filter { it != me.userId }.distinct().map(::user)
        val chat = FakeChat("chat-${nextId++}", ChatKind.GROUP, trimmed, (listOf(me) + others).toMutableList(), clock())
        others.forEach { chat.events += membership(it, MembershipChangeKind.ADDED) }
        chat.loaded = chat.events.size
        chat.rendered = render(chat)
        chats[chat.id] = chat
        publishChat(chat.id)
        chat.id
    }

    override suspend fun addMembers(chatId: String, userIds: List<String>) = synchronized(lock) {
        me()
        val chat = groupChat(chatId)
        if (userIds.isEmpty()) throw GimException.InvalidInput("userIds must not be empty")
        val users = userIds.distinct().map(::user)
        users.firstOrNull { u -> chat.members.any { it.userId == u.userId } }
            ?.let { throw GimException.InvalidInput("${it.userId} is already a member") }
        users.forEach {
            chat.members += it
            appendEvent(chat, membership(it, MembershipChangeKind.ADDED))
        }
        refreshTimeline(chat)
        publishChat(chat.id)
    }

    override suspend fun removeMember(chatId: String, userId: String) = synchronized(lock) {
        val me = me()
        val chat = groupChat(chatId)
        if (userId == me.userId) throw GimException.InvalidInput("use leaveChat to leave")
        val member = chat.members.firstOrNull { it.userId == userId } ?: throw GimException.NotFound("member $userId")
        chat.members.remove(member)
        appendEvent(chat, membership(member, MembershipChangeKind.REMOVED))
        refreshTimeline(chat)
        publishChat(chat.id)
    }

    override suspend fun leaveChat(chatId: String) = synchronized(lock) {
        val me = me()
        val chat = groupChat(chatId)
        chat.members.removeAll { it.userId == me.userId }
        appendEvent(chat, membership(me, MembershipChangeKind.LEFT))
        refreshTimeline(chat)
        chats.remove(chatId)
        publishChat(chatId)
    }

    override suspend fun setGroupInfo(chatId: String, name: String?, avatarFilePath: String?) = synchronized(lock) {
        me()
        val chat = groupChat(chatId)
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
        updateMessage(chatId, messageId) { message ->
            if (!message.isOwn) throw GimException.InvalidInput("only own messages can be deleted")
            message.copy(content = MessageContent.Deleted, reactions = emptyList())
        }
    }

    override suspend fun react(chatId: String, messageId: String, emoji: String) = synchronized(lock) {
        me()
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

    override suspend fun chatMembers(chatId: String): List<UserProfile> = synchronized(lock) {
        me()
        chat(chatId).members.toList()
    }

    // endregion

    // region Test controls

    /** Adds a user that [findUser] can discover. */
    fun addDirectoryUser(user: UserProfile) = synchronized(lock) { directory[user.userId] = user }

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
        directory[session.userId] = UserProfile(session.userId, session.handle, session.displayName)
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

    private fun membership(user: UserProfile, change: MembershipChangeKind) =
        TimelineItem.MembershipChange(user.displayName, change, clock())

    private fun enqueue(chatId: String, replyTo: String?, content: (messageId: String) -> MessageContent): String {
        val me = me()
        val chat = chat(chatId)
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

    private fun dayStart(ms: Long): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

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
        val online = syncing && reachable && session != null
        when {
            !online -> updateConnection(ConnectionState.OFFLINE)
            connection == ConnectionState.OFFLINE -> {
                updateConnection(ConnectionState.CONNECTING)
                updateConnection(ConnectionState.SYNCING)
                updateConnection(ConnectionState.CONNECTED)
            }
        }
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

        private val HANDLE_PATTERN = Regex("[a-z0-9._-]{3,32}")

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
