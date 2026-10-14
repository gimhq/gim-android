package im.g.apps.core.bridge

/** Opaque user identifier assigned by gim-server. UniFFI exposes ids as plain strings. */
typealias UserId = String

/** Opaque device identifier assigned at registration. */
typealias DeviceId = String

/** Opaque chat identifier (one MLS group per chat). */
typealias ChatId = String

/** Message identifier; for outbound messages this is the outbox `client_id`. */
typealias MessageId = String

data class User(
    val id: UserId,
    val handle: String,
    val displayName: String,
)

data class Session(
    val userId: UserId,
    val deviceId: DeviceId,
    val handle: String,
    val displayName: String,
    val serverUrl: String,
)

sealed class LoginState {
    data object Unregistered : LoginState()

    data class Registered(val session: Session) : LoginState()
}

enum class ConnectionState { OFFLINE, CONNECTING, ONLINE }

enum class ChatKind { DIRECT, GROUP }

/** Outbound delivery state, driven by the outbox and encrypted receipts. */
enum class SendStatus { PENDING, SENT, DELIVERED, READ, FAILED }

data class ChatSummary(
    val id: ChatId,
    val kind: ChatKind,
    val name: String,
    val avatarPath: String?,
    val unreadCount: Int,
    val lastMessage: MessagePreview?,
    /** Epoch milliseconds of the newest timeline item (or chat creation). */
    val lastActivityAt: Long,
    val members: List<User>,
)

data class MessagePreview(
    val messageId: MessageId,
    val senderName: String,
    val fromMe: Boolean,
    val content: MessageContent,
    val sentAt: Long,
    val status: SendStatus?,
)

data class ReplyPreview(
    val messageId: MessageId,
    val senderName: String,
    val snippet: String,
)

data class ReactionGroup(
    val emoji: String,
    val count: Int,
    /** True when the local user is one of the reactors. */
    val mine: Boolean,
)

sealed class MessageContent {
    data class Text(val body: String) : MessageContent()

    data class Image(
        val caption: String?,
        val mime: String,
        val width: Int?,
        val height: Int?,
        val size: Long,
        val thumbnailPath: String?,
        val mediaId: String,
    ) : MessageContent()

    data class File(
        val caption: String?,
        val fileName: String,
        val mime: String,
        val size: Long,
        val mediaId: String,
    ) : MessageContent()

    data object Deleted : MessageContent()

    data object Unsupported : MessageContent()
}

enum class SystemEventKind { CHAT_CREATED, MEMBER_ADDED, MEMBER_REMOVED }

/** One row of a chat timeline. Timelines are ordered newest first (index 0 = newest). */
sealed class TimelineItem {
    abstract val id: String

    data class Message(
        override val id: MessageId,
        val chatId: ChatId,
        val sender: User,
        val fromMe: Boolean,
        val sentAt: Long,
        val content: MessageContent,
        val replyTo: ReplyPreview?,
        val reactions: List<ReactionGroup>,
        val editedAt: Long?,
        val deleted: Boolean,
        /** Delivery state for outbound messages; null for inbound ones. */
        val status: SendStatus?,
    ) : TimelineItem()

    data class System(
        override val id: String,
        val at: Long,
        val kind: SystemEventKind,
        val text: String,
    ) : TimelineItem()
}
