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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class NewChatUiState(
    val query: String = "",
    val search: UserSearchState = UserSearchState.Idle,
    val selfUserId: String? = null,
    val starting: Boolean = false,
    val scannerOpen: Boolean = false,
    val scanNotice: ScanNotice? = null,
) {
    /** A user other than the signed-in one was found and no chat is being created. */
    val canStartChat: Boolean
        get() = search is UserSearchState.Found && search.user.userId != selfUserId && !starting
}

/** Why a QR scan did not start a chat; shown in the scanner while it is open, else under the scan button. */
enum class ScanNotice {
    NOT_ACCOUNT_ID,
    OWN_CODE,
    CAMERA_DENIED,
    CAMERA_UNAVAILABLE,
}

sealed interface NewChatEvent {
    data class OpenChat(val chatId: String) : NewChatEvent

    data class ShowError(val error: ContactsError) : NewChatEvent
}

/**
 * Finds a user by handle (or takes a typed or scanned user id) and opens (or creates) the direct chat with them;
 * an unknown user id fails with [ContactsError.UserNotFound].
 */
class NewChatViewModel(private val client: GimClient) : ViewModel() {
    private val _state = MutableStateFlow(NewChatUiState())
    val state: StateFlow<NewChatUiState> = _state.asStateFlow()

    private val _events = Channel<NewChatEvent>(Channel.BUFFERED)
    val events: Flow<NewChatEvent> = _events.receiveAsFlow()

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

    fun startChat() {
        val current = _state.value
        if (!current.canStartChat) return
        openChatWith((current.search as UserSearchState.Found).user)
    }

    fun openScanner() {
        _state.update { it.copy(scannerOpen = true, scanNotice = null) }
    }

    fun closeScanner() {
        _state.update { it.copy(scannerOpen = false, scanNotice = null) }
    }

    fun onCameraDenied() {
        _state.update { it.copy(scannerOpen = false, scanNotice = ScanNotice.CAMERA_DENIED) }
    }

    fun onCameraUnavailable() {
        _state.update { it.copy(scannerOpen = false, scanNotice = ScanNotice.CAMERA_UNAVAILABLE) }
    }

    /**
     * Handles a QR code [text] from the open scanner: an Account ID of someone else closes the scanner and opens
     * the chat with them; anything else keeps scanning with a [ScanNotice]. Scans while a chat is being created
     * are ignored.
     */
    fun onQrScanned(text: String) {
        val current = _state.value
        if (!current.scannerOpen || current.starting) return
        val notice = when (val id = findAccountId(text)) {
            null -> ScanNotice.NOT_ACCOUNT_ID
            client.userId() -> ScanNotice.OWN_CODE
            else -> {
                _state.update { it.copy(scannerOpen = false, scanNotice = null) }
                openChatWith(UserProfile(id, "", ""))
                return
            }
        }
        _state.update { it.copy(scanNotice = notice) }
    }

    private fun openChatWith(user: UserProfile) {
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            try {
                _events.send(NewChatEvent.OpenChat(client.createDirectChat(user.userId)))
            } catch (e: GimException) {
                _events.send(NewChatEvent.ShowError(e.toInviteError(client.suspendedHubName(e), listOf(user))))
            } finally {
                _state.update { it.copy(starting = false) }
            }
        }
    }
}

fun newChatViewModelFactory(client: GimClient): ViewModelProvider.Factory = viewModelFactory {
    initializer { NewChatViewModel(client) }
}
