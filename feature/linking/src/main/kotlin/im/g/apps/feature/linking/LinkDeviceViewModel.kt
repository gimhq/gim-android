package im.g.apps.feature.linking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.PendingApproval
import im.g.apps.core.bridge.suspendedHubName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Existing device: scan (or paste) the new device's QR code -> `approveLink` -> compare the SAS ->
 * `confirmApproval(accepted)` -> done. Errors keep the user on the current step so they can try again.
 */
class LinkDeviceViewModel(private val client: GimClient) : ViewModel() {
    private val _state = MutableStateFlow(LinkDeviceUiState())
    val state: StateFlow<LinkDeviceUiState> = _state.asStateFlow()

    private var approval: PendingApproval? = null

    /**
     * Last code `approveLink` rejected as invalid or for a suspended or not admitted account (not retried); the scanner
     * keeps reporting it while the camera points at it.
     */
    private var rejectedCode: String? = null

    /** A QR code decoded by the camera; ignored unless scanning. */
    fun onCodeScanned(code: String) {
        if (code == rejectedCode) return
        approve(code)
    }

    fun onPastedCodeChange(value: String) = _state.update {
        if (it.step != LinkDeviceStep.SCAN) it else it.copy(pastedCode = value, error = null)
    }

    fun submitPastedCode() {
        val current = _state.value
        if (!current.canSubmitPastedCode) return
        approve(current.pastedCode.trim())
    }

    /** Confirms ([accepted]) or rejects the compared SAS; rejecting revokes the new device. */
    fun confirm(accepted: Boolean) {
        val current = _state.value
        val pending = approval
        if (current.step != LinkDeviceStep.CONFIRM || pending == null) return
        _state.value = current.copy(step = LinkDeviceStep.CONFIRMING, error = null)
        viewModelScope.launch {
            try {
                client.confirmApproval(pending, accepted)
                approval = null
                _state.update { it.copy(step = LinkDeviceStep.DONE, linked = accepted) }
            } catch (e: GimException) {
                val error = e.toError(client.suspendedHubName(e))
                _state.update { it.copy(step = LinkDeviceStep.CONFIRM, error = error) }
            }
        }
    }

    private fun approve(code: String) {
        val current = _state.value
        if (current.step != LinkDeviceStep.SCAN) return
        _state.value = current.copy(step = LinkDeviceStep.APPROVING, error = null)
        viewModelScope.launch {
            try {
                val pending = client.approveLink(code)
                approval = pending
                _state.update {
                    it.copy(step = LinkDeviceStep.CONFIRM, sas = pending.sas(), deviceId = pending.deviceId())
                }
            } catch (e: GimException) {
                if (e is GimException.InvalidInput || e is GimException.UserSuspended || e is GimException.UserNotAdmitted) {
                    rejectedCode = code
                }
                val error = e.toError(client.suspendedHubName(e))
                _state.update { it.copy(step = LinkDeviceStep.SCAN, error = error) }
            }
        }
    }

    private fun GimException.toError(suspendedHub: String?): LinkDeviceError = when (this) {
        is GimException.InvalidInput -> LinkDeviceError.InvalidCode
        is GimException.Network -> LinkDeviceError.CannotReachServer
        is GimException.RetryLater -> LinkDeviceError.TryAgainLater(retryAfterSecs.coerceAtLeast(1))
        is GimException.UserSuspended -> LinkDeviceError.Suspended(suspendedHub)
        is GimException.UserNotAdmitted -> LinkDeviceError.NotAdmitted(suspendedHub)
        else -> LinkDeviceError.Generic
    }
}

fun linkDeviceViewModelFactory(client: GimClient): ViewModelProvider.Factory = viewModelFactory {
    initializer { LinkDeviceViewModel(client) }
}
