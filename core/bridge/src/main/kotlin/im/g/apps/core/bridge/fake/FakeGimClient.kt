package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.ChatId
import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatListListener
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.ConnectionListener
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.LoginState
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageId
import im.g.apps.core.bridge.MessagePreview
import im.g.apps.core.bridge.ReactionGroup
import im.g.apps.core.bridge.ReplyPreview
import im.g.apps.core.bridge.SendStatus
import im.g.apps.core.bridge.Session
import im.g.apps.core.bridge.Subscription
import im.g.apps.core.bridge.SystemEventKind
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.TimelineListener
import im.g.apps.core.bridge.TypingListener
import im.g.apps.core.bridge.User
import im.g.apps.core.bridge.UserId
import im.g.apps.core.bridge.diff.ListDiff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Deterministic in-memory [GimClient] for ViewModel/UI tests, previews and the app until gim-core's
 * bindings are vendored.
 *
 * All state lives behind one lock and listeners are invoked synchronously on the calling thread, in
 * mutation order. Outbound messages start [SendStatus.PENDING] and advance to SENT, DELIVERED and READ
 * after [outboxDelays] (run on [scope]); pass `outboxDelays = null` to drive them with [advanceOutbox].
 *
 * @param clock epoch-millisecond time source for timestamps.
 * @param cacheDir directory where [fetchMedia] writes placeholder files.
 * @param pageSize number of newest timeline items visible before [paginateBackwards].
 */
class FakeGimClient(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheDir: File = File(System.getProperty("java.io.tmpdir"), "gim-fake-media"),
    private val outboxDelays: OutboxDelays? = OutboxDelays(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : GimClient {

    data class OutboxDelays(
        val sentMs: Long = 400,
        val deliveredMs: Long = 800,
        val readMs: Long = 1_500,
    )

    private class FakeChat(
        val id: ChatId,
        val kind: ChatKind,
        var name: String,
        val members: MutableList<User>,
        val createdAt: Long,
    ) {
        /** Full history, newest first; the first [visible] items are exposed to timeline listeners. */
        val history = mutableListOf<TimelineItem>()
        var visible = 0
        var unread = 0
    }

    private class MediaEntry(val mime: String, val width: Int?, val height: Int?, val sourcePath: String?)

    private class Registration<L>(val chatId: ChatId?, val listener: L)

    private val lock = Any()
    private var loginState: LoginState = LoginState.Unregistered
    private var connection = ConnectionState.OFFLINE
    private val directory = LinkedHashMap<UserId, User>()
    private val chats = LinkedHashMap<ChatId, FakeChat>()
    private val messageChats = HashMap<MessageId, ChatId>()
    private val media = HashMap<String, MediaEntry>()
    private val typing = LinkedHashMap<ChatId, List<User>>()
    private val myTyping = HashMap<ChatId, Boolean>()
    private val outbox = LinkedHashSet<MessageId>()
    private val doomed = HashSet<MessageId>()
    private var failNext = false
    private var nextId = 1
    private var chatList: List<ChatSummary> = emptyList()

    private val chatListRegs = mutableListOf<Registration<ChatListListener>>()
    private val timelineRegs = mutableListOf<Registration<TimelineListener>>()
    private val connectionRegs = mutableListOf<Registration<ConnectionListener>>()
    private val typingRegs = mutableListOf<Registration<TypingListener>>()

    /** Number of live subscriptions of every kind; lets tests assert that flows cancel on close. */
    val activeSubscriptions: Int
        get() = synchronized(lock) { chatListRegs.size + timelineRegs.size + connectionRegs.size + typingRegs.size }

    // region GimClient

    override suspend fun loginState(): LoginState = synchronized(lock) { loginState }

    override suspend fun register(serverUrl: String, handle: String, displayName: String): Session =
        synchronized(lock) {
            if (loginState is LoginState.Registered) throw GimException.Conflict("already registered")
            val url = serverUrl.trim()
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                throw GimException.InvalidInput("serverUrl", "must start with http:// or https://")
            }
            val normalized = normalizeHandle(handle)
            if (normalized.isEmpty()) throw GimException.InvalidInput("handle", "must not be blank")
            if (!HANDLE_PATTERN.matches(normalized)) {
                throw GimException.InvalidInput("handle", "use 3-32 of a-z, 0-9, '.', '_', '-'")
            }
            val name = displayName.trim()
            if (name.isEmpty()) throw GimException.InvalidInput("displayName", "must not be blank")
            if (directory.values.any { it.handle == normalized }) throw GimException.Conflict("handle taken")

            val session = Session("u-$normalized", "d-${nextId++}", normalized, name, url)
            seedSession(session)
            session
        }

    override suspend fun logout() = synchronized(lock) {
        val session = requireSession()
        directory.remove(session.userId)
        loginState = LoginState.Unregistered
        chats.clear()
        messageChats.clear()
        outbox.clear()
        doomed.clear()
        typing.clear()
        chatList = emptyList()
        chatListRegs.toList().forEach { it.listener.onUpdate(listOf(ListDiff.Clear)) }
        timelineRegs.toList().forEach { it.listener.onUpdate(listOf(ListDiff.Clear)) }
        updateConnection(ConnectionState.OFFLINE)
    }

    override suspend fun start() = synchronized(lock) {
        requireSession()
        if (connection != ConnectionState.ONLINE) {
            updateConnection(ConnectionState.CONNECTING)
            updateConnection(ConnectionState.ONLINE)
        }
    }

    override suspend fun stop() = synchronized(lock) { updateConnection(ConnectionState.OFFLINE) }

    override suspend fun findUser(handle: String): User? = synchronized(lock) {
        requireSession()
        val normalized = normalizeHandle(handle)
        directory.values.firstOrNull { it.handle == normalized }
    }

    override suspend fun createDirectChat(userId: UserId): ChatId = synchronized(lock) {
        val me = me()
        if (userId == me.id) throw GimException.InvalidInput("userId", "cannot chat with yourself")
        val other = user(userId)
        chats.values.firstOrNull { it.kind == ChatKind.DIRECT && it.members.any { m -> m.id == userId } }
            ?.let { return@synchronized it.id }
        val chat = FakeChat("chat-${nextId++}", ChatKind.DIRECT, other.displayName, mutableListOf(me, other), clock())
        chats[chat.id] = chat
        publishChat(chat.id)
        chat.id
    }

    override suspend fun createGroupChat(name: String, memberIds: List<UserId>): ChatId = synchronized(lock) {
        val me = me()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw GimException.InvalidInput("name", "must not be blank")
        val members = (listOf(me) + memberIds.filter { it != me.id }.distinct().map(::user)).toMutableList()
        val chat = FakeChat("chat-${nextId++}", ChatKind.GROUP, trimmed, members, clock())
        chats[chat.id] = chat
        chat.history.add(0, system(SystemEventKind.CHAT_CREATED, "${me.displayName} created the group"))
        chat.visible = 1
        publishChat(chat.id)
        chat.id
    }

    override suspend fun addMember(chatId: ChatId, userId: UserId) = synchronized(lock) {
        me()
        val chat = groupChat(chatId)
        val user = user(userId)
        if (chat.members.any { it.id == userId }) throw GimException.Conflict("already a member")
        chat.members.add(user)
        pushFront(chat, system(SystemEventKind.MEMBER_ADDED, "${user.displayName} was added"))
        publishChat(chat.id)
    }

    override suspend fun removeMember(chatId: ChatId, userId: UserId) = synchronized(lock) {
        me()
        val chat = groupChat(chatId)
        val user = chat.members.firstOrNull { it.id == userId } ?: throw GimException.NotFound("member $userId")
        chat.members.remove(user)
        pushFront(chat, system(SystemEventKind.MEMBER_REMOVED, "${user.displayName} was removed"))
        publishChat(chat.id)
    }

    override suspend fun chatDetails(chatId: ChatId): ChatSummary = synchronized(lock) { summary(chat(chatId)) }

    override suspend fun sendText(chatId: ChatId, body: String, replyTo: MessageId?): MessageId =
        synchronized(lock) {
            if (body.isBlank()) throw GimException.InvalidInput("body", "must not be blank")
            enqueue(chatId, MessageContent.Text(body), replyTo)
        }

    override suspend fun sendMedia(
        chatId: ChatId,
        filePath: String,
        mime: String,
        fileName: String,
        caption: String?,
        replyTo: MessageId?,
    ): MessageId = synchronized(lock) {
        val file = File(filePath)
        if (!file.isFile) throw GimException.InvalidInput("filePath", "file not found")
        val mediaId = "media-${nextId++}"
        media[mediaId] = MediaEntry(mime, null, null, file.absolutePath)
        val content = if (mime.startsWith("image/")) {
            MessageContent.Image(caption, mime, null, null, file.length(), null, mediaId)
        } else {
            MessageContent.File(caption, fileName, mime, file.length(), mediaId)
        }
        enqueue(chatId, content, replyTo)
    }

    override suspend fun editMessage(messageId: MessageId, newBody: String) = synchronized(lock) {
        me()
        if (newBody.isBlank()) throw GimException.InvalidInput("newBody", "must not be blank")
        val (chat, index, message) = locateMessage(messageId)
        if (!message.fromMe || message.deleted || message.content !is MessageContent.Text) {
            throw GimException.InvalidInput("messageId", "only own text messages can be edited")
        }
        replace(chat, index, message.copy(content = MessageContent.Text(newBody), editedAt = clock()))
    }

    override suspend fun deleteMessage(messageId: MessageId) = synchronized(lock) {
        me()
        val (chat, index, message) = locateMessage(messageId)
        if (!message.fromMe) throw GimException.InvalidInput("messageId", "only own messages can be deleted")
        if (message.deleted) return@synchronized
        replace(chat, index, message.copy(content = MessageContent.Deleted, deleted = true, reactions = emptyList()))
    }

    override suspend fun toggleReaction(messageId: MessageId, emoji: String) = synchronized(lock) {
        me()
        if (emoji.isBlank()) throw GimException.InvalidInput("emoji", "must not be blank")
        val (chat, index, message) = locateMessage(messageId)
        if (message.deleted) throw GimException.InvalidInput("messageId", "message was deleted")
        val existing = message.reactions.firstOrNull { it.emoji == emoji }
        val reactions = when {
            existing == null -> message.reactions + ReactionGroup(emoji, 1, mine = true)
            existing.mine && existing.count == 1 -> message.reactions - existing
            existing.mine -> message.reactions.map { if (it == existing) it.copy(count = it.count - 1, mine = false) else it }
            else -> message.reactions.map { if (it == existing) it.copy(count = it.count + 1, mine = true) else it }
        }
        replace(chat, index, message.copy(reactions = reactions))
    }

    override suspend fun retrySend(messageId: MessageId) = synchronized(lock) {
        me()
        val (chat, index, message) = locateMessage(messageId)
        if (!message.fromMe || message.status != SendStatus.FAILED) throw GimException.Conflict("message is not failed")
        replace(chat, index, message.copy(status = SendStatus.PENDING))
        startDelivery(messageId)
    }

    override suspend fun markRead(chatId: ChatId, upTo: MessageId) = synchronized(lock) {
        me()
        val chat = chat(chatId)
        if (chat.history.none { it.id == upTo }) throw GimException.NotFound("message $upTo")
        chat.unread = 0
        publishChat(chat.id)
    }

    override suspend fun setTyping(chatId: ChatId, typing: Boolean) = synchronized(lock) {
        me()
        chat(chatId)
        myTyping[chatId] = typing
    }

    override suspend fun paginateBackwards(chatId: ChatId, count: Int): Boolean = synchronized(lock) {
        if (count <= 0) throw GimException.InvalidInput("count", "must be positive")
        val chat = chat(chatId)
        val older = chat.history.subList(chat.visible, minOf(chat.history.size, chat.visible + count)).toList()
        chat.visible += older.size
        if (older.isNotEmpty()) emitTimeline(chat.id, older.map { ListDiff.PushBack(it) })
        chat.visible < chat.history.size
    }

    override suspend fun fetchMedia(mediaId: String): String = synchronized(lock) {
        val entry = media[mediaId] ?: throw GimException.NotFound("media $mediaId")
        cacheDir.mkdirs()
        val target = File(cacheDir, mediaId.replace(UNSAFE_FILE_CHARS, "_"))
        val source = entry.sourcePath?.let(::File)
        when {
            source != null && source.isFile -> source.copyTo(target, overwrite = true)
            entry.mime.startsWith("image/") ->
                writePlaceholderPng(target, entry.width ?: 4, entry.height ?: 3, mediaId.hashCode())
            else -> target.writeText("Placeholder for $mediaId\n")
        }
        target.absolutePath
    }

    override fun subscribeChatList(listener: ChatListListener): Subscription = synchronized(lock) {
        listener.onUpdate(listOf(ListDiff.Reset(chatList)))
        addRegistration(chatListRegs, Registration(null, listener))
    }

    override fun subscribeTimeline(chatId: ChatId, listener: TimelineListener): Subscription = synchronized(lock) {
        val items = chats[chatId]?.let { it.history.take(it.visible) }.orEmpty()
        listener.onUpdate(listOf(ListDiff.Reset(items)))
        addRegistration(timelineRegs, Registration(chatId, listener))
    }

    override fun subscribeConnection(listener: ConnectionListener): Subscription = synchronized(lock) {
        listener.onState(connection)
        addRegistration(connectionRegs, Registration(null, listener))
    }

    override fun subscribeTyping(listener: TypingListener): Subscription = synchronized(lock) {
        typing.forEach { (chatId, users) -> listener.onTyping(chatId, users) }
        addRegistration(typingRegs, Registration(null, listener))
    }

    // endregion

    // region Test controls

    /** Adds a user that [findUser] can discover. */
    fun addDirectoryUser(user: User) = synchronized(lock) { directory[user.id] = user }

    /** Makes the next outbound send (or retry) end in [SendStatus.FAILED]. */
    fun failNextSend() = synchronized(lock) { failNext = true }

    /** Advances every in-flight outbound message by one status step (manual outbox mode). */
    fun advanceOutbox() = synchronized(lock) { outbox.toList().forEach(::step) }

    fun setConnection(state: ConnectionState) = synchronized(lock) { updateConnection(state) }

    /** Delivers an inbound text message from [from] into [chatId]; returns its id. */
    fun simulateIncoming(chatId: ChatId, text: String, from: UserId, replyTo: MessageId? = null): MessageId =
        synchronized(lock) {
            val chat = chat(chatId)
            val sender = chat.members.firstOrNull { it.id == from } ?: throw GimException.NotFound("member $from")
            val id = "m-${nextId++}"
            val message = TimelineItem.Message(
                id = id,
                chatId = chatId,
                sender = sender,
                fromMe = false,
                sentAt = clock(),
                content = MessageContent.Text(text),
                replyTo = replyTo?.let(::replyPreview),
                reactions = emptyList(),
                editedAt = null,
                deleted = false,
                status = null,
            )
            messageChats[id] = chatId
            chat.unread++
            pushFront(chat, message)
            publishChat(chatId)
            id
        }

    /** Marks [userId] as typing (or not) in [chatId] and notifies typing listeners. */
    fun simulateTyping(chatId: ChatId, userId: UserId, typing: Boolean) = synchronized(lock) {
        val chat = chat(chatId)
        val user = chat.members.firstOrNull { it.id == userId } ?: throw GimException.NotFound("member $userId")
        val current = this.typing[chatId].orEmpty()
        val updated = if (typing) (current.filter { it.id != userId } + user) else current.filter { it.id != userId }
        if (updated.isEmpty()) this.typing.remove(chatId) else this.typing[chatId] = updated
        typingRegs.toList().forEach { it.listener.onTyping(chatId, updated) }
    }

    /** Last value passed to [setTyping] for [chatId]. */
    fun isTyping(chatId: ChatId): Boolean = synchronized(lock) { myTyping[chatId] == true }

    // endregion

    // region Seeding (used by DemoData)

    internal fun seedSession(session: Session) = synchronized(lock) {
        loginState = LoginState.Registered(session)
        directory[session.userId] = User(session.userId, session.handle, session.displayName)
    }

    internal fun seedMedia(mediaId: String, mime: String, width: Int?, height: Int?) = synchronized(lock) {
        media[mediaId] = MediaEntry(mime, width, height, null)
    }

    /** Adds a chat whose [history] is ordered newest first. */
    internal fun seedChat(
        id: ChatId,
        kind: ChatKind,
        name: String,
        members: List<User>,
        createdAt: Long,
        history: List<TimelineItem>,
        unread: Int,
    ) = synchronized(lock) {
        val chat = FakeChat(id, kind, name, members.toMutableList(), createdAt)
        chat.history.addAll(history)
        chat.visible = minOf(pageSize, history.size)
        chat.unread = unread
        history.forEach { messageChats[it.id] = id }
        chats[id] = chat
        publishChat(id)
    }

    // endregion

    // region Internals (callers hold the lock)

    private fun <L> addRegistration(regs: MutableList<Registration<L>>, reg: Registration<L>): Subscription {
        regs.add(reg)
        return Subscription { synchronized(lock) { regs.remove(reg) } }
    }

    private fun requireSession(): Session =
        (loginState as? LoginState.Registered)?.session ?: throw GimException.Unauthorized()

    private fun me(): User = requireSession().let { User(it.userId, it.handle, it.displayName) }

    private fun user(userId: UserId): User = directory[userId] ?: throw GimException.NotFound("user $userId")

    private fun chat(chatId: ChatId): FakeChat = chats[chatId] ?: throw GimException.NotFound("chat $chatId")

    private fun groupChat(chatId: ChatId): FakeChat = chat(chatId).also {
        if (it.kind != ChatKind.GROUP) throw GimException.InvalidInput("chatId", "not a group chat")
    }

    private data class Located(val chat: FakeChat, val index: Int, val message: TimelineItem.Message)

    private fun locateOrNull(messageId: MessageId): Located? {
        val chat = messageChats[messageId]?.let { chats[it] } ?: return null
        val index = chat.history.indexOfFirst { it.id == messageId }
        val message = chat.history.getOrNull(index) as? TimelineItem.Message ?: return null
        return Located(chat, index, message)
    }

    private fun locateMessage(messageId: MessageId): Located =
        locateOrNull(messageId) ?: throw GimException.NotFound("message $messageId")

    private fun replyPreview(messageId: MessageId): ReplyPreview {
        val message = locateMessage(messageId).message
        return ReplyPreview(message.id, message.sender.displayName, snippet(message.content))
    }

    private fun system(kind: SystemEventKind, text: String) =
        TimelineItem.System("s-${nextId++}", clock(), kind, text)

    private fun enqueue(chatId: ChatId, content: MessageContent, replyTo: MessageId?): MessageId {
        val me = me()
        val chat = chat(chatId)
        val reply = replyTo?.let(::replyPreview)
        val id = "c-${nextId++}"
        val message = TimelineItem.Message(id, chatId, me, true, clock(), content, reply, emptyList(), null, false, SendStatus.PENDING)
        messageChats[id] = chatId
        pushFront(chat, message)
        publishChat(chatId)
        startDelivery(id)
        return id
    }

    private fun startDelivery(id: MessageId) {
        if (failNext) {
            failNext = false
            doomed.add(id)
        }
        outbox.add(id)
        val delays = outboxDelays ?: return
        scope.launch {
            for (ms in listOf(delays.sentMs, delays.deliveredMs, delays.readMs)) {
                delay(ms)
                if (!synchronized(lock) { step(id) }) break
            }
        }
    }

    /** Moves [id] one status step forward; returns true while it stays in flight. */
    private fun step(id: MessageId): Boolean {
        if (id !in outbox) return false
        val located = locateOrNull(id)
        val next = when {
            located == null -> null
            doomed.remove(id) -> SendStatus.FAILED
            else -> when (located.message.status) {
                SendStatus.PENDING -> SendStatus.SENT
                SendStatus.SENT -> SendStatus.DELIVERED
                SendStatus.DELIVERED -> SendStatus.READ
                else -> null
            }
        }
        if (next == null || next == SendStatus.FAILED || next == SendStatus.READ) outbox.remove(id)
        if (located != null && next != null) {
            replace(located.chat, located.index, located.message.copy(status = next))
        }
        return id in outbox
    }

    private fun pushFront(chat: FakeChat, item: TimelineItem) {
        chat.history.add(0, item)
        chat.visible++
        emitTimeline(chat.id, listOf(ListDiff.PushFront(item)))
    }

    private fun replace(chat: FakeChat, index: Int, item: TimelineItem) {
        chat.history[index] = item
        if (index < chat.visible) emitTimeline(chat.id, listOf(ListDiff.Set(index, item)))
        publishChat(chat.id)
    }

    private fun emitTimeline(chatId: ChatId, diffs: List<ListDiff<TimelineItem>>) {
        timelineRegs.toList().filter { it.chatId == chatId }.forEach { it.listener.onUpdate(diffs) }
    }

    private fun updateConnection(state: ConnectionState) {
        if (state == connection) return
        connection = state
        connectionRegs.toList().forEach { it.listener.onState(state) }
    }

    private fun summary(chat: FakeChat): ChatSummary {
        val me = (loginState as? LoginState.Registered)?.session?.userId
        val name = if (chat.kind == ChatKind.DIRECT) {
            chat.members.firstOrNull { it.id != me }?.displayName ?: chat.name
        } else {
            chat.name
        }
        val last = chat.history.firstOrNull { it is TimelineItem.Message } as TimelineItem.Message?
        val newestAt = when (val newest = chat.history.firstOrNull()) {
            is TimelineItem.Message -> newest.sentAt
            is TimelineItem.System -> newest.at
            null -> chat.createdAt
        }
        return ChatSummary(
            id = chat.id,
            kind = chat.kind,
            name = name,
            avatarPath = null,
            unreadCount = chat.unread,
            lastMessage = last?.let {
                MessagePreview(it.id, it.sender.displayName, it.fromMe, it.content, it.sentAt, it.status)
            },
            lastActivityAt = maxOf(chat.createdAt, newestAt),
            members = chat.members.toList(),
        )
    }

    /** Recomputes the ordered chat list and emits the minimal diff for the single changed [chatId]. */
    private fun publishChat(chatId: ChatId) {
        val old = chatList
        val new = chats.values.map(::summary)
            .sortedWith(compareByDescending<ChatSummary> { it.lastActivityAt }.thenBy { it.id })
        chatList = new
        val from = old.indexOfFirst { it.id == chatId }
        val to = new.indexOfFirst { it.id == chatId }
        val diffs = when {
            from == -1 -> listOf(ListDiff.Insert(to, new[to]))
            from == to -> if (old[from] == new[to]) return else listOf(ListDiff.Set(to, new[to]))
            else -> listOf(ListDiff.Remove(from), ListDiff.Insert(to, new[to]))
        }
        chatListRegs.toList().forEach { it.listener.onUpdate(diffs) }
    }

    // endregion

    companion object {
        const val DEFAULT_PAGE_SIZE = 20

        private val HANDLE_PATTERN = Regex("[a-z0-9._-]{3,32}")
        private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")

        private fun normalizeHandle(handle: String) = handle.trim().removePrefix("@").lowercase()

        /** A registered client seeded with [DemoData]: three contacts, a direct chat and a group. */
        fun withDemoData(
            scope: CoroutineScope,
            clock: () -> Long = System::currentTimeMillis,
            cacheDir: File = File(System.getProperty("java.io.tmpdir"), "gim-fake-media"),
            outboxDelays: OutboxDelays? = OutboxDelays(),
            pageSize: Int = DEFAULT_PAGE_SIZE,
        ): FakeGimClient = FakeGimClient(scope, clock, cacheDir, outboxDelays, pageSize).also { DemoData.seed(it, clock()) }
    }
}

/** Plain-text snippet used for fake reply previews. */
internal fun snippet(content: MessageContent): String = when (content) {
    is MessageContent.Text -> content.body
    is MessageContent.Image -> content.caption ?: "Photo"
    is MessageContent.File -> content.fileName
    MessageContent.Deleted -> "Message deleted"
    MessageContent.Unsupported -> "Unsupported message"
}
