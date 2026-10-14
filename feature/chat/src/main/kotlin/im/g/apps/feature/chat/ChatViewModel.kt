package im.g.apps.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.MediaRef
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.flow.chatListFlow
import im.g.apps.core.bridge.flow.itemsFlow
import im.g.apps.core.bridge.flow.typingFlow
import im.g.apps.feature.chat.timeline.ChatEvent
import im.g.apps.feature.chat.timeline.ChatHeaderUi
import im.g.apps.feature.chat.timeline.ChatUiState
import im.g.apps.feature.chat.timeline.MediaPathProvider
import im.g.apps.feature.chat.timeline.TimelineMapper
import im.g.apps.feature.chat.timeline.UiText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale

/**
 * Timeline of one chat. Subscribes to gim-core's timeline (oldest first), typing set and chat list row,
 * and builds [ChatUiState] (rows newest first) on [defaultDispatcher].
 */
@OptIn(FlowPreview::class)
class ChatViewModel(
    private val client: GimClient,
    private val chatId: String,
    private val mediaPaths: MediaPathProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
    defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    locale: Locale = Locale.getDefault(),
) : ViewModel() {

    /** Latest timeline facts needed by user actions; written off the main thread. */
    private class Snapshot(
        val messages: List<MessageItem> = emptyList(),
        val unreadCount: Int = 0,
    ) {
        val byId: Map<String, MessageItem> = messages.associateBy { it.messageId }
    }

    private data class Extras(
        val isLoadingOlder: Boolean,
        val hasMoreOlder: Boolean,
        val imagePaths: Map<String, String>,
        val avatarPath: String?,
    )

    private val mapper = TimelineMapper(zone, locale)
    private val timeline = viewModelScope.async { client.timeline(chatId) }
    private val isLoadingOlder = MutableStateFlow(false)
    private val hasMoreOlder = MutableStateFlow(true)
    private val imagePaths = MutableStateFlow(persistentMapOf<String, String>())
    private val avatarPath = MutableStateFlow<String?>(null)
    private val requestedImages = HashSet<String>()
    private val readRequests = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private var lastReadRequest: String? = null
    private val _events = Channel<ChatEvent>(Channel.BUFFERED)

    @Volatile
    private var snapshot = Snapshot()

    private val summary: Flow<ChatSummary?> = client.chatListFlow()
        .map { list -> list.firstOrNull { it.chatId == chatId } }
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val extras = combine(isLoadingOlder, hasMoreOlder, imagePaths, avatarPath, ::Extras)

    val uiState: StateFlow<ChatUiState> = combine(
        flow { emitAll(timeline.await().itemsFlow()) },
        client.typingFlow(chatId),
        summary,
        extras,
    ) { items, typing, summary, extras -> buildState(items, typing, summary, extras) }
        .flowOn(defaultDispatcher)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    val events: Flow<ChatEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            readRequests.debounce(MARK_READ_DEBOUNCE_MS).collect { messageId ->
                try {
                    client.markRead(chatId, messageId)
                } catch (_: GimException) {
                    if (lastReadRequest == messageId) lastReadRequest = null
                }
            }
        }
        viewModelScope.launch {
            summary.map { it?.avatar }.distinctUntilChanged().filterNotNull().collect { ref ->
                fetchImage(ref)?.let { avatarPath.value = it }
            }
        }
    }

    /** Loads older history; ignored while a page is loading or when the start has been reached. */
    fun loadOlder() {
        if (!hasMoreOlder.value || !isLoadingOlder.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                hasMoreOlder.value = timeline.await().paginateBackwards(PAGE_SIZE)
            } catch (_: GimException) {
                // Keep hasMoreOlder; scrolling again retries.
            } finally {
                isLoadingOlder.value = false
            }
        }
    }

    /**
     * Called while the screen is resumed with the newest visible message. Marks the chat read up to the
     * newest inbound message at or before it, debounced, and only while there are unread messages.
     */
    fun onVisible(newestVisibleMessageId: String) {
        val current = snapshot
        if (current.unreadCount == 0) return
        val visibleIndex = current.messages.indexOfFirst { it.messageId == newestVisibleMessageId }
        if (visibleIndex < 0) return
        val target = current.messages.subList(0, visibleIndex + 1).lastOrNull { !it.isOwn }?.messageId ?: return
        if (target == lastReadRequest) return
        lastReadRequest = target
        readRequests.tryEmit(target)
    }

    /** Fetches the image of [messageId] (thumbnail first, then full size) once; rows pick up the path. */
    fun onImageNeeded(messageId: String) {
        if (messageId in imagePaths.value || !requestedImages.add(messageId)) return
        val image = snapshot.byId[messageId]?.content as? MessageContent.Image
        if (image == null) {
            requestedImages.remove(messageId)
            return
        }
        viewModelScope.launch {
            try {
                if (image.media.hasThumbnail) {
                    client.fetchThumbnail(chatId, messageId, mediaPaths.thumbnailPath(image.media))
                        ?.let { imagePaths.value = imagePaths.value.put(messageId, it) }
                }
                val full = client.fetchMedia(chatId, messageId, mediaPaths.mediaPath(image.media, null))
                imagePaths.value = imagePaths.value.put(messageId, full)
            } catch (_: GimException) {
                requestedImages.remove(messageId)
            }
        }
    }

    /** Downloads the attachment of [messageId] and emits [ChatEvent.OpenFile]. */
    fun onOpenFile(messageId: String) {
        val file = snapshot.byId[messageId]?.content as? MessageContent.File ?: return
        viewModelScope.launch {
            try {
                val path = client.fetchMedia(chatId, messageId, mediaPaths.mediaPath(file.media, file.fileName))
                _events.send(ChatEvent.OpenFile(path, file.media.mime))
            } catch (_: GimException) {
                // Nothing to open.
            }
        }
    }

    /** Re-queues a failed outbound message. */
    fun retry(messageId: String) {
        viewModelScope.launch {
            try {
                client.retrySend(chatId, messageId)
            } catch (_: GimException) {
                // The message stays failed.
            }
        }
    }

    private suspend fun fetchImage(ref: MediaRef): String? = try {
        if (ref.hasThumbnail) {
            client.fetchThumbnail(ref.chatId, ref.messageId, mediaPaths.thumbnailPath(ref))
        } else {
            client.fetchMedia(ref.chatId, ref.messageId, mediaPaths.mediaPath(ref, null))
        }
    } catch (_: GimException) {
        null
    }

    private fun buildState(
        items: List<TimelineItem>,
        typing: List<String>,
        summary: ChatSummary?,
        extras: Extras,
    ): ChatUiState {
        val messages = items.mapNotNull { (it as? TimelineItem.Message)?.item }
        snapshot = Snapshot(messages, summary?.unreadCount ?: 0)
        val isGroup = summary?.kind == ChatKind.GROUP
        val typingLabel = typingLabel(typing, messages)
        val subtitle = when {
            typingLabel != null -> if (isGroup) typingLabel else UiText.Res(R.string.chat_typing)
            isGroup -> UiText.Plural(R.plurals.chat_members, summary?.memberCount ?: 0)
            else -> null
        }
        return ChatUiState(
            header = ChatHeaderUi(
                title = summary?.name.orEmpty(),
                subtitle = subtitle,
                avatarPath = extras.avatarPath,
                isGroup = isGroup,
            ),
            items = mapper.map(items, isGroup, extras.imagePaths, clock()),
            isLoadingOlder = extras.isLoadingOlder,
            hasMoreOlder = extras.hasMoreOlder,
            typingLabel = typingLabel,
            unreadCount = summary?.unreadCount ?: 0,
        )
    }

    private fun typingLabel(userIds: List<String>, messages: List<MessageItem>): UiText? {
        if (userIds.isEmpty()) return null
        val names = userIds.map { id -> messages.lastOrNull { it.sender.userId == id }?.sender?.displayName }
        return when {
            names.size == 1 -> names[0]?.let { UiText.Res(R.string.chat_typing_one, persistentListOf(it)) }
                ?: UiText.Res(R.string.chat_typing_someone)
            names.size == 2 && names.all { it != null } ->
                UiText.Res(R.string.chat_typing_two, persistentListOf(names[0]!!, names[1]!!))
            else -> UiText.Plural(R.plurals.chat_typing_many, names.size)
        }
    }

    companion object {
        const val PAGE_SIZE = 30
        const val MARK_READ_DEBOUNCE_MS = 500L

        fun factory(client: GimClient, chatId: String, mediaPaths: MediaPathProvider): ViewModelProvider.Factory =
            viewModelFactory { initializer { ChatViewModel(client, chatId, mediaPaths) } }
    }
}
