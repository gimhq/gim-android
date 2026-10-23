package im.g.apps.feature.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** gim-hub's directory rule for usernames (handles). */
private val HANDLE_PATTERN = Regex("^[a-z0-9_]{3,32}$")

private const val DISPLAY_NAME_MAX_LENGTH = 64

private const val HTTP_CONFLICT = 409

/** Trims, drops a leading `@` and lowercases what the user typed, like onboarding. */
internal fun normalizeHandle(value: String): String = value.trim().removePrefix("@").lowercase()

/** The open dialog of the profile screen. */
enum class ProfileEditor { NAME, USERNAME, REMOVE_USERNAME }

enum class ProfileFieldError(@StringRes val message: Int) {
    NAME_INVALID(R.string.profile_error_name),
    USERNAME_INVALID(R.string.profile_error_username),
    USERNAME_TAKEN(R.string.profile_error_username_taken),
}

@Immutable
data class ProfileUiState(
    val displayName: String = "",
    /** The claimed username without "@"; null when none is claimed. */
    val username: String? = null,
    val accountId: String = "",
    val editor: ProfileEditor? = null,
    /** A change is being saved; the dialog buttons are disabled. */
    val saving: Boolean = false,
    val nameError: ProfileFieldError? = null,
    val usernameError: ProfileFieldError? = null,
)

sealed interface ProfileEvent {
    /** A snackbar message. */
    data class Message(@StringRes val text: Int) : ProfileEvent

    /** Put [accountId] on the clipboard and say so. */
    data class CopyAccountId(val accountId: String) : ProfileEvent
}

/** The own name, username and Account ID; every change is saved through [GimClient] and then re-read from the session. */
class ProfileViewModel(
    private val client: GimClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private val _events = Channel<ProfileEvent>(Channel.BUFFERED)
    val events: Flow<ProfileEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun editName() = open(ProfileEditor.NAME)

    fun editUsername() = open(ProfileEditor.USERNAME)

    fun askRemoveUsername() = open(ProfileEditor.REMOVE_USERNAME)

    fun cancelEdit() {
        if (_state.value.saving) return
        _state.update { it.copy(editor = null, nameError = null, usernameError = null) }
    }

    /** Trims [input]; 1 to 64 characters, else a field error without calling the SDK. */
    fun saveName(input: String) {
        val name = input.trim()
        if (name.length !in 1..DISPLAY_NAME_MAX_LENGTH) {
            _state.update { it.copy(nameError = ProfileFieldError.NAME_INVALID) }
            return
        }
        save(R.string.profile_name_saved, { client.setDisplayName(name) }) { e ->
            if (e is GimException.InvalidInput) copy(nameError = ProfileFieldError.NAME_INVALID) else null
        }
    }

    /** Claims the normalized [input]; also changes a claimed username (the hub replaces it, no release needed). */
    fun saveUsername(input: String) {
        val handle = normalizeHandle(input)
        if (!HANDLE_PATTERN.matches(handle)) {
            _state.update { it.copy(usernameError = ProfileFieldError.USERNAME_INVALID) }
            return
        }
        save(R.string.profile_username_saved, { client.claimHandle(handle) }) { e ->
            when {
                e is GimException.Server && e.status == HTTP_CONFLICT -> copy(usernameError = ProfileFieldError.USERNAME_TAKEN)
                e is GimException.InvalidInput -> copy(usernameError = ProfileFieldError.USERNAME_INVALID)
                else -> null
            }
        }
    }

    /** Releases the username; "none claimed" means it is already gone, so the state is just reloaded. */
    fun removeUsername() {
        save(R.string.profile_username_removed, {
            try {
                client.releaseHandle()
            } catch (_: GimException.InvalidInput) {
                // None claimed: nothing to remove.
            }
        })
    }

    fun copyAccountId() {
        _state.value.accountId.takeIf { it.isNotEmpty() }?.let { _events.trySend(ProfileEvent.CopyAccountId(it)) }
    }

    private fun open(editor: ProfileEditor) {
        if (_state.value.saving) return
        _state.update { it.copy(editor = editor, nameError = null, usernameError = null) }
    }

    /**
     * Runs [call] off the main thread; on success reloads the session, closes the dialog and shows [done]. A failure
     * [fieldError] maps to a field error keeps the dialog open; any other failure is a snackbar message.
     */
    private fun save(
        @StringRes done: Int,
        call: suspend () -> Unit,
        fieldError: ProfileUiState.(GimException) -> ProfileUiState? = { null },
    ) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, nameError = null, usernameError = null) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { call() }
            } catch (e: GimException) {
                val withError = _state.value.fieldError(e)
                _state.value = (withError ?: _state.value).copy(saving = false)
                if (withError == null) _events.send(ProfileEvent.Message(errorMessage(e)))
                return@launch
            }
            reload()
            _state.update { it.copy(saving = false, editor = null) }
            _events.send(ProfileEvent.Message(done))
        }
    }

    private suspend fun reload() {
        val session = try {
            withContext(ioDispatcher) { client.session() }
        } catch (_: GimException) {
            null
        } ?: return
        _state.update {
            it.copy(displayName = session.displayName, username = session.handle.ifEmpty { null }, accountId = session.userId)
        }
    }

    @StringRes
    private fun errorMessage(e: GimException): Int = when (e) {
        is GimException.Network -> R.string.profile_error_network
        else -> R.string.profile_error_unknown
    }
}

fun profileViewModelFactory(client: GimClient): ViewModelProvider.Factory = viewModelFactory {
    initializer { ProfileViewModel(client) }
}
