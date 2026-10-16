package im.g.apps.feature.contacts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.InMemoryPendingLeaveStore
import im.g.apps.core.bridge.PendingLeaveStore
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.flow.chatListFlow
import im.g.apps.core.bridge.flow.itemsFlow
import im.g.apps.core.bridge.key
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class GroupMember(val user: UserProfile, val isSelf: Boolean)

/** The add-member dialog: a handle search. */
@Immutable
data class AddMemberDialogState(
    val query: String = "",
    val search: UserSearchState = UserSearchState.Idle,
)

@Immutable
data class GroupInfoUiState(
    /** The chat list row of this chat; null while loading or when [missing]. */
    val chat: ChatSummary? = null,
    /** The chat is not (or no longer) in the chat list. */
    val missing: Boolean = false,
    val members: PersistentList<GroupMember> = persistentListOf(),
    /** gim-core cannot list members yet; show [ChatSummary.memberCount] instead. */
    val membersUnavailable: Boolean = false,
    val addMemberDialog: AddMemberDialogState? = null,
    val pendingRemoval: UserProfile? = null,
    val confirmingLeave: Boolean = false,
    /** The leave was submitted and the own `Left` change has not arrived yet. */
    val leaving: Boolean = false,
    /** Draft of the rename dialog; null when it is closed. */
    val renameDraft: String? = null,
    val busy: Boolean = false,
) {
    val isGroup: Boolean
        get() = chat?.kind == ChatKind.GROUP

    /** The other user of a direct chat. */
    val peer: UserProfile?
        get() = members.firstOrNull { !it.isSelf }?.user

    val canRename: Boolean
        get() = renameDraft != null && renameDraft.trim().length in 1..MAX_GROUP_NAME_LENGTH && !busy

    /** Whether [user] can be added: not a member already. */
    fun canAdd(user: UserProfile): Boolean = members.none { it.user.userId == user.userId }
}

sealed interface GroupInfoEvent {
    data class ShowError(val error: ContactsError) : GroupInfoEvent

    /** The leave was submitted; back to the chat, which shows it until the own `Left` change arrives. */
    data object LeftChat : GroupInfoEvent
}

/**
 * Details of one chat: the chat list row (observed through [chatListFlow]) plus [GimClient.chatMembers].
 * gim-core has no members listener, so the member list is re-queried whenever the chat's row changes,
 * the chat's timeline gains a membership change, or after this screen's own add/remove calls.
 * Groups support rename, add/remove member and leave; direct chats are read-only. A submitted leave is recorded in
 * [pendingLeaves] until the chat shows the own `Left` change.
 */
class GroupInfoViewModel(
    private val client: GimClient,
    private val chatId: String,
    private val pendingLeaves: PendingLeaveStore = InMemoryPendingLeaveStore(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(GroupInfoUiState())
    val state: StateFlow<GroupInfoUiState> = _state.asStateFlow()

    private val _events = Channel<GroupInfoEvent>(Channel.BUFFERED)
    val events: Flow<GroupInfoEvent> = _events.receiveAsFlow()

    private val memberRefreshes = Channel<Unit>(Channel.CONFLATED)
    private var selfUserId: String? = null

    private val search = UserSearch(client, viewModelScope) { result ->
        _state.update { state -> state.copy(addMemberDialog = state.addMemberDialog?.copy(search = result)) }
    }

    init {
        viewModelScope.launch {
            selfUserId = client.selfUserIdOrNull()
            launch { memberRefreshes.receiveAsFlow().collect { loadMembers() } }
            launch { observeChat() }
            launch { observeMembershipChanges() }
        }
        viewModelScope.launch {
            pendingLeaves.pending.map { chatId in it }.distinctUntilChanged().collect { leaving ->
                _state.update { it.copy(leaving = leaving) }
            }
        }
    }

    // region Add member

    fun openAddMember() {
        if (!_state.value.isGroup) return
        search.onQueryChange("")
        _state.update { it.copy(addMemberDialog = AddMemberDialogState()) }
    }

    fun onAddMemberQueryChange(text: String) {
        _state.update { state -> state.copy(addMemberDialog = state.addMemberDialog?.copy(query = text)) }
        search.onQueryChange(text)
    }

    fun addMember(user: UserProfile) {
        if (!_state.value.canAdd(user)) return
        _state.update { it.copy(addMemberDialog = null) }
        mutate(error = GimException::toInviteError) { client.addMembers(chatId, listOf(user.userId)) }
    }

    // endregion

    // region Remove member

    fun requestRemove(user: UserProfile) {
        if (!_state.value.isGroup || user.userId == selfUserId) return
        _state.update { it.copy(pendingRemoval = user) }
    }

    fun confirmRemove() {
        val user = _state.value.pendingRemoval ?: return
        _state.update { it.copy(pendingRemoval = null) }
        mutate { client.removeMember(chatId, user.userId) }
    }

    // endregion

    // region Rename

    fun startRename() {
        val chat = _state.value.chat ?: return
        if (chat.kind != ChatKind.GROUP) return
        _state.update { it.copy(renameDraft = chat.name) }
    }

    fun onRenameChange(text: String) {
        _state.update { it.copy(renameDraft = text.take(MAX_GROUP_NAME_LENGTH)) }
    }

    fun confirmRename() {
        val current = _state.value
        if (!current.canRename) return
        val name = current.renameDraft.orEmpty().trim()
        _state.update { it.copy(renameDraft = null) }
        // The new name arrives through the chat list row.
        mutate(refreshMembers = false) { client.setGroupInfo(chatId, name, null) }
    }

    // endregion

    // region Leave

    fun requestLeave() {
        if (!_state.value.isGroup || _state.value.leaving) return
        _state.update { it.copy(confirmingLeave = true) }
    }

    fun confirmLeave() {
        if (!_state.value.confirmingLeave) return
        _state.update { it.copy(confirmingLeave = false, busy = true) }
        viewModelScope.launch {
            try {
                val requestedAtMs = clock()
                client.leaveChat(chatId)
                pendingLeaves.add(chatId, requestedAtMs)
                _events.send(GroupInfoEvent.LeftChat)
            } catch (e: GimException) {
                _events.send(GroupInfoEvent.ShowError(e.toContactsError()))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // endregion

    /** Closes whichever dialog is open. */
    fun dismissDialog() {
        _state.update {
            it.copy(addMemberDialog = null, pendingRemoval = null, confirmingLeave = false, renameDraft = null)
        }
    }

    private fun mutate(
        refreshMembers: Boolean = true,
        error: (GimException) -> ContactsError = GimException::toContactsError,
        action: suspend () -> Unit,
    ) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                action()
                if (refreshMembers) memberRefreshes.trySend(Unit)
            } catch (e: GimException) {
                _events.send(GroupInfoEvent.ShowError(error(e)))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun observeChat() {
        try {
            client.chatListFlow()
                .map { rows -> rows.firstOrNull { it.chatId == chatId } }
                .distinctUntilChanged()
                .collect { row ->
                    _state.update { it.copy(chat = row ?: it.chat, missing = row == null) }
                    if (row != null) memberRefreshes.trySend(Unit)
                }
        } catch (e: GimException) {
            _events.send(GroupInfoEvent.ShowError(e.toContactsError()))
        }
    }

    private suspend fun observeMembershipChanges() {
        try {
            var seen: Set<String>? = null
            client.timeline(chatId).itemsFlow().collect { items ->
                val changes = items.filterIsInstance<TimelineItem.MembershipChange>().mapTo(HashSet()) { it.key }
                if (seen?.containsAll(changes) == false) memberRefreshes.trySend(Unit)
                seen = changes
            }
        } catch (e: GimException) {
            // The chat-list row still drives refreshes; a missing timeline is not worth an error message.
        }
    }

    private suspend fun loadMembers() {
        try {
            val self = selfUserId
            val members = client.chatMembers(chatId).map { GroupMember(it, it.userId == self) }.toPersistentList()
            _state.update { it.copy(members = members, membersUnavailable = false) }
        } catch (e: GimException.NotImplemented) {
            _state.update { it.copy(members = persistentListOf(), membersUnavailable = true) }
        } catch (e: GimException.NotFound) {
            // The chat is gone (left or removed); the chat list row reports that as `missing`.
        } catch (e: GimException) {
            _events.send(GroupInfoEvent.ShowError(e.toContactsError()))
        }
    }
}

fun groupInfoViewModelFactory(
    client: GimClient,
    chatId: String,
    pendingLeaves: PendingLeaveStore,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { GroupInfoViewModel(client, chatId, pendingLeaves) }
}
