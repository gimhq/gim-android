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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class DeleteAccountUiState(
    /** The "I understand" box; the delete button needs it. */
    val understood: Boolean = false,
    val deleting: Boolean = false,
    /** Why the last attempt failed (string resource); nothing was deleted. */
    @StringRes val error: Int? = null,
    val deleted: Boolean = false,
    /** Other servers that could not be reached; null when every server was. */
    val unreachable: ServerList? = null,
) {
    val canDelete: Boolean get() = understood && !deleting && !deleted
}

/**
 * "Delete account": the user ticks that they understand, then [deleteAccount] runs (the app container's, which also
 * wipes the phone like sign-out). It cannot be cancelled; a failure changes nothing and can be retried. The servers'
 * URLs are read from [client] before deleting, as the client is gone afterwards.
 */
class DeleteAccountViewModel(
    private val client: GimClient,
    private val deleteAccount: suspend () -> List<String>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(DeleteAccountUiState())
    val state: StateFlow<DeleteAccountUiState> = _state.asStateFlow()

    fun setUnderstood(value: Boolean) = _state.update { if (it.deleting || it.deleted) it else it.copy(understood = value) }

    fun delete() {
        if (!_state.value.canDelete) return
        _state.update { it.copy(deleting = true, error = null) }
        viewModelScope.launch {
            try {
                val unreachable = withContext(ioDispatcher) {
                    val urls = client.hubUrls()
                    deleteAccount().takeIf { it.isNotEmpty() }?.let { serverList(it, urls) }
                }
                _state.update { it.copy(deleting = false, deleted = true, unreachable = unreachable) }
            } catch (e: GimException) {
                _state.update { it.copy(deleting = false, error = errorFor(e)) }
            }
        }
    }

    @StringRes
    private fun errorFor(e: GimException): Int = when (e) {
        is GimException.Network -> R.string.delete_account_error_network
        is GimException.RetryLater -> R.string.delete_account_error_busy
        else -> R.string.delete_account_error_generic
    }
}

fun deleteAccountViewModelFactory(client: GimClient, deleteAccount: suspend () -> List<String>): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { DeleteAccountViewModel(client, deleteAccount) }
    }
