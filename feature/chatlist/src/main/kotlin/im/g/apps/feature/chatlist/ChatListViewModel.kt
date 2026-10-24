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
import im.g.apps.core.bridge.SendState
import im.g.apps.core.bridge.displayNameOrShortId
import im.g.apps.core.bridge.flow.chatListFlow
import im.g.apps.core.bridge.flow.connectionStateFlow
import im.g.apps.core.designsystem.component.TickState
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
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat list screen state from gim-core's chat list and connection state. Rows are shown as delivered by
 * the client (offline included), archived chats apart; photos (a chat's own, else a direct chat peer's) are
 * downloaded once into [avatarDir] and patched in when ready. Row times and muted marks are re-computed on every
 * [ticker] emission so they follow the clock.
 */
class ChatListViewModel(
    private val client: GimClient,
    private val clock: Clock,
    private val avatarDir: File,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    locale: Locale = Locale.getDefault(),
    ticker: Flow<Unit> = minuteTicker(clock),
) : ViewModel() {
    /** A downloadable photo: a chat's avatar (one per avatar message) or a user's profile photo. */
    private sealed interface PhotoKey {
        data class Chat(val chatId: String, val messageId: String) : PhotoKey

        data class User(val userId: String) : PhotoKey
    }

    private val timeFormatter = ChatTimeFormatter(clock, locale)
    private val photos = MutableStateFlow(persistentMapOf<PhotoKey, String>())
    private val requestedPhotos = ConcurrentHashMap.newKeySet<PhotoKey>()
    private val ownPhoto = MutableStateFlow<String?>(null)
    private val actionError = MutableStateFlow<ChatListAction?>(null)

    val uiState: StateFlow<ChatListUiState> = combine(
        combine(client.chatListFlow().onEach(::requestPhotos), client.connectionStateFlow(), ::Pair),
        photos,
        ownPhoto,
        actionError,
        ticker,
    ) { (chats, connection), photoPaths, ownPhotoPath, error, _ ->
        val nowMs = clock.millis()
        val (archived, rows) = chats.sortedWith(CHAT_ORDER).map { it.toRow(photoPaths, nowMs) }.partition { it.archived }
        ChatListUiState(
            rows = rows.toImmutableList(),
            archivedRows = archived.toImmutableList(),
            connection = connection.toBanner(),
            isEmpty = chats.isEmpty(),
            ownPhotoPath = ownPhotoPath,
            actionError = error,
        )
    }
        .flowOn(defaultDispatcher)
        // Non-fusible step: without it stateIn adopts flowOn's dispatcher and writes the StateFlow off main.
        .onEach {}
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChatListUiState())

    /** Re-reads the own profile and downloads the own photo when there is one; call when the screen is shown. */
    fun refreshOwnPhoto() {
        viewModelScope.launch(defaultDispatcher) {
            ownPhoto.value = try {
                val own = client.ownProfile()?.takeIf { it.hasAvatar }
                own?.let { client.fetchUserAvatar(it.userId, File(avatarDir, it.userId).path) }
            } catch (_: GimException) {
                null
            }
        }
    }

    /** Runs a long-press [action] on [chatId]; [ChatListAction.MUTE] needs a duration, see [mute]. */
    fun onAction(chatId: String, action: ChatListAction) {
        when (action) {
            // The screen asks for how long first and then calls mute().
            ChatListAction.MUTE -> Unit
            ChatListAction.UNMUTE -> chatAction(action) { client.setChatMuted(chatId, 0) }
            ChatListAction.PIN -> chatAction(action) { client.setChatPinned(chatId, true) }
            ChatListAction.UNPIN -> chatAction(action) { client.setChatPinned(chatId, false) }
            ChatListAction.ARCHIVE -> chatAction(action) { client.setChatArchived(chatId, true) }
            ChatListAction.UNARCHIVE -> chatAction(action) { client.setChatArchived(chatId, false) }
        }
    }

    fun mute(chatId: String, option: MuteOption) = chatAction(ChatListAction.MUTE) {
        client.setChatMuted(chatId, option.mutedUntilMs(clock.millis(), client.mutedForever()))
    }

    /** The [ChatListUiState.actionError] message was shown. */
    fun errorShown() {
        actionError.value = null
    }

    private fun chatAction(action: ChatListAction, call: suspend () -> Unit) {
        viewModelScope.launch(defaultDispatcher) {
            try {
                call()
            } catch (_: GimException) {
                actionError.value = action
            }
        }
    }

    private fun requestPhotos(chats: List<ChatSummary>) {
        for (chat in chats) {
            val key = chat.photoKey() ?: continue
            if (!requestedPhotos.add(key)) continue
            viewModelScope.launch(defaultDispatcher) {
                val path = try {
                    when (key) {
                        is PhotoKey.Chat ->
                            client.fetchChatAvatar(key.chatId, File(avatarDir, "chat-${key.chatId}-${key.messageId}").path)
                        is PhotoKey.User -> client.fetchUserAvatar(key.userId, File(avatarDir, key.userId).path)
                    }
                } catch (_: GimException) {
                    null
                }
                if (path != null) photos.update { it.put(key, path) }
            }
        }
    }

    /** The chat's own avatar, else the photo of a direct chat's peer who has one. */
    private fun ChatSummary.photoKey(): PhotoKey? {
        avatar?.let { return PhotoKey.Chat(chatId, it.messageId) }
        return peer?.takeIf { kind == ChatKind.DIRECT && it.hasAvatar }?.let { PhotoKey.User(it.userId) }
    }

    private fun ChatSummary.toRow(photoPaths: Map<PhotoKey, String>, nowMs: Long): ChatRowUi {
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
            avatarPath = photoKey()?.let { photoPaths[it] },
            sender = sender,
            preview = last?.text.orEmpty(),
            time = timeFormatter.format(updatedAtMs),
            unreadCount = unreadCount,
            state = state,
            muted = mutedUntilMs > nowMs,
            pinned = pinned,
            archived = archived,
            tick = last?.takeIf { it.isOwn }?.sendState?.toTick(),
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** gim-core's order (pinned first, then the most recently updated), kept even if a client delivers rows out of order. */
        val CHAT_ORDER = compareByDescending<ChatSummary> { it.pinned }.thenByDescending { it.updatedAtMs }

        fun SendState.toTick(): TickState = when (this) {
            SendState.Pending -> TickState.PENDING
            SendState.Sent -> TickState.SENT
            SendState.Delivered -> TickState.DELIVERED
            SendState.Read -> TickState.READ
            is SendState.Failed -> TickState.FAILED
        }

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

/**
 * [avatarDir] holds the downloaded photos (the app's `cacheDir/gim/avatars`). [locale] is read once; key the
 * ViewModel on it so a language change gets new formatters.
 */
fun chatListViewModelFactory(
    client: GimClient,
    avatarDir: File,
    clock: Clock = Clock.systemDefaultZone(),
    locale: Locale = Locale.getDefault(),
): ViewModelProvider.Factory = viewModelFactory {
    initializer { ChatListViewModel(client, clock, avatarDir, locale = locale) }
}
