package im.g.apps.core.bridge.flow

import im.g.apps.core.bridge.ChatListDiff
import im.g.apps.core.bridge.ChatListListener
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.ConnectionStateListener
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.TaskHandle
import im.g.apps.core.bridge.Timeline
import im.g.apps.core.bridge.TimelineDiff
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.TimelineListener
import im.g.apps.core.bridge.TypingListener
import im.g.apps.core.bridge.applyDiffs
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

// Listener callbacks arrive on foreign threads. Each callback only enqueues its payload into an unlimited
// channel (never dropped, kept in callback order); diffs are applied by the collector, one batch at a
// time, so emissions are consistent snapshots. The subscription's TaskHandle is closed when collection
// stops.

/** Chat list snapshots, most recently updated first. */
fun GimClient.chatListFlow(): Flow<ImmutableList<ChatSummary>> =
    listenerFlow<List<ChatListDiff>> { chatList(ChatListListener { diffs -> trySend(diffs) }) }
        .snapshots<ChatSummary, ChatListDiff> { diffs -> applyDiffs(diffs) }

/** Timeline snapshots, oldest first, as delivered by gim-core. */
fun Timeline.itemsFlow(): Flow<ImmutableList<TimelineItem>> =
    listenerFlow<List<TimelineDiff>> { addListener(TimelineListener { diffs -> trySend(diffs) }) }
        .snapshots<TimelineItem, TimelineDiff> { diffs -> applyDiffs(diffs) }

/** Connection state; starts with the current state. */
fun GimClient.connectionStateFlow(): Flow<ConnectionState> =
    listenerFlow<ConnectionState> { connectionState(ConnectionStateListener { state -> trySend(state) }) }

/** User ids currently typing in [chatId] (never the signed-in user); starts with the current set. */
fun GimClient.typingFlow(chatId: String): Flow<List<String>> =
    listenerFlow<List<String>> { typing(chatId, TypingListener { userIds -> trySend(userIds) }) }

private fun <T> listenerFlow(subscribe: suspend ProducerScope<T>.() -> TaskHandle): Flow<T> =
    callbackFlow {
        val handle = subscribe()
        awaitClose { handle.close() }
    }.buffer(Channel.UNLIMITED)

private fun <T, D> Flow<List<D>>.snapshots(apply: MutableList<T>.(List<D>) -> Unit): Flow<ImmutableList<T>> =
    flow {
        var current: PersistentList<T> = persistentListOf()
        collect { diffs ->
            current = current.mutate { it.apply(diffs) }
            emit(current)
        }
    }
