package im.g.apps.feature.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.displayNameOrShortId
import im.g.apps.core.bridge.flow.chatListFlow
import im.g.apps.core.bridge.flow.connectionStateFlow
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Destination file for a chat avatar thumbnail, keyed by the avatar's [chatId] and media [messageId]. */
typealias ThumbnailPathProvider = (chatId: String, messageId: String) -> String

/**
 * Chat list screen state from gim-core's chat list and connection state. Rows are shown as delivered by
 * the client (offline included); avatar thumbnails are fetched once per avatar and patched in when ready.
 * Row times are re-formatted on every [ticker] emission so relative labels follow the clock.
 */
class ChatListViewModel(
    private val client: GimClient,
    clock: Clock,
    private val thumbnailPath: ThumbnailPathProvider,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    locale: Locale = Locale.getDefault(),
    ticker: Flow<Unit> = minuteTicker(clock),
) : ViewModel() {
    private data class MediaKey(val chatId: String, val messageId: String)

    private val timeFormatter = ChatTimeFormatter(clock, locale)
    private val thumbnails = MutableStateFlow(persistentMapOf<MediaKey, String>())
    private val requestedThumbnails = ConcurrentHashMap.newKeySet<MediaKey>()

    val uiState: StateFlow<ChatListUiState> = combine(
        client.chatListFlow().onEach(::requestThumbnails),
        client.connectionStateFlow(),
        thumbnails,
        ticker,
    ) { chats, connection, thumbs, _ ->
        ChatListUiState(
            rows = chats.sortedByDescending { it.updatedAtMs }.map { it.toRow(thumbs) }.toImmutableList(),
            connection = connection.toBanner(),
            isEmpty = chats.isEmpty(),
        )
    }
        .flowOn(defaultDispatcher)
        // Non-fusible step: without it stateIn adopts flowOn's dispatcher and writes the StateFlow off main.
        .onEach {}
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChatListUiState())

    private fun requestThumbnails(chats: List<ChatSummary>) {
        for (chat in chats) {
            val avatar = chat.avatar?.takeIf { it.hasThumbnail } ?: continue
            val key = MediaKey(chat.chatId, avatar.messageId)
            if (!requestedThumbnails.add(key)) continue
            viewModelScope.launch(defaultDispatcher) {
                val path = try {
                    client.fetchThumbnail(key.chatId, key.messageId, thumbnailPath(key.chatId, key.messageId))
                } catch (_: GimException) {
                    null
                }
                if (path != null) thumbnails.update { it.put(key, path) }
            }
        }
    }

    private fun ChatSummary.toRow(thumbs: Map<MediaKey, String>): ChatRowUi {
        val last = lastMessage
        val sender = when {
            last == null -> null
            last.isOwn -> PreviewSender.You
            kind == ChatKind.GROUP -> PreviewSender.Member(firstName(displayNameOrShortId(last.senderName)))
            else -> null
        }
        return ChatRowUi(
            id = chatId,
            title = displayNameOrShortId(name),
            avatarPath = avatar?.let { thumbs[MediaKey(chatId, it.messageId)] },
            sender = sender,
            preview = last?.text.orEmpty(),
            time = timeFormatter.format(updatedAtMs),
            unreadCount = unreadCount,
            state = state,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        fun firstName(name: String): String = name.trim().substringBefore(' ')

        fun ConnectionState.toBanner(): ConnectionBannerUi? = when (this) {
            ConnectionState.OFFLINE -> ConnectionBannerUi.WAITING_FOR_NETWORK
            ConnectionState.CONNECTING -> ConnectionBannerUi.CONNECTING
            ConnectionState.SYNCING -> ConnectionBannerUi.UPDATING
            ConnectionState.CONNECTED -> null
        }
    }
}

/** Emits on collection and then at the start of every minute in [clock]'s zone, local midnight included. */
internal fun minuteTicker(clock: Clock): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(millisUntilNextMinute(clock))
    }
}

internal fun millisUntilNextMinute(clock: Clock): Long {
    val now = ZonedDateTime.now(clock)
    val next = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
    return Duration.between(now, next).toMillis().coerceAtLeast(1)
}

fun chatListViewModelFactory(
    client: GimClient,
    thumbnailPath: ThumbnailPathProvider,
    clock: Clock = Clock.systemDefaultZone(),
): ViewModelProvider.Factory = viewModelFactory {
    initializer { ChatListViewModel(client, clock, thumbnailPath) }
}
