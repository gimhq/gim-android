package im.g.apps.core.bridge

// Mirrors gim-core `bindings/gim-ffi/src/types.rs` and `client.rs` (ClientConfig).
// All ids are opaque strings; all timestamps are Unix epoch milliseconds.
// Unsigned Rust integers map to signed Kotlin ones: u32 -> Int, u64 -> Long.

/** The signed-in account on this device. */
data class Session(
    val userId: String,
    val deviceId: String,
    /** Empty until [GimClient.claimHandle]. */
    val handle: String,
    val displayName: String,
)

/** Public profile of a user. */
data class UserProfile(
    val userId: String,
    val handle: String,
    val displayName: String,
)

/** A gim user id: "u" + 55 base32 characters. */
private val USER_ID_PATTERN = Regex("^u[a-z2-7]{54}[aiqy]$")

private const val SHORT_USER_ID_LENGTH = 8

/** The first 8 characters of [userId] after its leading "u"; shown for users without a known name. */
fun shortUserId(userId: String): String = userId.removePrefix("u").take(SHORT_USER_ID_LENGTH)

/**
 * A name as gim-core reports it, shortened to [shortUserId] when it is a bare user id (gim-core falls back to the
 * user id until a directory lookup resolves the name, and remote users may have no handle).
 */
fun displayNameOrShortId(name: String): String = if (USER_ID_PATTERN.matches(name)) shortUserId(name) else name

/** The display name, falling back to a [shortUserId] when it is empty or just the user id. */
val UserProfile.displayLabel: String
    get() = if (displayName.isBlank() || displayName == userId) shortUserId(userId) else displayNameOrShortId(displayName)

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
    val state: ChatState = ChatState.ACTIVE,
)

/** Where this device stands in a chat; read from local state, so right from the first callback after a restart. */
enum class ChatState {
    /** A member; messages can be sent. */
    ACTIVE,

    /** [GimClient.leaveChat] was accepted by the hub; nothing can be sent until another member commits the removal. */
    LEAVING,

    /** Removed: after leaving, by another member, or hub-side. History is kept; nothing can be sent. */
    LEFT,
}

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
        /** The user who joined or left; the own user id for the signed-in user's own `Left`. */
        val userId: String,
        /** Who caused it: the adder or remover, the user themselves for `Left`; null if unknown. */
        val actorUserId: String?,
    ) : TimelineItem()
}

/** Stable key for lazy lists: unique within one timeline. */
val TimelineItem.key: String
    get() = when (this) {
        is TimelineItem.Message -> item.messageId
        is TimelineItem.DateSeparator -> "date-$dayStartMs"
        is TimelineItem.MembershipChange -> "member-$atMs-$userId-$change"
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

    /** Delivered to every device of every other member. */
    data object Delivered : SendState()

    /** Read on every device of every other member. */
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
    /** Base URL of gim-hub, `http://` or `https://`. */
    val serverUrl: String,
)

/** One device of a user. */
data class DeviceInfo(
    val deviceId: String,
    /** When the device was certified; 0 for revoked devices. */
    val createdAtMs: Long,
    /** Whether this is the client's own device. */
    val isCurrent: Boolean,
    val revoked: Boolean,
)

/** New-device side of a device link; mirrors gim-core's `LinkSession`. Accepts one bundle. */
interface LinkSession {
    /** Contents of the QR code the old device scans. */
    fun qrString(): String
}

/** Old-device side of a device link; mirrors gim-core's `PendingApproval`. */
interface PendingApproval {
    /** Six digits, "123 456", compared with the new device's [GimClient.waitLink] result. */
    fun sas(): String

    /** The new device; revoke it with [GimClient.revokeDevice] if the link is abandoned after approval. */
    fun deviceId(): String
}
