package im.g.apps.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.GimException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

/**
 * New account: Welcome (server address) -> display name -> `createIdentity` -> recovery code (shown once, saving
 * must be confirmed) -> optional `claimHandle` -> [OnboardingEvent.Finished]. The server field starts with
 * [defaultServerUrl] (trimmed), validated like typed input. Field errors are computed as the user types; server-side
 * rejections replace them until the field changes.
 *
 * Link to an existing device: Welcome -> `startLink` QR code while `waitLink` runs (at most [LINK_TIMEOUT]) -> SAS
 * -> `confirmLink(true)` -> [OnboardingEvent.Finished]. Rejecting the SAS or cancelling the wait drops the client
 * through [discardClient] (gim-core requires a new client) and returns to Welcome.
 *
 * Restore after losing every device: Welcome -> user id + recovery code -> `restoreFromRecovery` (optionally
 * revoking every other device) -> [OnboardingEvent.Finished]. The code is kept as normalized symbols while typing.
 *
 * A failed `createIdentity` or `restoreFromRecovery` discards the client and its partial local state, so a retry
 * (also with another server address) is idempotent.
 */
class OnboardingViewModel(
    private val manager: GimClientManager,
    defaultServerUrl: String = "",
    private val discardClient: suspend () -> Unit = { manager.signOutQuietly() },
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState.withServerUrl(defaultServerUrl.trim()))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events: Flow<OnboardingEvent> = _events.receiveAsFlow()

    /** Set by [submitDisplayName] or [linkDevice]; later steps use the same client. */
    private var client: GimClient? = null

    /** The running `waitLink`. */
    private var linkJob: Job? = null

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

    fun onRestoreUserIdChange(value: String) = edit {
        val userId = normalizeUserId(value)
        it.copy(
            restoreUserId = userId,
            restoreUserIdError = FieldError.InvalidUserId.takeIf { userId.isNotEmpty() && !isValidUserId(userId) },
            error = null,
        )
    }

    fun onRestoreCodeChange(value: String) = edit {
        val symbols = normalizeRecoveryCode(value)
        it.copy(
            restoreCode = symbols,
            restoreCodeError = FieldError.InvalidRecoveryCode.takeIf { isBrokenRecoveryCode(symbols) },
            error = null,
        )
    }

    fun onRevokeOtherDevicesChange(revoke: Boolean) = edit { it.copy(revokeOtherDevices = revoke) }

    /** Welcome -> display name; ignored unless the server address is valid. */
    fun createAccount() = _state.update {
        if (it.step != OnboardingStep.WELCOME || !it.canCreateAccount) it else it.copy(step = OnboardingStep.DISPLAY_NAME)
    }

    /** Welcome -> restore form; ignored unless the server address is valid. */
    fun startRestore() = _state.update {
        if (it.step != OnboardingStep.WELCOME || !it.canCreateAccount) it else it.copy(step = OnboardingStep.RESTORE)
    }

    /**
     * Display name or restore form -> Welcome; on the link QR code cancels the link like [cancelLink]. Ignored while
     * loading, on the recovery code (it must be acknowledged) and on the SAS (it must be confirmed or rejected).
     */
    fun back() {
        if (_state.value.step == OnboardingStep.LINK_QR) {
            cancelLink()
            return
        }
        _state.update {
            val leaves = it.step == OnboardingStep.DISPLAY_NAME || it.step == OnboardingStep.RESTORE
            if (!leaves || it.isLoading) it else it.copy(step = OnboardingStep.WELCOME, error = null)
        }
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
                discardFailedEnrollment()
                fail(e) { loaded ->
                    if (e is GimException.InvalidInput) loaded.copy(displayNameError = FieldError.InvalidDisplayName) else null
                }
            }
        }
    }

    /**
     * Connects to the server and restores the account; on success finishes. Unknown user (`NotFound`), malformed
     * (`InvalidInput`) and wrong (`Crypto`) codes are reported on their fields.
     */
    fun restore() {
        val current = _state.value
        if (current.step != OnboardingStep.RESTORE || !current.canRestore) return
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
                connected.restoreFromRecovery(
                    current.restoreUserId,
                    formatRecoveryCode(current.restoreCode),
                    current.revokeOtherDevices,
                )
                _state.update { it.copy(isLoading = false, restoreCode = "") }
                _events.send(OnboardingEvent.Finished)
            } catch (e: GimException) {
                discardFailedEnrollment()
                fail(e) { loaded ->
                    when (e) {
                        is GimException.NotFound -> loaded.copy(restoreUserIdError = FieldError.UnknownUser)
                        is GimException.InvalidInput -> loaded.copy(restoreCodeError = FieldError.InvalidRecoveryCode)
                        is GimException.Crypto -> loaded.copy(restoreCodeError = FieldError.WrongRecoveryCode)
                        else -> null
                    }
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

    /** Welcome -> link QR code: connects to the server and starts a link through it. */
    fun linkDevice() {
        val current = _state.value
        if (current.step != OnboardingStep.WELCOME || !current.canCreateAccount) return
        _state.value = current.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val connected = try {
                manager.connect(current.serverUrl.trim())
            } catch (e: GimException) {
                fail(e) { loaded ->
                    if (e is GimException.InvalidInput) loaded.copy(serverUrlError = FieldError.InvalidServerUrl) else null
                }
                return@launch
            }
            client = connected
            startLink(connected, current.serverUrl.trim())
        }
    }

    /** After the wait failed or timed out: shows a new QR code (a link session accepts one bundle). */
    fun retryLink() {
        val current = _state.value
        val connected = client
        if (!current.canRetryLink || connected == null) return
        startLink(connected, current.serverUrl.trim())
    }

    /** Link QR code -> Welcome: stops waiting and drops the client. */
    fun cancelLink() {
        val current = _state.value
        if (current.step != OnboardingStep.LINK_QR || current.isLoading) return
        linkJob?.cancel()
        linkJob = null
        resetToWelcome()
    }

    /** The SAS matches: completes the link and finishes. */
    fun confirmLink() {
        val current = _state.value
        val connected = client
        if (current.step != OnboardingStep.LINK_SAS || current.isLoading || connected == null) return
        _state.value = current.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                connected.confirmLink(true)
                _state.update { it.copy(isLoading = false) }
                _events.send(OnboardingEvent.Finished)
            } catch (e: GimException) {
                fail(e) { null }
            }
        }
    }

    /** The SAS does not match: gim-core wipes this device; returns to Welcome with a new client. */
    fun rejectLink() {
        val current = _state.value
        val connected = client
        if (current.step != OnboardingStep.LINK_SAS || current.isLoading || connected == null) return
        _state.value = current.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                connected.confirmLink(false)
            } catch (_: GimException) {
                // The client is discarded below either way.
            }
            resetToWelcome()
        }
    }

    private fun startLink(connected: GimClient, hub: String) {
        val link = try {
            connected.startLink(listOf(hub))
        } catch (e: GimException) {
            fail(e) { null }
            return
        }
        _state.update {
            it.copy(step = OnboardingStep.LINK_QR, linkQr = link.qrString(), isLoading = false, error = null)
        }
        linkJob = viewModelScope.launch {
            val result = try {
                Result.success(withTimeout(LINK_TIMEOUT) { connected.waitLink(link) })
            } catch (e: TimeoutCancellationException) {
                Result.failure(e)
            } catch (e: GimException) {
                Result.failure(e)
            }
            _state.update { state ->
                if (state.step != OnboardingStep.LINK_QR) return@update state
                result.fold(
                    onSuccess = { sas -> state.copy(step = OnboardingStep.LINK_SAS, linkQr = null, linkSas = sas) },
                    onFailure = { e -> state.copy(linkQr = null, error = linkError(e)) },
                )
            }
        }
    }

    /** Drops the client and shows Welcome again, keeping the server address. */
    private fun resetToWelcome() {
        _state.update { it.copy(isLoading = true, error = null) }
        client = null
        viewModelScope.launch {
            discardClient()
            _state.update { OnboardingUiState.withServerUrl(it.serverUrl) }
        }
    }

    /**
     * After a failed `createIdentity` or `restoreFromRecovery`: gim-core may already have stored this device's keys
     * and MLS identity (they are written before the first hub call), and a retry on the same local state fails
     * ("identity already exists"). Drops the client and wipes that state so the next attempt, on any server
     * address, starts from a fresh client. Nothing usable is lost: no recovery code was shown and nothing finished.
     */
    private suspend fun discardFailedEnrollment() {
        client = null
        discardClient()
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

    companion object {
        private const val HTTP_CONFLICT = 409

        /** gim-core's own `waitLink` limit. */
        val LINK_TIMEOUT = 10.minutes

        private fun linkError(e: Throwable): OnboardingError = when (e) {
            is TimeoutCancellationException -> OnboardingError.LinkTimedOut
            is GimException.Network -> OnboardingError.CannotReachServer
            is GimException.RetryLater -> OnboardingError.TryAgainLater(e.retryAfterSecs.coerceAtLeast(1))
            else -> OnboardingError.LinkFailed
        }
    }
}

/** Signs out and drops the client; the client may hold no session, so failures are ignored. */
private suspend fun GimClientManager.signOutQuietly() {
    try {
        signOut()
    } catch (_: GimException) {
        // The manager drops the client even when sign-out throws.
    }
}

/** [discardClient] replaces the default sign-out when a dropped link must also wipe platform state. */
fun onboardingViewModelFactory(
    manager: GimClientManager,
    defaultServerUrl: String = "",
    discardClient: (suspend () -> Unit)? = null,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            if (discardClient == null) {
                OnboardingViewModel(manager, defaultServerUrl)
            } else {
                OnboardingViewModel(manager, defaultServerUrl, discardClient)
            }
        }
    }
