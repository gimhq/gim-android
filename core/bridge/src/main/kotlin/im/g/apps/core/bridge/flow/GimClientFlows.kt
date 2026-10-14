package im.g.apps.core.bridge.flow

import im.g.apps.core.bridge.ChatId
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.User
import im.g.apps.core.bridge.diff.applyDiffs
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/** Chat list snapshots, newest activity first. Cancels the subscription when collection stops. */
fun GimClient.chatListFlow(): Flow<ImmutableList<ChatSummary>> = callbackFlow {
    var current = emptyList<ChatSummary>()
    val subscription = subscribeChatList { diffs ->
        current = current.applyDiffs(diffs)
        trySend(current.toImmutableList())
    }
    awaitClose { subscription.cancel() }
}.conflate()

/** Timeline snapshots for [chatId], newest first. */
fun GimClient.timelineFlow(chatId: ChatId): Flow<ImmutableList<TimelineItem>> = callbackFlow {
    var current = emptyList<TimelineItem>()
    val subscription = subscribeTimeline(chatId) { diffs ->
        current = current.applyDiffs(diffs)
        trySend(current.toImmutableList())
    }
    awaitClose { subscription.cancel() }
}.conflate()

fun GimClient.connectionFlow(): Flow<ConnectionState> = callbackFlow {
    val subscription = subscribeConnection { state -> trySend(state) }
    awaitClose { subscription.cancel() }
}.conflate().distinctUntilChanged()

/** Users currently typing in [chatId]; starts with an empty list. */
fun GimClient.typingFlow(chatId: ChatId): Flow<ImmutableList<User>> = callbackFlow<ImmutableList<User>> {
    val subscription = subscribeTyping { typingChatId, users ->
        if (typingChatId == chatId) trySend(users.toImmutableList())
    }
    awaitClose { subscription.cancel() }
}.onStart { emit(persistentListOf()) }.conflate().distinctUntilChanged()
