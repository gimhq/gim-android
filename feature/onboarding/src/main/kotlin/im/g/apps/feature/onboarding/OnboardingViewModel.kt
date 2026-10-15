package im.g.apps.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.GimException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Registration: connects to the server URL through [manager], then registers handle and display name.
 * Field errors are computed as the user types; server-side rejections replace them until the field changes.
 */
class OnboardingViewModel(private val manager: GimClientManager) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events: Flow<OnboardingEvent> = _events.receiveAsFlow()

    fun onServerUrlChange(value: String) = _state.update {
        if (it.isLoading) return@update it
        it.copy(
            serverUrl = value,
            serverUrlError = FieldError.InvalidServerUrl.takeIf { value.isNotBlank() && !isValidServerUrl(value) },
            error = null,
        )
    }

    fun onHandleChange(value: String) = _state.update {
        if (it.isLoading) return@update it
        val handle = normalizeHandle(value)
        it.copy(
            handle = handle,
            handleError = FieldError.InvalidHandle.takeIf { handle.isNotEmpty() && !isValidHandle(handle) },
            error = null,
        )
    }

    fun onDisplayNameChange(value: String) = _state.update {
        if (it.isLoading) return@update it
        it.copy(
            displayName = value,
            displayNameError = FieldError.InvalidDisplayName.takeIf { value.isNotEmpty() && !isValidDisplayName(value) },
            error = null,
        )
    }

    /** Ignored while a submission is running or when a field is invalid. */
    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.value = current.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val client: GimClient = try {
                manager.connect(current.serverUrl.trim())
            } catch (e: GimException) {
                fail(e, step = Step.Connect)
                return@launch
            }
            try {
                val session = client.register(current.handle, current.displayName.trim())
                _state.update { it.copy(isLoading = false) }
                _events.send(OnboardingEvent.Registered(session))
            } catch (e: GimException) {
                fail(e, step = Step.Register)
            }
        }
    }

    private fun fail(e: GimException, step: Step) = _state.update {
        val loaded = it.copy(isLoading = false)
        when {
            e is GimException.Network -> loaded.copy(error = OnboardingError.CannotReachServer)
            e is GimException.Server && e.status == HTTP_CONFLICT -> loaded.copy(handleError = FieldError.HandleTaken)
            e is GimException.InvalidInput && step == Step.Connect ->
                loaded.copy(serverUrlError = FieldError.InvalidServerUrl)
            e is GimException.InvalidInput && e.reason.startsWith("displayName") ->
                loaded.copy(displayNameError = FieldError.InvalidDisplayName)
            e is GimException.InvalidInput -> loaded.copy(handleError = FieldError.InvalidHandle)
            else -> loaded.copy(error = OnboardingError.Generic)
        }
    }

    private enum class Step { Connect, Register }

    private companion object {
        const val HTTP_CONFLICT = 409
    }
}

fun onboardingViewModelFactory(manager: GimClientManager): ViewModelProvider.Factory = viewModelFactory {
    initializer { OnboardingViewModel(manager) }
}
