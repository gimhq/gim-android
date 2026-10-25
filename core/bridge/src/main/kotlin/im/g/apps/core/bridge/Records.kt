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
    /** Whether the user has a profile photo; download it with [GimClient.fetchUserAvatar]. */
    val hasAvatar: Boolean = false,
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
    /** The other user of a direct chat (show their photo when [avatar] is absent); null for groups. */
    val peer: UserProfile? = null,
    /** Muted until this Unix time in ms; [GimClient.mutedForever] for good, 0 when not muted. Compare with now. */
    val mutedUntilMs: Long = 0,
    /** Pinned chats come first in the chat list. */
    val pinned: Boolean = false,
    /** Show archived chats apart; a new message does not unarchive a chat. */
    val archived: Boolean = false,
    /** A direct chat whose [peer] is blocked. */
    val blocked: Boolean = false,
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
    /** Plain-text rendering in English ("Photo", "File: report.pdf" for media); localized UI renders [kind]. */
    val text: String,
    val kind: PreviewKind,
    val sentAtMs: Long,
    val isOwn: Boolean,
    /** Delivery state of an own message (the row's ticks), as [MessageItem.sendState]; null for others' messages. */
    val sendState: SendState? = null,
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
    /** As [MessagePreview.text]. */
    val text: String,
    val kind: PreviewKind,
)

/** What a previewed message is, for a rendering in the app's language. */
sealed class PreviewKind {
    /** A text message or media with a caption: show the preview's `text` (user content) as is. */
    data object Text : PreviewKind()

    /** An image without a caption ("Photo"). */
    data object Photo : PreviewKind()

    /** Another file without a caption ("File: <name>", or "File"). */
    data class File(val fileName: String?) : PreviewKind()

    /** A deleted message ("Message deleted"). */
    data object Deleted : PreviewKind()
}

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

    /** Gave up; [GimClient.retrySend] or [GimClient.cancelSend]. Hide Retry when [cause] is not [SendFailure.retryable]. */
    data class Failed(val reason: String, val cause: SendFailure) : SendState()
}

/** Why a message is [SendState.Failed]. */
enum class SendFailure {
    /** A hub suspended this user. */
    USER_SUSPENDED,

    /** The user's hub does not admit this user. */
    USER_NOT_ADMITTED,

    /** The hub owning the chat does not admit this user. */
    NOT_ADMITTED_BY_PEER,

    /** The hub refused the message for good (e.g. not a member any more). */
    REJECTED,

    /** Anything else (retries used up, a local failure, an older failure). */
    OTHER,
    ;

    /** gim-core's guidance: no Retry for a suspended or not admitted user. */
    val retryable: Boolean get() = this == REJECTED || this == OTHER
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
    /** The process kind; the app always creates [ClientMode.APP] clients. */
    val mode: ClientMode = ClientMode.APP,
    /**
     * Base URL of the gim keep server, which backs up the privacy settings and takes [GimClient.reportUser] reports;
     * null: no backup, and reports fail with [GimException.InvalidInput].
     */
    val keepServerUrl: String? = null,
)

/**
 * Privacy settings, synced to the user's other devices and backed up on the keep server; all on and empty by default.
 */
data class PrivacySettings(
    /** Off: [GimClient.markRead] sends no receipt and others' receipts are not shown (sends stop at Delivered). */
    val readReceipts: Boolean = true,
    /** Off: [GimClient.setTyping] sends nothing and others' typing is not shown. */
    val typingIndicators: Boolean = true,
    /** Users whose messages, reactions, receipts, typing and invites are dropped. */
    val blockedUserIds: List<String> = emptyList(),
)

/** Result of [GimClient.rotateRecoveryCode]. */
data class RecoveryRotation(
    /** The new recovery code; stored nowhere, so show it once. */
    val recoveryCode: String,
    /** Hubs that still hold the backup sealed with the old code, which keeps working there until a later rotation. */
    val unsyncedHubIds: List<String>,
)

/** Why a user is reported with [GimClient.reportUser]. */
enum class ReportReason {
    SPAM,
    HARASSMENT,

    /** Content not suitable for the user (sexual, violent, ...). */
    INAPPROPRIATE,
    SCAM,
    OTHER,
}

/** What the process creating the client is; gim-core's `ClientMode`. */
enum class ClientMode {
    /** The app. */
    APP,

    /**
     * An app extension on the app's data directory: no sync loop ([GimClient.startSync] fails), no maintenance, push
     * registration is left to the app, and a local database that cannot be opened fails creation instead of being
     * reset.
     */
    EXTENSION,
}

/**
 * The device's push registration on its hubs; every field is opaque to the hub and passed to the push server.
 * [toString] leaves out the token and the Web Push keys.
 */
data class PushRegistration(
    /** Push platform (`fcm`, `unifiedpush`, ...): 1-64 characters of `a-z 0-9 . _ -`. */
    val provider: String,
    /** Platform token: 1-4096 bytes without control characters. */
    val token: String,
    /** App identifier: 1-255 characters of `A-Z a-z 0-9 . - _ :`. */
    val appId: String? = null,
    /** Platform sandbox flag. */
    val sandbox: Boolean? = null,
    /** Web Push `p256dh` key: 1-256 characters of base64url. */
    val p256dh: String? = null,
    /** Web Push `auth` secret: 1-256 characters of base64url. */
    val auth: String? = null,
) {
    override fun toString(): String = "PushRegistration(provider=$provider, appId=$appId, sandbox=$sandbox, ..)"
}

/** What a notification is about. */
enum class NotificationKind {
    /** A text or media message (also the silent edit or delete of one). */
    MESSAGE,

    /** A reaction to a message; [NotificationContent.messageId] is the reacted-to message. */
    REACTION,

    /** The user was added to a chat. */
    INVITE,

    /** A member was added to or removed from a chat; never displayed. */
    MEMBERSHIP,

    /** Content unknown: show [NotificationContent.body] ("New message"). */
    GENERIC,
}

/** What to show for a push, from [GimClient.processPush]. */
data class NotificationContent(
    val chatId: String?,
    /** Group name, or the other user's name for a direct chat. */
    val chatTitle: String?,
    val senderUserId: String?,
    val senderDisplayName: String?,
    /** The text, the media caption, "Photo" or "File: <name>"; "reacted <emoji>"; "New message" for generic content. */
    val body: String,
    val messageId: String?,
    /** Use as the notification identifier (FCM `tag`): every hub of the user may push the same message. */
    val collapseId: String?,
    val kind: NotificationKind,
    /** False: show nothing (own messages, edits, deletes, receipts, membership changes, already read). */
    val shouldDisplay: Boolean,
    /** When the message was sent, or now. */
    val timestamp: Long,
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

/** Where a hub of the user's hosting record stands on this device. */
enum class HubState {
    /**
     * Listed, but this device has no URL for it that proved the hub's identity (none yet, or only one shared by
     * another own device, which is never contacted): [GimClient.addHub] with its URL on this device.
     */
    UNVERIFIED,

    /** Listed, and its URL proved the hub's identity: in use. */
    VERIFIED,

    /** No longer listed by the hosting record. */
    REMOVED,
}

/** A hub of the user's hosting record, for the hub settings screen. */
data class HubInfo(
    val hubId: String,
    /** Client API base URL, once known. */
    val url: String?,
    val state: HubState,
    /** Priority in the hosting record; lower is preferred. gim-core's `u8`. */
    val priority: Int,
    /** The first listed hub: messages and member changes are submitted there first. */
    val isPrimary: Boolean,
    /** The hub of [ClientConfig.serverUrl]; it cannot be removed. */
    val isHome: Boolean,
    /** This device holds a session on the hub. */
    val signedIn: Boolean,
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
