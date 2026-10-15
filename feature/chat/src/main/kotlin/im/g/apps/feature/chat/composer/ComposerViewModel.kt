package im.g.apps.feature.chat.composer

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.SendState
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.flow.itemsFlow
import im.g.apps.feature.chat.R
import im.g.apps.feature.chat.timeline.UiText
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Composer and message actions of one chat. Kept separate from [im.g.apps.feature.chat.ChatViewModel]
 * so the timeline stays read-only; it follows the same gim-core timeline to decide which actions a
 * long-pressed message offers.
 *
 * @param clock epoch-millisecond time source for the typing throttle.
 */
class ComposerViewModel(
    private val client: GimClient,
    private val chatId: String,
    private val uriCopier: UriCopier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposerUiState())
    val uiState: StateFlow<ComposerUiState> = _uiState.asStateFlow()

    private val _events = Channel<ComposerEvent>(Channel.BUFFERED)
    val events: Flow<ComposerEvent> = _events.receiveAsFlow()

    @Volatile
    private var messages: Map<String, MessageItem> = emptyMap()

    private var typingActive = false
    private var lastTypingSentMs = 0L
    private var typingIdleJob: Job? = null

    init {
        viewModelScope.launch {
            client.timeline(chatId).itemsFlow()
                .map { items -> items.mapNotNull { (it as? TimelineItem.Message)?.item }.associateBy { it.messageId } }
                .flowOn(defaultDispatcher)
                .collect { messages = it }
        }
    }

    fun onDraftChange(text: String) {
        _uiState.update { it.copy(draft = text) }
        if (_uiState.value.mode is ComposerMode.Editing) return
        if (text.isBlank()) {
            stopTyping()
            return
        }
        val now = clock()
        if (!typingActive || now - lastTypingSentMs >= TYPING_THROTTLE_MS) {
            typingActive = true
            lastTypingSentMs = now
            launchQuietly { client.setTyping(chatId, true) }
        }
        typingIdleJob?.cancel()
        typingIdleJob = viewModelScope.launch {
            delay(TYPING_IDLE_MS)
            stopTyping()
        }
    }

    /** Sends the draft as a new message, a reply, or an edit; blank drafts are ignored. */
    fun send() {
        val state = _uiState.value
        val body = state.draft.trim()
        if (body.isEmpty()) return
        val mode = state.mode
        _uiState.update { it.copy(draft = "", mode = ComposerMode.Normal) }
        stopTyping()
        viewModelScope.launch {
            when (mode) {
                is ComposerMode.Editing -> if (body != mode.originalText) {
                    runAction(R.string.composer_error_edit) { client.editMessage(chatId, mode.messageId, body) }
                }
                else -> runAction(R.string.composer_error_send) {
                    client.sendText(chatId, body, (mode as? ComposerMode.Replying)?.messageId)
                }
            }
        }
    }

    /** Leaves reply or edit mode; leaving edit mode also drops the edited text. */
    fun cancelMode() {
        _uiState.update {
            it.copy(mode = ComposerMode.Normal, draft = if (it.mode is ComposerMode.Editing) "" else it.draft)
        }
    }

    /** Copies the picked [uri] and sends it with the draft as caption, quoting the reply target if any. */
    fun onAttachmentPicked(uri: String) {
        if (!_uiState.value.canAttach) return
        _uiState.update { it.copy(isAttaching = true) }
        viewModelScope.launch {
            try {
                val result = try {
                    withContext(ioDispatcher) { uriCopier.copy(uri, MAX_ATTACHMENT_BYTES) }
                } catch (_: IOException) {
                    emitError(R.string.composer_error_attach)
                    return@launch
                } catch (_: SecurityException) {
                    emitError(R.string.composer_error_attach)
                    return@launch
                }
                when (result) {
                    CopyResult.TooLarge -> emitError(R.string.composer_error_too_large)
                    is CopyResult.Copied -> sendCopied(result)
                }
            } finally {
                _uiState.update { it.copy(isAttaching = false) }
            }
        }
    }

    /** Opens the actions sheet for [messageId]; ignored for messages not in the timeline. */
    fun onMessageLongPress(messageId: String) {
        val message = messages[messageId] ?: return
        _uiState.update { it.copy(actions = actionsFor(message)) }
    }

    fun dismissActions() = _uiState.update { it.copy(actions = null) }

    fun reply(messageId: String) {
        val message = takeAction(messageId) { it.canReply } ?: return
        _uiState.update {
            it.copy(
                mode = ComposerMode.Replying(messageId, message.sender.displayName, previewText(message.content)),
                draft = if (it.mode is ComposerMode.Editing) "" else it.draft,
            )
        }
    }

    fun edit(messageId: String) {
        val message = takeAction(messageId) { it.canEdit } ?: return
        val body = (message.content as MessageContent.Text).body
        stopTyping()
        _uiState.update { it.copy(mode = ComposerMode.Editing(messageId, body), draft = body) }
    }

    /** Asks for confirmation before [confirmDelete]. */
    fun requestDelete(messageId: String) {
        takeAction(messageId) { it.canDelete } ?: return
        _uiState.update { it.copy(confirmDeleteId = messageId) }
    }

    fun confirmDelete() {
        val messageId = _uiState.value.confirmDeleteId ?: return
        _uiState.update { it.copy(confirmDeleteId = null) }
        if (messages[messageId]?.let { canDelete(it) } != true) return
        _uiState.update { state ->
            val mode = state.mode
            val target = (mode as? ComposerMode.Replying)?.messageId ?: (mode as? ComposerMode.Editing)?.messageId
            when (target) {
                messageId -> state.copy(mode = ComposerMode.Normal, draft = if (mode is ComposerMode.Editing) "" else state.draft)
                else -> state
            }
        }
        viewModelScope.launch {
            runAction(R.string.composer_error_delete) { client.deleteMessage(chatId, messageId) }
        }
    }

    fun cancelDelete() = _uiState.update { it.copy(confirmDeleteId = null) }

    /** Adds [emoji] to [messageId], or removes it when the signed-in user already reacted with it. */
    fun toggleReaction(messageId: String, emoji: String) {
        val message = takeAction(messageId) { it.canReact } ?: return
        val own = message.reactions.any { it.emoji == emoji && it.own }
        viewModelScope.launch {
            runAction(R.string.composer_error_react) {
                if (own) client.unreact(chatId, messageId, emoji) else client.react(chatId, messageId, emoji)
            }
        }
    }

    fun retry(messageId: String) {
        takeAction(messageId) { it.canRetry } ?: return
        viewModelScope.launch { runAction(R.string.composer_error_retry) { client.retrySend(chatId, messageId) } }
    }

    fun cancelSend(messageId: String) {
        takeAction(messageId) { it.canRetry } ?: return
        viewModelScope.launch { runAction(R.string.composer_error_cancel) { client.cancelSend(chatId, messageId) } }
    }

    private suspend fun sendCopied(copied: CopyResult.Copied) {
        val state = _uiState.value
        val caption = state.draft.trim().ifEmpty { null }
        val replyTo = (state.mode as? ComposerMode.Replying)?.messageId
        _uiState.update { it.copy(draft = "", mode = ComposerMode.Normal) }
        stopTyping()
        try {
            runAction(R.string.composer_error_send) { client.sendMedia(chatId, copied.path, copied.mime, caption, replyTo) }
        } finally {
            // gim-core copies the file before sendMedia returns.
            withContext(ioDispatcher) {
                val file = File(copied.path)
                file.delete()
                file.parentFile?.delete()
            }
        }
    }

    /** Closes the sheet and returns the message when [allowed] holds for its current actions. */
    private fun takeAction(messageId: String, allowed: (MessageActionsUi) -> Boolean): MessageItem? {
        dismissActions()
        val message = messages[messageId] ?: return null
        return message.takeIf { allowed(actionsFor(it)) }
    }

    private fun stopTyping() {
        typingIdleJob?.cancel()
        typingIdleJob = null
        if (!typingActive) return
        typingActive = false
        launchQuietly { client.setTyping(chatId, false) }
    }

    private suspend fun runAction(@StringRes error: Int, action: suspend () -> Unit) {
        try {
            action()
        } catch (_: GimException) {
            emitError(error)
        }
    }

    private suspend fun emitError(@StringRes error: Int) = _events.send(ComposerEvent.Error(UiText.Res(error)))

    private fun launchQuietly(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (_: GimException) {
                // Typing notifications are best effort.
            }
        }
    }

    companion object {
        const val TYPING_THROTTLE_MS = 4_000L
        const val TYPING_IDLE_MS = 5_000L
        const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024

        fun factory(client: GimClient, chatId: String, uriCopier: UriCopier): ViewModelProvider.Factory =
            viewModelFactory { initializer { ComposerViewModel(client, chatId, uriCopier) } }

        internal fun actionsFor(message: MessageItem): MessageActionsUi {
            val content = message.content
            val deleted = content == MessageContent.Deleted
            return MessageActionsUi(
                messageId = message.messageId,
                canReply = !deleted,
                copyText = when (content) {
                    is MessageContent.Text -> content.body
                    is MessageContent.Image -> content.caption
                    is MessageContent.File -> content.caption
                    else -> null
                }?.takeIf { it.isNotBlank() },
                canEdit = message.isOwn && content is MessageContent.Text,
                canDelete = canDelete(message),
                canReact = !deleted,
                ownReactions = message.reactions.filter { it.own }.map { it.emoji }.toImmutableSet(),
                canRetry = message.isOwn && message.sendState is SendState.Failed,
            )
        }

        private fun canDelete(message: MessageItem) = message.isOwn && message.content != MessageContent.Deleted

        private fun previewText(content: MessageContent): UiText = when (content) {
            is MessageContent.Text -> UiText.Raw(content.body)
            is MessageContent.Image -> content.caption?.takeIf { it.isNotBlank() }?.let(UiText::Raw)
                ?: UiText.Res(R.string.chat_image)
            is MessageContent.File -> content.caption?.takeIf { it.isNotBlank() }?.let(UiText::Raw)
                ?: UiText.Raw(content.fileName ?: content.media.messageId)
            MessageContent.Deleted -> UiText.Res(R.string.chat_message_deleted)
            MessageContent.Unsupported -> UiText.Res(R.string.chat_message_unsupported)
        }
    }
}
