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
import java.io.File
import java.io.InputStream
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

private const val DISPLAY_NAME_MAX_LENGTH = 64

private const val JPEG_MIME = "image/jpeg"

/** The open dialog of the profile screen. */
enum class ProfileEditor { NAME }

enum class ProfileFieldError(@StringRes val message: Int) {
    NAME_INVALID(R.string.profile_error_name),
}

@Immutable
data class ProfileUiState(
    val displayName: String = "",
    val accountId: String = "",
    val editor: ProfileEditor? = null,
    /** A change is being saved; the dialog buttons are disabled. */
    val saving: Boolean = false,
    val nameError: ProfileFieldError? = null,
    /** Whether the user has a profile photo ([UserProfile.hasAvatar][im.g.apps.core.bridge.UserProfile.hasAvatar]). */
    val hasPhoto: Boolean = false,
    /** The downloaded photo; null shows the initials (no photo, or it can't be downloaded now). */
    val photoPath: String? = null,
    /** A photo change is being saved; the photo buttons are disabled. */
    val photoSaving: Boolean = false,
)

sealed interface ProfileEvent {
    /** A snackbar message. */
    data class Message(@StringRes val text: Int) : ProfileEvent

    /** Put [accountId] on the clipboard and say so. */
    data class CopyAccountId(val accountId: String) : ProfileEvent
}

/**
 * The own name, Account ID and photo; every change is saved through [GimClient] and then re-read from the
 * session and [GimClient.ownProfile].
 */
class ProfileViewModel(
    private val client: GimClient,
    private val photos: ProfilePhotos,
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

    fun cancelEdit() {
        if (_state.value.saving) return
        _state.update { it.copy(editor = null, nameError = null) }
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

    /**
     * Crops and scales the picture [open] reads (see [ProfilePhotos.prepare]) and sets it as the profile photo. A
     * picture that can't be read is not uploaded.
     */
    fun changePhoto(open: () -> InputStream?) {
        savePhoto(R.string.profile_photo_saved) {
            val file = photos.prepare(open) ?: return@savePhoto false
            client.setAvatar(file.path, JPEG_MIME)
            true
        }
    }

    fun removePhoto() {
        savePhoto(R.string.profile_photo_removed) {
            client.removeAvatar()
            true
        }
    }

    fun copyAccountId() {
        _state.value.accountId.takeIf { it.isNotEmpty() }?.let { _events.trySend(ProfileEvent.CopyAccountId(it)) }
    }

    private fun open(editor: ProfileEditor) {
        if (_state.value.saving) return
        _state.update { it.copy(editor = editor, nameError = null) }
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
        _state.update { it.copy(saving = true, nameError = null) }
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

    /**
     * Runs [call] off the main thread; false means the picture couldn't be read. On success reloads the profile and
     * shows [done]; any failure is a snackbar message.
     */
    private fun savePhoto(@StringRes done: Int, call: suspend () -> Boolean) {
        if (_state.value.photoSaving) return
        _state.update { it.copy(photoSaving = true) }
        viewModelScope.launch {
            val message = try {
                if (withContext(ioDispatcher) { call() }) {
                    reload()
                    done
                } else {
                    R.string.profile_photo_error_unreadable
                }
            } catch (e: GimException) {
                if (e is GimException.Network) R.string.profile_photo_error_network else R.string.profile_photo_error_unknown
            }
            _state.update { it.copy(photoSaving = false) }
            _events.send(ProfileEvent.Message(message))
        }
    }

    private suspend fun reload() {
        val session = try {
            withContext(ioDispatcher) { client.session() }
        } catch (_: GimException) {
            null
        } ?: return
        _state.update {
            it.copy(displayName = session.displayName, accountId = session.userId)
        }
        val hasPhoto = try {
            withContext(ioDispatcher) { client.ownProfile() }?.hasAvatar ?: false
        } catch (_: GimException) {
            _state.value.hasPhoto
        }
        val photoPath = if (hasPhoto) withContext(ioDispatcher) { photos.load(session.userId) } else null
        _state.update { it.copy(hasPhoto = hasPhoto, photoPath = photoPath) }
    }

    @StringRes
    private fun errorMessage(e: GimException): Int = when (e) {
        is GimException.Network -> R.string.profile_error_network
        else -> R.string.profile_error_unknown
    }
}

/** [cacheDir] holds the downloaded and the prepared photos (see [ProfilePhotos]). */
fun profileViewModelFactory(client: GimClient, cacheDir: File): ViewModelProvider.Factory = viewModelFactory {
    initializer { ProfileViewModel(client, ProfilePhotos(client, cacheDir)) }
}
