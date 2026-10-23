package im.g.apps.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.GimClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class SettingsUiState(
    val displayName: String = "",
    /** The claimed username without "@"; null when none is claimed. */
    val username: String? = null,
    val accountId: String? = null,
)

/** The signed-in user's name, username and Account ID for the settings header, from [GimClient.session]. */
class SettingsViewModel(
    private val client: GimClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Reloads the session, e.g. after a sub-screen changed the display name. */
    fun refresh() {
        viewModelScope.launch {
            val session = withContext(ioDispatcher) { client.session() } ?: return@launch
            _state.value = SettingsUiState(
                displayName = session.displayName,
                username = session.handle.ifEmpty { null },
                accountId = session.userId,
            )
        }
    }
}

fun settingsViewModelFactory(client: GimClient): ViewModelProvider.Factory = viewModelFactory {
    initializer { SettingsViewModel(client) }
}
