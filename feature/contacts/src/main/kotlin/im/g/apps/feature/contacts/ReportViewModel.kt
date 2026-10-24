package im.g.apps.feature.contacts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.ReportReason
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.displayLabel
import im.g.apps.core.bridge.flow.chatListFlow
import im.g.apps.core.bridge.flow.itemsFlow
import im.g.apps.core.bridge.suspendedHubName
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Most messages one report can carry (gim-core's limit). */
const val MAX_REPORT_MESSAGES = 50

/** Longest report note (gim-core's limit). */
const val MAX_REPORT_NOTE_LENGTH = 1000

/** A message of the reported user that can go with the report: its text or media caption. */
@Immutable
data class ReportMessage(val messageId: String, val text: String, val sentAtMs: Long)

@Immutable
data class ReportUiState(
    /** The reported user's name; null until known. */
    val name: String? = null,
    val reason: ReportReason? = null,
    val note: String = "",
    /** Their messages in the chat's loaded timeline, newest first; null while loading. */
    val messages: PersistentList<ReportMessage>? = null,
    val selected: PersistentSet<String> = persistentSetOf(),
    val alsoBlock: Boolean = false,
    val sending: Boolean = false,
    /** The report was sent; show the confirmation. */
    val sent: Boolean = false,
    /** "Also block" blocked them after the report. */
    val blocked: Boolean = false,
    /** "Also block" failed after the report was sent. */
    val blockFailed: Boolean = false,
) {
    val canSend: Boolean
        get() = reason != null && !sending && !sent

    /** Whether [messageId] can be toggled: picked already, or fewer than [MAX_REPORT_MESSAGES] are picked. */
    fun canPick(messageId: String): Boolean = messageId in selected || selected.size < MAX_REPORT_MESSAGES
}

sealed interface ReportEvent {
    data class ShowError(val error: ContactsError) : ReportEvent
}

/**
 * Reports [userId] to the meta server's moderators with a reason, an optional note and up to [MAX_REPORT_MESSAGES]
 * of their messages from [chatId] ([messageId] picked to start with), then blocks them if asked to.
 */
class ReportViewModel(
    private val client: GimClient,
    private val userId: String,
    private val chatId: String,
    private val messageId: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    private val _events = Channel<ReportEvent>(Channel.BUFFERED)
    val events: Flow<ReportEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun onReasonChange(reason: ReportReason) {
        _state.update { it.copy(reason = reason) }
    }

    fun onNoteChange(text: String) {
        _state.update { it.copy(note = text.take(MAX_REPORT_NOTE_LENGTH)) }
    }

    fun toggleMessage(messageId: String) {
        _state.update {
            when {
                messageId in it.selected -> it.copy(selected = it.selected.remove(messageId))
                it.canPick(messageId) -> it.copy(selected = it.selected.add(messageId))
                else -> it
            }
        }
    }

    fun onAlsoBlockChange(alsoBlock: Boolean) {
        _state.update { it.copy(alsoBlock = alsoBlock) }
    }

    fun send() {
        val current = _state.value
        val reason = current.reason ?: return
        if (!current.canSend) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                val messageIds = current.messages.orEmpty().map { it.messageId }.filter { it in current.selected }
                client.reportUser(userId, reason, current.note.trim().ifEmpty { null }, messageIds)
            } catch (e: GimException) {
                _state.update { it.copy(sending = false) }
                _events.send(ReportEvent.ShowError(e.toChatActionError(ChatAction.REPORT, client.suspendedHubName(e))))
                return@launch
            }
            val blocked = current.alsoBlock && try {
                client.blockUser(userId)
                true
            } catch (_: GimException) {
                false
            }
            _state.update {
                it.copy(sending = false, sent = true, blocked = blocked, blockFailed = current.alsoBlock && !blocked)
            }
        }
    }

    private suspend fun load() {
        val theirs = try {
            client.timeline(chatId).itemsFlow().first()
                .filterIsInstance<TimelineItem.Message>()
                .map { it.item }
                .filter { !it.isOwn && it.sender.userId == userId }
        } catch (e: GimException) {
            _events.send(ReportEvent.ShowError(e.toContactsError(client.suspendedHubName(e))))
            emptyList()
        }
        val messages = theirs.asReversed().mapNotNull { message ->
            message.content.reportText()?.let { ReportMessage(message.messageId, it, message.sentAtMs) }
        }
        val name = theirs.lastOrNull()?.sender?.displayLabel ?: peerName()
        _state.update { state ->
            state.copy(
                name = name,
                messages = messages.toPersistentList(),
                selected = messages.filter { it.messageId == messageId }.map { it.messageId }.toPersistentSet(),
            )
        }
    }

    /** The name of the chat's peer when it is the reported user. */
    private suspend fun peerName(): String? = try {
        client.chatListFlow().first().firstOrNull { it.chatId == chatId }?.peer?.takeIf { it.userId == userId }?.displayLabel
    } catch (_: GimException) {
        null
    }
}

/** What a report carries of a message: its text or media caption; null when there is none. */
private fun MessageContent.reportText(): String? = when (this) {
    is MessageContent.Text -> body
    is MessageContent.Image -> caption
    is MessageContent.File -> caption
    MessageContent.Deleted, MessageContent.Unsupported -> null
}?.takeIf { it.isNotBlank() }

fun reportViewModelFactory(
    client: GimClient,
    userId: String,
    chatId: String,
    messageId: String? = null,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { ReportViewModel(client, userId, chatId, messageId) }
}
