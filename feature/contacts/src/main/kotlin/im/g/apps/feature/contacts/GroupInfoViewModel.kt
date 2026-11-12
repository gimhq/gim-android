package im.g.apps.feature.contacts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.flow.chatListFlow
import im.g.apps.core.bridge.flow.itemsFlow
import im.g.apps.core.bridge.key
import im.g.apps.core.bridge.suspendedHubName
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import java.io.File

@Immutable
data class GroupMember(val user: UserProfile, val isSelf: Boolean)

/** The add-member dialog: an Account ID field. */
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
    /** Draft of the rename dialog; null when it is closed. */
    val renameDraft: String? = null,
    val busy: Boolean = false,
    /** The [chatActions] of [chat], in display order. */
    val chatActions: PersistentList<ChatAction> = persistentListOf(),
    /** The mute options dialog is open. */
    val choosingMute: Boolean = false,
    /** [chat] is muted until it is unmuted ([GimClient.mutedForever]). */
    val mutedForever: Boolean = false,
    /** The block (or, for a blocked peer, unblock) confirmation is open. */
    val confirmingBlock: Boolean = false,
    /** Downloaded photos by user id, and the chat's avatar under [chatPhotoKey]. */
    val photos: PersistentMap<String, String> = persistentMapOf(),
) {
    val isGroup: Boolean
        get() = chat?.kind == ChatKind.GROUP

    /** The leave was accepted and another member has not committed it yet ([ChatState.LEAVING]). */
    val leaving: Boolean
        get() = chat?.state == ChatState.LEAVING

    /** The signed-in user is no longer a member ([ChatState.LEFT]). */
    val left: Boolean
        get() = chat?.state == ChatState.LEFT

    /** The other user of a direct chat. */
    val peer: UserProfile?
        get() = members.firstOrNull { !it.isSelf }?.user

    val canRename: Boolean
        get() = renameDraft != null && renameDraft.trim().length in 1..MAX_GROUP_NAME_LENGTH && !busy

    /** The photo the chat list shows for [chat]: its own avatar, else a direct chat peer's photo. */
    val headerPhotoPath: String?
        get() {
            val chat = chat ?: return null
            chat.avatar?.let { return photos[chatPhotoKey(chat.chatId, it.messageId)] }
            return chat.peer?.takeIf { chat.kind == ChatKind.DIRECT && it.hasAvatar }?.let { photos[it.userId] }
        }

    /** Whether [user] can be added: not a member already. */
    fun canAdd(user: UserProfile): Boolean = members.none { it.user.userId == user.userId }
}

sealed interface GroupInfoEvent {
    data class ShowError(val error: ContactsError) : GroupInfoEvent

    /** The leave was submitted; back to the chat, which shows "Leaving…" until the own `Left` change arrives. */
    data object LeftChat : GroupInfoEvent

    /** Open the report about [userId], with messages from [chatId]. */
    data class OpenReport(val userId: String, val chatId: String) : GroupInfoEvent
}

/**
 * Details of one chat: the chat list row (observed through [chatListFlow]) plus [GimClient.chatMembers].
 * gim-core has no members listener, so the member list is re-queried whenever the chat's row changes,
 * the chat's timeline gains a membership change, or after this screen's own add/remove calls.
 * Groups support rename, add/remove member and leave; direct chats are read-only. Whether a leave is in progress (or
 * done) comes from the row's [ChatState], which gim-core keeps across restarts. Both offer the [chatActions] of the row;
 * report only when [reportAvailable] (a keep server is configured). [clock] decides whether the chat is still muted.
 * Photos (the chat's, members' and found users') are downloaded into [avatarDir].
 */
class GroupInfoViewModel(
    private val client: GimClient,
    private val chatId: String,
    avatarDir: File,
    private val reportAvailable: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(GroupInfoUiState())
    val state: StateFlow<GroupInfoUiState> = _state.asStateFlow()

    private val _events = Channel<GroupInfoEvent>(Channel.BUFFERED)
    val events: Flow<GroupInfoEvent> = _events.receiveAsFlow()

    private val memberRefreshes = Channel<Unit>(Channel.CONFLATED)
    private var selfUserId: String? = null

    private val photos = ContactPhotos(client, avatarDir, viewModelScope, defaultDispatcher) { key, path ->
        _state.update { it.copy(photos = it.photos.put(key, path)) }
    }

    private val search = UserSearch { result ->
        _state.update { state -> state.copy(addMemberDialog = state.addMemberDialog?.copy(search = result)) }
    }

    init {
        viewModelScope.launch {
            selfUserId = client.selfUserIdOrNull()
            launch { memberRefreshes.receiveAsFlow().collect { loadMembers() } }
            launch { observeChat() }
            launch { observeMembershipChanges() }
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
        mutate(error = { e -> client.inviteError(e, listOf(user)) }) { client.addMembers(chatId, listOf(user.userId)) }
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
        if (!_state.value.isGroup || _state.value.leaving || _state.value.left) return
        _state.update { it.copy(confirmingLeave = true) }
    }

    fun confirmLeave() {
        if (!_state.value.confirmingLeave) return
        _state.update { it.copy(confirmingLeave = false, busy = true) }
        viewModelScope.launch {
            try {
                client.leaveChat(chatId)
                _events.send(GroupInfoEvent.LeftChat)
            } catch (e: GimException) {
                _events.send(GroupInfoEvent.ShowError(e.toContactsError(client.suspendedHubName(e))))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // endregion

    // region Chat actions

    /** Runs [action] if it is offered; mute and block ask first, report opens the report screen. */
    fun onChatAction(action: ChatAction) {
        val current = _state.value
        val chat = current.chat ?: return
        if (action !in current.chatActions) return
        when (action) {
            ChatAction.MUTE -> _state.update { it.copy(choosingMute = true) }
            ChatAction.UNMUTE -> chatAction(action) { client.setChatMuted(chatId, 0) }
            ChatAction.PIN -> chatAction(action) { client.setChatPinned(chatId, true) }
            ChatAction.UNPIN -> chatAction(action) { client.setChatPinned(chatId, false) }
            ChatAction.ARCHIVE -> chatAction(action) { client.setChatArchived(chatId, true) }
            ChatAction.UNARCHIVE -> chatAction(action) { client.setChatArchived(chatId, false) }
            ChatAction.BLOCK, ChatAction.UNBLOCK -> _state.update { it.copy(confirmingBlock = true) }
            ChatAction.REPORT -> chat.peer?.let { peer ->
                viewModelScope.launch { _events.send(GroupInfoEvent.OpenReport(peer.userId, chatId)) }
            }
        }
    }

    fun mute(option: MuteOption) {
        if (!_state.value.choosingMute) return
        _state.update { it.copy(choosingMute = false) }
        chatAction(ChatAction.MUTE) { client.setChatMuted(chatId, option.mutedUntilMs(clock(), client.mutedForever())) }
    }

    /** Blocks the peer, or unblocks a blocked one; the new state arrives through the chat list row. */
    fun confirmBlock() {
        val current = _state.value
        val peer = current.chat?.peer ?: return
        if (!current.confirmingBlock) return
        _state.update { it.copy(confirmingBlock = false) }
        if (current.chat.blocked) {
            chatAction(ChatAction.UNBLOCK) { client.unblockUser(peer.userId) }
        } else {
            chatAction(ChatAction.BLOCK) { client.blockUser(peer.userId) }
        }
    }

    private fun chatAction(action: ChatAction, call: suspend () -> Unit) =
        mutate(refreshMembers = false, error = { e -> e.toChatActionError(action, client.suspendedHubName(e)) }, action = call)

    // endregion

    /** Closes whichever dialog is open. */
    fun dismissDialog() {
        _state.update {
            it.copy(
                addMemberDialog = null,
                pendingRemoval = null,
                confirmingLeave = false,
                renameDraft = null,
                choosingMute = false,
                confirmingBlock = false,
            )
        }
    }

    private fun mutate(
        refreshMembers: Boolean = true,
        error: suspend (GimException) -> ContactsError = { e -> e.toContactsError(client.suspendedHubName(e)) },
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
                    _state.update {
                        it.copy(
                            chat = row ?: it.chat,
                            missing = row == null,
                            chatActions = row?.let { chatActions(row, clock(), reportAvailable).toPersistentList() }
                                ?: it.chatActions,
                            mutedForever = row?.let { row.mutedUntilMs == client.mutedForever() } ?: it.mutedForever,
                        )
                    }
                    if (row != null) {
                        requestHeaderPhoto(row)
                        memberRefreshes.trySend(Unit)
                    }
                }
        } catch (e: GimException) {
            _events.send(GroupInfoEvent.ShowError(e.toContactsError(client.suspendedHubName(e))))
        }
    }

    /** See [GroupInfoUiState.headerPhotoPath]. */
    private fun requestHeaderPhoto(row: ChatSummary) {
        val avatar = row.avatar
        if (avatar != null) {
            photos.requestChat(row.chatId, avatar.messageId)
        } else if (row.kind == ChatKind.DIRECT) {
            row.peer?.let(photos::requestUser)
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
            members.forEach { photos.requestUser(it.user) }
        } catch (e: GimException.NotImplemented) {
            _state.update { it.copy(members = persistentListOf(), membersUnavailable = true) }
        } catch (e: GimException.NotFound) {
            // The chat is gone (left or removed); the chat list row reports that as `missing`.
        } catch (e: GimException) {
            _events.send(GroupInfoEvent.ShowError(e.toContactsError(client.suspendedHubName(e))))
        }
    }
}

/** [avatarDir] holds the downloaded photos (the app's `cacheDir/gim/avatars`). */
fun groupInfoViewModelFactory(
    client: GimClient,
    chatId: String,
    avatarDir: File,
    reportAvailable: Boolean = false,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { GroupInfoViewModel(client, chatId, avatarDir, reportAvailable) }
}
