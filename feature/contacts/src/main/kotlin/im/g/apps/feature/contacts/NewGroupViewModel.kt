package im.g.apps.feature.contacts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.suspendedHubName
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Longest group name accepted by the UI. */
const val MAX_GROUP_NAME_LENGTH = 64

enum class NewGroupStep { MEMBERS, NAME }

@Immutable
data class NewGroupUiState(
    val step: NewGroupStep = NewGroupStep.MEMBERS,
    val query: String = "",
    val search: UserSearchState = UserSearchState.Idle,
    /** Selected members, not including the signed-in user. */
    val members: PersistentList<UserProfile> = persistentListOf(),
    val name: String = "",
    val selfUserId: String? = null,
    val creating: Boolean = false,
) {
    val canProceed: Boolean
        get() = members.isNotEmpty()

    val canCreate: Boolean
        get() = canProceed && name.trim().length in 1..MAX_GROUP_NAME_LENGTH && !creating

    /** Whether [user] can still be added: not the signed-in user and not selected yet. */
    fun canAdd(user: UserProfile): Boolean = user.userId != selfUserId && members.none { it.userId == user.userId }
}

sealed interface NewGroupEvent {
    data class OpenChat(val chatId: String) : NewGroupEvent

    data class ShowError(val error: ContactsError) : NewGroupEvent
}

/** Two-step group creation: pick members by handle, then name the group. */
class NewGroupViewModel(private val client: GimClient) : ViewModel() {
    private val _state = MutableStateFlow(NewGroupUiState())
    val state: StateFlow<NewGroupUiState> = _state.asStateFlow()

    private val _events = Channel<NewGroupEvent>(Channel.BUFFERED)
    val events: Flow<NewGroupEvent> = _events.receiveAsFlow()

    private val search = UserSearch(client, viewModelScope) { result -> _state.update { it.copy(search = result) } }

    init {
        viewModelScope.launch {
            val self = client.selfUserIdOrNull()
            _state.update { it.copy(selfUserId = self) }
        }
    }

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text) }
        search.onQueryChange(text)
    }

    /** Selects [user] and clears the search field. */
    fun addMember(user: UserProfile) {
        if (!_state.value.canAdd(user)) return
        _state.update { it.copy(members = it.members.add(user)) }
        onQueryChange("")
    }

    fun removeMember(userId: String) {
        _state.update { state -> state.copy(members = state.members.removeAll { it.userId == userId }) }
    }

    fun next() {
        _state.update { if (it.canProceed) it.copy(step = NewGroupStep.NAME) else it }
    }

    /** Goes back to member selection; returns false when already there (the caller leaves the screen). */
    fun back(): Boolean {
        if (_state.value.step == NewGroupStep.MEMBERS) return false
        _state.update { it.copy(step = NewGroupStep.MEMBERS) }
        return true
    }

    fun onNameChange(text: String) {
        _state.update { it.copy(name = text.take(MAX_GROUP_NAME_LENGTH)) }
    }

    fun create() {
        val current = _state.value
        if (!current.canCreate) return
        _state.update { it.copy(creating = true) }
        viewModelScope.launch {
            try {
                val chatId = client.createGroupChat(current.name.trim(), current.members.map { it.userId })
                _events.send(NewGroupEvent.OpenChat(chatId))
            } catch (e: GimException) {
                _events.send(NewGroupEvent.ShowError(e.toInviteError(client.suspendedHubName(e), current.members)))
            } finally {
                _state.update { it.copy(creating = false) }
            }
        }
    }
}

fun newGroupViewModelFactory(client: GimClient): ViewModelProvider.Factory = viewModelFactory {
    initializer { NewGroupViewModel(client) }
}
