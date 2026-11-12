package im.g.apps.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.flow.chatListFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A blocked user; [name] is the best known name, null when only the Account ID is known. */
@Immutable
data class BlockedPerson(val userId: String, val name: String?) {
    val label: String get() = name ?: userId
}

@Immutable
data class BlockedPeopleUiState(
    /** Null while loading or after a failed load. */
    val people: List<BlockedPerson>? = null,
    val loadFailed: Boolean = false,
    /** The person the unblock dialog asks about. */
    val confirm: BlockedPerson? = null,
    val unblocking: Boolean = false,
)

/**
 * The blocked users from [GimClient.privacy], named after their direct chat in the chat list when there is one.
 * Blocking happens from chat info; this screen only unblocks.
 */
class BlockedPeopleViewModel(
    private val client: GimClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(BlockedPeopleUiState())
    val state: StateFlow<BlockedPeopleUiState> = _state.asStateFlow()

    private val _messages = Channel<Int>(Channel.BUFFERED)

    /** Snackbar messages (string resources). */
    val messages: Flow<Int> = _messages.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loadFailed = false) }
        viewModelScope.launch {
            val people = try {
                withContext(ioDispatcher) {
                    val blocked = client.privacy().blockedUserIds
                    val peers = client.chatListFlow().first()
                        .filter { it.kind == ChatKind.DIRECT }
                        .mapNotNull { it.peer }
                        .associateBy { it.userId }
                    blocked.map { BlockedPerson(it, peers[it]?.bestName()) }
                }
            } catch (_: GimException) {
                _state.update { it.copy(people = null, loadFailed = true) }
                return@launch
            }
            _state.update { it.copy(people = people) }
        }
    }

    fun askUnblock(person: BlockedPerson) {
        if (!_state.value.unblocking) _state.update { it.copy(confirm = person) }
    }

    fun cancelUnblock() {
        if (!_state.value.unblocking) _state.update { it.copy(confirm = null) }
    }

    fun unblock() {
        val person = _state.value.confirm ?: return
        if (_state.value.unblocking) return
        _state.update { it.copy(unblocking = true) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { client.unblockUser(person.userId) }
                _state.update { state -> state.copy(people = state.people?.filter { it.userId != person.userId }) }
            } catch (_: GimException) {
                _messages.send(R.string.blocked_unblock_error)
            }
            _state.update { it.copy(confirm = null, unblocking = false) }
        }
    }

    /** The display name unless it is empty or the bare user id; null when not known. */
    private fun UserProfile.bestName(): String? = displayName.takeIf { it.isNotBlank() && it != userId }
}

fun blockedPeopleViewModelFactory(client: GimClient): ViewModelProvider.Factory = viewModelFactory {
    initializer { BlockedPeopleViewModel(client) }
}
