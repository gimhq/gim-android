package im.g.apps.core.bridge

import im.g.apps.core.bridge.diff.ListDiff

/** Receives chat list diffs, ordered by `lastActivityAt` descending. */
fun interface ChatListListener {
    fun onUpdate(diffs: List<ListDiff<ChatSummary>>)
}

/** Receives timeline diffs for one chat; index 0 is the newest item. */
fun interface TimelineListener {
    fun onUpdate(diffs: List<ListDiff<TimelineItem>>)
}

fun interface ConnectionListener {
    fun onState(state: ConnectionState)
}

/** Receives the full set of users currently typing in [chatId] (empty when nobody is). */
fun interface TypingListener {
    fun onTyping(chatId: ChatId, users: List<User>)
}

/** Handle returned by every `subscribe*` call; mirrors UniFFI's `TaskHandle`. */
fun interface Subscription {
    /** Stops delivery to the listener. Idempotent. */
    fun cancel()
}
