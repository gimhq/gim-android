package im.g.apps.core.bridge

// Mirrors gim-core `bindings/gim-ffi/src/types.rs` and `client.rs` (ClientConfig).
// All ids are opaque strings; all timestamps are Unix epoch milliseconds.
// Unsigned Rust integers map to signed Kotlin ones: u32 -> Int, u64 -> Long.

/** The signed-in account on this device. */
data class Session(
    val userId: String,
    val deviceId: String,
    val handle: String,
    val displayName: String,
)

/** Public profile of a user. */
data class UserProfile(
    val userId: String,
    val handle: String,
    val displayName: String,
)

enum class ChatKind { DIRECT, GROUP }

/** One row of the chat list. */
data class ChatSummary(
    val chatId: String,
    val kind: ChatKind,
    /** Group name, or the other user's display name for a direct chat. */
    val name: String,
    val avatar: MediaRef?,
    val lastMessage: MessagePreview?,
    val unreadCount: Int,
    val updatedAtMs: Long,
    val memberCount: Int,
)

/** Last message shown in a chat list row. */
data class MessagePreview(
    val senderName: String,
    /** Plain-text rendering ("Photo", "File: report.pdf" for media). */
    val text: String,
    val sentAtMs: Long,
    val isOwn: Boolean,
)

/** One row of a chat timeline. */
sealed class TimelineItem {
    data class Message(val item: MessageItem) : TimelineItem()

    /** Inserted before the first item of each local calendar day. */
    data class DateSeparator(val dayStartMs: Long) : TimelineItem()

    data class MembershipChange(
        val userName: String,
        val change: MembershipChangeKind,
        val atMs: Long,
    ) : TimelineItem()
}

/** Stable key for lazy lists: unique within one timeline. */
val TimelineItem.key: String
    get() = when (this) {
        is TimelineItem.Message -> item.messageId
        is TimelineItem.DateSeparator -> "date-$dayStartMs"
        is TimelineItem.MembershipChange -> "member-$atMs-$userName-$change"
    }

enum class MembershipChangeKind { ADDED, REMOVED, LEFT }

data class MessageItem(
    /** Client id of the message; stable from local echo to delivery. */
    val messageId: String,
    val chatId: String,
    val sender: UserProfile,
    val isOwn: Boolean,
    val sentAtMs: Long,
    val content: MessageContent,
    val replyTo: ReplyPreview?,
    val reactions: List<ReactionGroup>,
    val edited: Boolean,
    /** Delivery state of own messages; null for messages from others. */
    val sendState: SendState?,
)

sealed class MessageContent {
    data class Text(val body: String) : MessageContent()

    data class Image(
        val media: MediaRef,
        val width: Int?,
        val height: Int?,
        val caption: String?,
    ) : MessageContent()

    data class File(
        val media: MediaRef,
        val fileName: String?,
        val caption: String?,
    ) : MessageContent()

    /** The sender deleted the message. */
    data object Deleted : MessageContent()

    /** Content this client version cannot render. */
    data object Unsupported : MessageContent()
}

/**
 * Handle to an encrypted attachment. Pass its ids back to [GimClient.fetchMedia] /
 * [GimClient.fetchThumbnail]; keys and blob ids never leave gim-core.
 */
data class MediaRef(
    val chatId: String,
    val messageId: String,
    val mime: String,
    val size: Long,
    val hasThumbnail: Boolean,
)

/** Quoted message shown above a reply. */
data class ReplyPreview(
    val messageId: String,
    val senderName: String,
    val text: String,
)

data class ReactionGroup(
    val emoji: String,
    val count: Int,
    /** Whether the signed-in user is among the reactors. */
    val own: Boolean,
)

sealed class SendState {
    /** Queued in the outbox or being uploaded. */
    data object Pending : SendState()

    /** Accepted by the server. */
    data object Sent : SendState()

    /** Delivered to at least one recipient device. */
    data object Delivered : SendState()

    /** Read by at least one recipient. */
    data object Read : SendState()

    /** Gave up; [GimClient.retrySend] or [GimClient.cancelSend]. */
    data class Failed(val reason: String) : SendState()
}

enum class ConnectionState {
    OFFLINE,
    CONNECTING,
    CONNECTED,

    /** Connected and catching up on missed events. */
    SYNCING,
}

data class ClientConfig(
    /** Base URL of gim-server, `http://` or `https://`. */
    val serverUrl: String,
)
