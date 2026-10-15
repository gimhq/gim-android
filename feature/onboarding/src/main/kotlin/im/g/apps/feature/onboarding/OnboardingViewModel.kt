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
 * New account: Welcome (server address) -> display name -> `createIdentity` -> recovery code (shown once, saving
 * must be confirmed) -> optional `claimHandle` -> [OnboardingEvent.Finished]. The server field starts with
 * [defaultServerUrl] (trimmed), validated like typed input. Field errors are computed as the user types; server-side
 * rejections replace them until the field changes.
 */
class OnboardingViewModel(
    private val manager: GimClientManager,
    defaultServerUrl: String = "",
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState.withServerUrl(defaultServerUrl.trim()))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events: Flow<OnboardingEvent> = _events.receiveAsFlow()

    /** Set by [submitDisplayName]; [claimHandle] uses the same client. */
    private var client: GimClient? = null

    fun onServerUrlChange(value: String) = edit {
        it.copy(serverUrl = value, serverUrlError = serverUrlError(value), error = null)
    }

    fun onDisplayNameChange(value: String) = edit {
        it.copy(
            displayName = value,
            displayNameError = FieldError.InvalidDisplayName.takeIf { value.isNotEmpty() && !isValidDisplayName(value) },
            error = null,
        )
    }

    fun onHandleChange(value: String) = edit {
        val handle = normalizeHandle(value)
        it.copy(
            handle = handle,
            handleError = FieldError.InvalidHandle.takeIf { handle.isNotEmpty() && !isValidHandle(handle) },
            error = null,
        )
    }

    /** Welcome -> display name; ignored unless the server address is valid. */
    fun createAccount() = _state.update {
        if (it.step != OnboardingStep.WELCOME || !it.canCreateAccount) it else it.copy(step = OnboardingStep.DISPLAY_NAME)
    }

    /** Display name -> Welcome. Ignored while loading and on the recovery code (it must be acknowledged). */
    fun back() = _state.update {
        if (it.step != OnboardingStep.DISPLAY_NAME || it.isLoading) it else it.copy(step = OnboardingStep.WELCOME, error = null)
    }

    /** Connects to the server and creates the identity; on success shows the recovery code. */
    fun submitDisplayName() {
        val current = _state.value
        if (current.step != OnboardingStep.DISPLAY_NAME || !current.canSubmitDisplayName) return
        _state.value = current.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val connected = try {
                manager.connect(current.serverUrl.trim())
            } catch (e: GimException) {
                fail(e) { loaded ->
                    if (e is GimException.InvalidInput) {
                        loaded.copy(step = OnboardingStep.WELCOME, serverUrlError = FieldError.InvalidServerUrl)
                    } else {
                        null
                    }
                }
                return@launch
            }
            client = connected
            try {
                val code = connected.createIdentity(current.displayName.trim())
                _state.update {
                    it.copy(isLoading = false, step = OnboardingStep.RECOVERY_CODE, recoveryCode = code)
                }
            } catch (e: GimException) {
                fail(e) { loaded ->
                    if (e is GimException.InvalidInput) loaded.copy(displayNameError = FieldError.InvalidDisplayName) else null
                }
            }
        }
    }

    fun onRecoveryCodeSavedChange(saved: Boolean) = _state.update {
        if (it.step != OnboardingStep.RECOVERY_CODE) it else it.copy(recoveryCodeSaved = saved)
    }

    /** Recovery code -> handle, once saving is confirmed. The code is dropped from the state for good. */
    fun acknowledgeRecoveryCode() = _state.update {
        if (it.step != OnboardingStep.RECOVERY_CODE || !it.recoveryCodeSaved) {
            it
        } else {
            it.copy(step = OnboardingStep.HANDLE, recoveryCode = null)
        }
    }

    /** Claims the handle and finishes; a taken handle (409) is reported on the field. */
    fun claimHandle() {
        val current = _state.value
        val connected = client
        if (current.step != OnboardingStep.HANDLE || !current.canClaimHandle || connected == null) return
        _state.value = current.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                connected.claimHandle(current.handle)
                _state.update { it.copy(isLoading = false) }
                _events.send(OnboardingEvent.Finished)
            } catch (e: GimException) {
                fail(e) { loaded ->
                    when {
                        e is GimException.Server && e.status == HTTP_CONFLICT -> loaded.copy(handleError = FieldError.HandleTaken)
                        e is GimException.InvalidInput -> loaded.copy(handleError = FieldError.InvalidHandle)
                        else -> null
                    }
                }
            }
        }
    }

    /** Finishes without a handle; one can be claimed later. */
    fun skipHandle() {
        val current = _state.value
        if (current.step != OnboardingStep.HANDLE || current.isLoading) return
        _events.trySend(OnboardingEvent.Finished)
    }

    /** Applies [transform] unless a call is running. */
    private fun edit(transform: (OnboardingUiState) -> OnboardingUiState) = _state.update {
        if (it.isLoading) it else transform(it)
    }

    /** Ends loading with [specific]'s field error, or a general error for [e]. */
    private fun fail(e: GimException, specific: (OnboardingUiState) -> OnboardingUiState?) = _state.update {
        val loaded = it.copy(isLoading = false)
        specific(loaded) ?: loaded.copy(
            error = when (e) {
                is GimException.Network -> OnboardingError.CannotReachServer
                is GimException.RetryLater -> OnboardingError.TryAgainLater(e.retryAfterSecs.coerceAtLeast(1))
                else -> OnboardingError.Generic
            },
        )
    }

    private companion object {
        const val HTTP_CONFLICT = 409
    }
}

fun onboardingViewModelFactory(manager: GimClientManager, defaultServerUrl: String = ""): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { OnboardingViewModel(manager, defaultServerUrl) }
    }
