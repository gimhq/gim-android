package im.g.apps.push

import im.g.apps.core.bridge.NotificationContent
import im.g.apps.core.bridge.NotificationKind

fun notificationContent(
    body: String = "Anchored off Hydra",
    chatId: String? = "chat-1",
    chatTitle: String? = "Aegean crew",
    sender: String? = "Peer",
    collapseId: String? = "m-1",
    kind: NotificationKind = NotificationKind.MESSAGE,
    shouldDisplay: Boolean = true,
) = NotificationContent(
    chatId = chatId,
    chatTitle = chatTitle,
    senderUserId = sender?.let { "u-$it" },
    senderDisplayName = sender,
    body = body,
    messageId = collapseId,
    collapseId = collapseId,
    kind = kind,
    shouldDisplay = shouldDisplay,
    timestamp = 1_760_600_000_000,
)
