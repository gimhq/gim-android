package im.g.apps.feature.settings

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

@Immutable
data class SettingsUiState(
    val displayName: String = "",
    /** The claimed username without "@"; null when none is claimed. */
    val username: String? = null,
    val accountId: String? = null,
    val readReceipts: Boolean = true,
    val typingIndicator: Boolean = true,
)

/**
 * The signed-in user's name, username and Account ID for the settings header, from [GimClient.session], and the
 * privacy switches, from [GimClient.privacy]. A switch moves at once and moves back if saving fails.
 */
class SettingsViewModel(
    private val client: GimClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _messages = Channel<Int>(Channel.BUFFERED)

    /** Snackbar messages (string resources). */
    val messages: Flow<Int> = _messages.receiveAsFlow()

    init {
        refresh()
    }

    /** Reloads the session and privacy settings, e.g. after a sub-screen changed the display name. */
    fun refresh() {
        viewModelScope.launch {
            val session = withContext(ioDispatcher) { client.session() } ?: return@launch
            val privacy = try {
                withContext(ioDispatcher) { client.privacy() }
            } catch (_: GimException) {
                null
            }
            _state.update {
                it.copy(
                    displayName = session.displayName,
                    username = session.handle.ifEmpty { null },
                    accountId = session.userId,
                    readReceipts = privacy?.readReceipts ?: it.readReceipts,
                    typingIndicator = privacy?.typingIndicators ?: it.typingIndicator,
                )
            }
        }
    }

    fun setReadReceipts(enabled: Boolean) =
        toggle(enabled, { client.setReadReceipts(it) }) { copy(readReceipts = it) }

    fun setTypingIndicator(enabled: Boolean) =
        toggle(enabled, { client.setTypingIndicators(it) }) { copy(typingIndicator = it) }

    private fun toggle(enabled: Boolean, save: suspend (Boolean) -> Unit, apply: SettingsUiState.(Boolean) -> SettingsUiState) {
        _state.update { it.apply(enabled) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { save(enabled) }
            } catch (_: GimException) {
                _state.update { it.apply(!enabled) }
                _messages.send(R.string.settings_privacy_error)
            }
        }
    }
}

fun settingsViewModelFactory(client: GimClient): ViewModelProvider.Factory = viewModelFactory {
    initializer { SettingsViewModel(client) }
}
