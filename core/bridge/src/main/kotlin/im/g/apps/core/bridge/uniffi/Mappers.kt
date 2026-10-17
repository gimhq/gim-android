package im.g.apps.core.bridge.uniffi

import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatListDiff
import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.ClientConfig
import im.g.apps.core.bridge.ClientMode
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.DeviceInfo
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.HubInfo
import im.g.apps.core.bridge.HubState
import im.g.apps.core.bridge.MediaRef
import im.g.apps.core.bridge.MembershipChangeKind
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.MessagePreview
import im.g.apps.core.bridge.NotificationContent
import im.g.apps.core.bridge.NotificationKind
import im.g.apps.core.bridge.PushRegistration
import im.g.apps.core.bridge.ReactionGroup
import im.g.apps.core.bridge.ReplyPreview
import im.g.apps.core.bridge.SendState
import im.g.apps.core.bridge.Session
import im.g.apps.core.bridge.TimelineDiff
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.UserProfile

// Pure 1:1 mapping between gim-core's generated `im.g.gimcore` types and the :core:bridge mirror, per the
// mapping table in GimClient.kt. Unsigned integers convert bit-for-bit (u32 -> Int, u64 -> Long, u16 -> Int).
// Constructing or reading these generated types never loads the native library.

internal fun im.g.gimcore.Session.toMirror() = Session(
    userId = userId,
    deviceId = deviceId,
    handle = handle,
    displayName = displayName,
)

internal fun im.g.gimcore.UserProfile.toMirror() = UserProfile(
    userId = userId,
    handle = handle,
    displayName = displayName,
)

internal fun im.g.gimcore.DeviceInfo.toMirror() = DeviceInfo(
    deviceId = deviceId,
    createdAtMs = createdAtMs.toLong(),
    isCurrent = isCurrent,
    revoked = revoked,
)

internal fun im.g.gimcore.HubState.toMirror(): HubState = when (this) {
    im.g.gimcore.HubState.UNVERIFIED -> HubState.UNVERIFIED
    im.g.gimcore.HubState.VERIFIED -> HubState.VERIFIED
    im.g.gimcore.HubState.REMOVED -> HubState.REMOVED
}

internal fun im.g.gimcore.HubInfo.toMirror() = HubInfo(
    hubId = hubId,
    url = url,
    state = state.toMirror(),
    priority = priority.toInt(),
    isPrimary = isPrimary,
    isHome = isHome,
    signedIn = signedIn,
)

internal fun im.g.gimcore.ChatKind.toMirror(): ChatKind = when (this) {
    im.g.gimcore.ChatKind.DIRECT -> ChatKind.DIRECT
    im.g.gimcore.ChatKind.GROUP -> ChatKind.GROUP
}

internal fun im.g.gimcore.ChatSummary.toMirror() = ChatSummary(
    chatId = chatId,
    kind = kind.toMirror(),
    name = name,
    avatar = avatar?.toMirror(),
    lastMessage = lastMessage?.toMirror(),
    unreadCount = unreadCount.toInt(),
    updatedAtMs = updatedAtMs.toLong(),
    memberCount = memberCount.toInt(),
    state = state.toMirror(),
)

internal fun im.g.gimcore.ChatState.toMirror(): ChatState = when (this) {
    im.g.gimcore.ChatState.ACTIVE -> ChatState.ACTIVE
    im.g.gimcore.ChatState.LEAVING -> ChatState.LEAVING
    im.g.gimcore.ChatState.LEFT -> ChatState.LEFT
}

internal fun ChatState.toCore(): im.g.gimcore.ChatState = when (this) {
    ChatState.ACTIVE -> im.g.gimcore.ChatState.ACTIVE
    ChatState.LEAVING -> im.g.gimcore.ChatState.LEAVING
    ChatState.LEFT -> im.g.gimcore.ChatState.LEFT
}

internal fun im.g.gimcore.MessagePreview.toMirror() = MessagePreview(
    senderName = senderName,
    text = text,
    sentAtMs = sentAtMs.toLong(),
    isOwn = isOwn,
)

internal fun im.g.gimcore.TimelineItem.toMirror(): TimelineItem = when (this) {
    is im.g.gimcore.TimelineItem.Message -> TimelineItem.Message(item.toMirror())
    is im.g.gimcore.TimelineItem.DateSeparator -> TimelineItem.DateSeparator(dayStartMs.toLong())
    is im.g.gimcore.TimelineItem.MembershipChange ->
        TimelineItem.MembershipChange(
            userName = userName,
            change = change.toMirror(),
            atMs = atMs.toLong(),
            userId = userId,
            actorUserId = actorUserId,
        )
}

internal fun im.g.gimcore.MembershipChangeKind.toMirror(): MembershipChangeKind = when (this) {
    im.g.gimcore.MembershipChangeKind.ADDED -> MembershipChangeKind.ADDED
    im.g.gimcore.MembershipChangeKind.REMOVED -> MembershipChangeKind.REMOVED
    im.g.gimcore.MembershipChangeKind.LEFT -> MembershipChangeKind.LEFT
}

internal fun im.g.gimcore.MessageItem.toMirror() = MessageItem(
    messageId = messageId,
    chatId = chatId,
    sender = sender.toMirror(),
    isOwn = isOwn,
    sentAtMs = sentAtMs.toLong(),
    content = content.toMirror(),
    replyTo = replyTo?.toMirror(),
    reactions = reactions.map { it.toMirror() },
    edited = edited,
    sendState = sendState?.toMirror(),
)

internal fun im.g.gimcore.MessageContent.toMirror(): MessageContent = when (this) {
    is im.g.gimcore.MessageContent.Text -> MessageContent.Text(body)
    is im.g.gimcore.MessageContent.Image -> MessageContent.Image(
        media = media.toMirror(),
        width = width?.toInt(),
        height = height?.toInt(),
        caption = caption,
    )
    is im.g.gimcore.MessageContent.File ->
        MessageContent.File(media = media.toMirror(), fileName = fileName, caption = caption)
    im.g.gimcore.MessageContent.Deleted -> MessageContent.Deleted
    im.g.gimcore.MessageContent.Unsupported -> MessageContent.Unsupported
}

internal fun im.g.gimcore.MediaRef.toMirror() = MediaRef(
    chatId = chatId,
    messageId = messageId,
    mime = mime,
    size = size.toLong(),
    hasThumbnail = hasThumbnail,
)

internal fun im.g.gimcore.ReplyPreview.toMirror() = ReplyPreview(
    messageId = messageId,
    senderName = senderName,
    text = text,
)

internal fun im.g.gimcore.ReactionGroup.toMirror() = ReactionGroup(
    emoji = emoji,
    count = count.toInt(),
    own = own,
)

internal fun im.g.gimcore.SendState.toMirror(): SendState = when (this) {
    im.g.gimcore.SendState.Pending -> SendState.Pending
    im.g.gimcore.SendState.Sent -> SendState.Sent
    im.g.gimcore.SendState.Delivered -> SendState.Delivered
    im.g.gimcore.SendState.Read -> SendState.Read
    is im.g.gimcore.SendState.Failed -> SendState.Failed(reason)
}

internal fun im.g.gimcore.ConnectionState.toMirror(): ConnectionState = when (this) {
    im.g.gimcore.ConnectionState.OFFLINE -> ConnectionState.OFFLINE
    im.g.gimcore.ConnectionState.CONNECTING -> ConnectionState.CONNECTING
    im.g.gimcore.ConnectionState.CONNECTED -> ConnectionState.CONNECTED
    im.g.gimcore.ConnectionState.SYNCING -> ConnectionState.SYNCING
}

internal fun ClientConfig.toCore() = im.g.gimcore.ClientConfig(serverUrl = serverUrl, mode = mode.toCore())

internal fun ClientMode.toCore(): im.g.gimcore.ClientMode = when (this) {
    ClientMode.APP -> im.g.gimcore.ClientMode.APP
    ClientMode.EXTENSION -> im.g.gimcore.ClientMode.EXTENSION
}

internal fun PushRegistration.toCore() = im.g.gimcore.PushRegistration(
    provider = provider,
    token = token,
    appId = appId,
    sandbox = sandbox,
    p256dh = p256dh,
    auth = auth,
)

internal fun im.g.gimcore.NotificationKind.toMirror(): NotificationKind = when (this) {
    im.g.gimcore.NotificationKind.MESSAGE -> NotificationKind.MESSAGE
    im.g.gimcore.NotificationKind.REACTION -> NotificationKind.REACTION
    im.g.gimcore.NotificationKind.INVITE -> NotificationKind.INVITE
    im.g.gimcore.NotificationKind.MEMBERSHIP -> NotificationKind.MEMBERSHIP
    im.g.gimcore.NotificationKind.GENERIC -> NotificationKind.GENERIC
}

internal fun im.g.gimcore.NotificationContent.toMirror() = NotificationContent(
    chatId = chatId,
    chatTitle = chatTitle,
    senderUserId = senderUserId,
    senderDisplayName = senderDisplayName,
    body = body,
    messageId = messageId,
    collapseId = collapseId,
    kind = kind.toMirror(),
    shouldDisplay = shouldDisplay,
    timestamp = timestamp.toLong(),
)

internal fun im.g.gimcore.ChatListDiff.toMirror(): ChatListDiff = when (this) {
    is im.g.gimcore.ChatListDiff.Append -> ChatListDiff.Append(values.map { it.toMirror() })
    im.g.gimcore.ChatListDiff.Clear -> ChatListDiff.Clear
    is im.g.gimcore.ChatListDiff.PushFront -> ChatListDiff.PushFront(value.toMirror())
    is im.g.gimcore.ChatListDiff.PushBack -> ChatListDiff.PushBack(value.toMirror())
    im.g.gimcore.ChatListDiff.PopFront -> ChatListDiff.PopFront
    im.g.gimcore.ChatListDiff.PopBack -> ChatListDiff.PopBack
    is im.g.gimcore.ChatListDiff.Insert -> ChatListDiff.Insert(index.toInt(), value.toMirror())
    is im.g.gimcore.ChatListDiff.Set -> ChatListDiff.Set(index.toInt(), value.toMirror())
    is im.g.gimcore.ChatListDiff.Remove -> ChatListDiff.Remove(index.toInt())
    is im.g.gimcore.ChatListDiff.Truncate -> ChatListDiff.Truncate(length.toInt())
    is im.g.gimcore.ChatListDiff.Reset -> ChatListDiff.Reset(values.map { it.toMirror() })
}

internal fun im.g.gimcore.TimelineDiff.toMirror(): TimelineDiff = when (this) {
    is im.g.gimcore.TimelineDiff.Append -> TimelineDiff.Append(values.map { it.toMirror() })
    im.g.gimcore.TimelineDiff.Clear -> TimelineDiff.Clear
    is im.g.gimcore.TimelineDiff.PushFront -> TimelineDiff.PushFront(value.toMirror())
    is im.g.gimcore.TimelineDiff.PushBack -> TimelineDiff.PushBack(value.toMirror())
    im.g.gimcore.TimelineDiff.PopFront -> TimelineDiff.PopFront
    im.g.gimcore.TimelineDiff.PopBack -> TimelineDiff.PopBack
    is im.g.gimcore.TimelineDiff.Insert -> TimelineDiff.Insert(index.toInt(), value.toMirror())
    is im.g.gimcore.TimelineDiff.Set -> TimelineDiff.Set(index.toInt(), value.toMirror())
    is im.g.gimcore.TimelineDiff.Remove -> TimelineDiff.Remove(index.toInt())
    is im.g.gimcore.TimelineDiff.Truncate -> TimelineDiff.Truncate(length.toInt())
    is im.g.gimcore.TimelineDiff.Reset -> TimelineDiff.Reset(values.map { it.toMirror() })
}

internal fun im.g.gimcore.GimException.toMirror(): GimException = when (this) {
    is im.g.gimcore.GimException.Network -> GimException.Network(reason)
    is im.g.gimcore.GimException.NotRegistered -> GimException.NotRegistered()
    is im.g.gimcore.GimException.InvalidInput -> GimException.InvalidInput(reason)
    is im.g.gimcore.GimException.NotFound -> GimException.NotFound(reason)
    is im.g.gimcore.GimException.Crypto -> GimException.Crypto(reason)
    is im.g.gimcore.GimException.Storage -> GimException.Storage(reason)
    is im.g.gimcore.GimException.Server -> GimException.Server(status.toInt(), reason)
    is im.g.gimcore.GimException.RetryLater ->
        GimException.RetryLater(status.toInt(), retryAfterSecs.toLong(), reason)
    is im.g.gimcore.GimException.Cancelled -> GimException.Cancelled()
    is im.g.gimcore.GimException.NotImplemented -> GimException.NotImplemented()
    is im.g.gimcore.GimException.ChatNotActive -> GimException.ChatNotActive(chatId, state.toMirror())
    is im.g.gimcore.GimException.HubNotVerified -> GimException.HubNotVerified(reason)
    is im.g.gimcore.GimException.HubOwnsGroups -> GimException.HubOwnsGroups(hubId, groupCount.toInt())
    is im.g.gimcore.GimException.LastHub -> GimException.LastHub(hubId)
    is im.g.gimcore.GimException.HomeHub -> GimException.HomeHub(hubId)
    is im.g.gimcore.GimException.HubNotDrained -> GimException.HubNotDrained(hubId, reason)
    is im.g.gimcore.GimException.UserSuspended -> GimException.UserSuspended(hubId)
    is im.g.gimcore.GimException.Internal -> GimException.Internal(reason)
}.also { it.initCause(this) }

/** Reverse of [toMirror] for errors raised by mirror [im.g.apps.core.bridge.PlatformHooks] implementations. */
internal fun GimException.toCore(): im.g.gimcore.GimException = when (this) {
    is GimException.Network -> im.g.gimcore.GimException.Network(reason)
    is GimException.NotRegistered -> im.g.gimcore.GimException.NotRegistered()
    is GimException.InvalidInput -> im.g.gimcore.GimException.InvalidInput(reason)
    is GimException.NotFound -> im.g.gimcore.GimException.NotFound(reason)
    is GimException.Crypto -> im.g.gimcore.GimException.Crypto(reason)
    is GimException.Storage -> im.g.gimcore.GimException.Storage(reason)
    is GimException.Server -> im.g.gimcore.GimException.Server(status.toUShort(), reason)
    is GimException.RetryLater ->
        im.g.gimcore.GimException.RetryLater(status.toUShort(), retryAfterSecs.toULong(), reason)
    is GimException.Cancelled -> im.g.gimcore.GimException.Cancelled()
    is GimException.NotImplemented -> im.g.gimcore.GimException.NotImplemented()
    is GimException.ChatNotActive -> im.g.gimcore.GimException.ChatNotActive(chatId, state.toCore())
    is GimException.HubNotVerified -> im.g.gimcore.GimException.HubNotVerified(reason)
    is GimException.HubOwnsGroups -> im.g.gimcore.GimException.HubOwnsGroups(hubId, groupCount.toUInt())
    is GimException.LastHub -> im.g.gimcore.GimException.LastHub(hubId)
    is GimException.HomeHub -> im.g.gimcore.GimException.HomeHub(hubId)
    is GimException.HubNotDrained -> im.g.gimcore.GimException.HubNotDrained(hubId, reason)
    is GimException.UserSuspended -> im.g.gimcore.GimException.UserSuspended(hubId)
    is GimException.Internal -> im.g.gimcore.GimException.Internal(reason)
}.also { it.initCause(this) }
