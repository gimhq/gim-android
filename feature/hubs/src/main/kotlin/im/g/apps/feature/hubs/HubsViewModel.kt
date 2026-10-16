package im.g.apps.feature.hubs

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.HubInfo
import im.g.apps.core.bridge.HubState
import im.g.apps.core.bridge.flow.hubListFlow
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** User-facing errors of the hubs screen; mapped from [GimException] by [toHubsError]. */
sealed interface HubsError {
    /** [GimClient.addHub]: not an `http(s)://host` URL, or no gim hub there. */
    data object InvalidUrl : HubsError

    data object Unreachable : HubsError

    /** [GimClient.addHub]: the hub did not prove its identity at that URL. */
    data object NotVerified : HubsError

    data class RetryLater(val seconds: Long) : HubsError

    /** [GimClient.removeHub] of a hub owning chats: ask, then remove with `confirmOwnedGroups`. */
    data class OwnsGroups(val hubId: String, val groupCount: Int) : HubsError

    data object HomeHub : HubsError

    data object LastHub : HubsError

    data object NotDrained : HubsError

    /** [GimClient.setPrimaryHub]: a hub whose priority changes has no session on this device. */
    data object CannotMakePrimary : HubsError

    /** [GimClient.removeHub] of a hub the hosting record no longer lists. */
    data object NotListed : HubsError

    data object NotRegistered : HubsError

    data object Server : HubsError

    data object Unknown : HubsError
}

/** The open "add hub" dialog. */
@Immutable
data class AddHubState(
    val url: String = "",
    val submitting: Boolean = false,
    /** Shown inline in the dialog. */
    val error: HubsError? = null,
)

@Immutable
data class HubsUiState(
    /** Listed hubs, primary first. */
    val hubs: PersistentList<HubInfo> = persistentListOf(),
    /** Hubs no longer listed by the hosting record. */
    val removed: PersistentList<HubInfo> = persistentListOf(),
    val loading: Boolean = true,
    /** A make-primary or remove call is running. */
    val working: Boolean = false,
    val addHub: AddHubState? = null,
    /** The latest error of a row action (or the owned-groups confirmation), cleared by [HubsViewModel.errorShown]. */
    val error: HubsError? = null,
) {
    fun canMakePrimary(hub: HubInfo): Boolean = !hub.isPrimary && hub.state == HubState.VERIFIED

    fun canRemove(hub: HubInfo): Boolean = hub.state != HubState.REMOVED
}

/**
 * Lists the user's hubs ([GimClient.hubList]) and adds, reprioritizes and removes them. The subscription ends with
 * [viewModelScope], which closes its handle.
 */
class HubsViewModel(private val client: GimClient) : ViewModel() {
    private val _state = MutableStateFlow(HubsUiState())
    val state: StateFlow<HubsUiState> = _state.asStateFlow()

    init {
        client.hubListFlow()
            .onEach { hubs -> _state.update { it.withHubs(hubs) } }
            .catch { e ->
                if (e !is GimException) throw e
                _state.update { it.copy(loading = false, error = e.toHubsError(invalidInput = HubsError.Unknown)) }
            }
            .launchIn(viewModelScope)
    }

    fun openAddHub() {
        _state.update { it.copy(addHub = AddHubState()) }
    }

    fun addHubUrlChanged(url: String) {
        _state.update { it.copy(addHub = it.addHub?.copy(url = url, error = null)) }
    }

    fun dismissAddHub() {
        _state.update { if (it.addHub?.submitting == true) it else it.copy(addHub = null) }
    }

    fun submitAddHub() {
        val add = _state.value.addHub ?: return
        if (add.submitting || add.url.isBlank()) return
        _state.update { it.copy(addHub = add.copy(submitting = true, error = null)) }
        viewModelScope.launch {
            try {
                client.addHub(add.url.trim())
                _state.update { it.copy(addHub = null) }
            } catch (e: GimException) {
                val error = e.toHubsError(invalidInput = HubsError.InvalidUrl)
                _state.update { it.copy(addHub = it.addHub?.copy(submitting = false, error = error)) }
            }
        }
    }

    fun makePrimary(hub: HubInfo) {
        if (!_state.value.canMakePrimary(hub)) return
        act(invalidInput = HubsError.CannotMakePrimary) { client.setPrimaryHub(hub.hubId) }
    }

    fun remove(hub: HubInfo) {
        if (!_state.value.canRemove(hub)) return
        act(invalidInput = HubsError.NotListed) { client.removeHub(hub.hubId, confirmOwnedGroups = false) }
    }

    /** The user accepted the [HubsError.OwnsGroups] warning. */
    fun confirmRemoveOwnedGroups() {
        val owns = _state.value.error as? HubsError.OwnsGroups ?: return
        _state.update { it.copy(error = null) }
        act(invalidInput = HubsError.NotListed) { client.removeHub(owns.hubId, confirmOwnedGroups = true) }
    }

    fun errorShown() {
        _state.update { it.copy(error = null) }
    }

    /** Runs a row action; the list itself follows from [GimClient.hubList]. */
    private fun act(invalidInput: HubsError, block: suspend () -> Unit) {
        if (_state.value.working) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: GimException) {
                _state.update { it.copy(error = e.toHubsError(invalidInput)) }
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }
}

private fun HubsUiState.withHubs(all: List<HubInfo>): HubsUiState {
    val (removed, listed) = all.partition { it.state == HubState.REMOVED }
    return copy(hubs = listed.toPersistentList(), removed = removed.toPersistentList(), loading = false)
}

/**
 * Maps every [GimException] of the hub calls; [invalidInput] is what [GimException.InvalidInput] means for the call
 * (a bad URL for `addHub`, a hub without a session for `setPrimaryHub`, an unlisted hub for `removeHub`).
 */
internal fun GimException.toHubsError(invalidInput: HubsError): HubsError = when (this) {
    is GimException.InvalidInput -> invalidInput
    is GimException.Network -> HubsError.Unreachable
    is GimException.HubNotVerified -> HubsError.NotVerified
    is GimException.RetryLater -> HubsError.RetryLater(retryAfterSecs)
    is GimException.HubOwnsGroups -> HubsError.OwnsGroups(hubId, groupCount)
    is GimException.HomeHub -> HubsError.HomeHub
    is GimException.LastHub -> HubsError.LastHub
    is GimException.HubNotDrained -> HubsError.NotDrained
    is GimException.NotRegistered -> HubsError.NotRegistered
    is GimException.Server -> HubsError.Server
    is GimException.NotFound,
    is GimException.Crypto,
    is GimException.Storage,
    is GimException.Cancelled,
    is GimException.NotImplemented,
    is GimException.ChatNotActive,
    is GimException.Internal,
    -> HubsError.Unknown
}

fun hubsViewModelFactory(client: GimClient): ViewModelProvider.Factory = viewModelFactory {
    initializer { HubsViewModel(client) }
}
