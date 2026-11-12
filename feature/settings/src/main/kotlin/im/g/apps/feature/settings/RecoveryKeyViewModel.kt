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
import im.g.apps.core.bridge.RecoveryKitCodec
import im.g.apps.core.bridge.RecoveryKitLabels
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RecoveryKeyStatus {
    /** Reading whether this device holds the key. */
    LOADING,

    /** The device holds the key; it is shown after device authentication. */
    LOCKED,

    /** The key is shown: the window blocks screenshots, and the key is dropped when the screen is left. */
    SHOWN,

    /** This device does not hold the key (linked by QR code, or the account predates kept keys). */
    MISSING,

    /** The key could not be read. */
    ERROR,
}

/** How device authentication ended. */
enum class DeviceAuthResult { SUCCESS, CANCELLED, FAILED, NO_LOCK }

@Immutable
data class RecoveryKeyUiState(
    val status: RecoveryKeyStatus = RecoveryKeyStatus.LOADING,
    val accountId: String = "",
    /** Only while [status] is [RecoveryKeyStatus.SHOWN]. */
    val recoveryCode: String? = null,
    /** Why the last authentication did not show the key (string resource); null after a cancel. */
    @StringRes val authError: Int? = null,
    val showQr: Boolean = false,
)

/**
 * "Your recovery key": the key kept on this device ([GimClient.recoveryCode]) behind device authentication, like a
 * wallet seed phrase. The key is read only after [onAuthResult] reports success and dropped by [hide] (the screen
 * calls it when it stops); a device without the key offers "Create a new recovery key".
 */
class RecoveryKeyViewModel(
    private val client: GimClient,
    private val recoveryKitCodec: RecoveryKitCodec,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(RecoveryKeyUiState())
    val state: StateFlow<RecoveryKeyUiState> = _state.asStateFlow()

    /** Whether the device holds a key, without keeping it; also after a new key was created. */
    fun refresh() {
        viewModelScope.launch {
            val status = try {
                if (withContext(ioDispatcher) { client.recoveryCode() } != null) RecoveryKeyStatus.LOCKED else RecoveryKeyStatus.MISSING
            } catch (_: GimException) {
                RecoveryKeyStatus.ERROR
            }
            _state.update {
                if (it.status == RecoveryKeyStatus.SHOWN) it else it.copy(status = status, accountId = client.userId().orEmpty())
            }
        }
    }

    /** Clears the last error before a new authentication starts. */
    fun startAuth() = _state.update { it.copy(authError = null) }

    fun onAuthResult(result: DeviceAuthResult) {
        if (_state.value.status != RecoveryKeyStatus.LOCKED) return
        when (result) {
            DeviceAuthResult.SUCCESS -> reveal()
            DeviceAuthResult.CANCELLED -> _state.update { it.copy(authError = null) }
            DeviceAuthResult.FAILED -> _state.update { it.copy(authError = R.string.recovery_key_auth_failed) }
            DeviceAuthResult.NO_LOCK -> _state.update { it.copy(authError = R.string.recovery_key_no_lock) }
        }
    }

    /** Drops the key from the state: leaving the screen or the app hides it. */
    fun hide() = _state.update {
        if (it.status == RecoveryKeyStatus.SHOWN) {
            it.copy(status = RecoveryKeyStatus.LOCKED, recoveryCode = null, showQr = false)
        } else {
            it
        }
    }

    fun toggleQr() = _state.update { if (it.status == RecoveryKeyStatus.SHOWN) it.copy(showQr = !it.showQr) else it }

    /** The kit text the screen copies, saves and shows as a QR code. */
    fun kitText(labels: RecoveryKitLabels): String {
        val state = _state.value
        return recoveryKitCodec.format(state.accountId, state.recoveryCode.orEmpty(), labels)
    }

    private fun reveal() {
        viewModelScope.launch {
            val code = try {
                withContext(ioDispatcher) { client.recoveryCode() }
            } catch (_: GimException) {
                _state.update { it.copy(status = RecoveryKeyStatus.ERROR) }
                return@launch
            }
            _state.update {
                if (code == null) {
                    it.copy(status = RecoveryKeyStatus.MISSING)
                } else {
                    it.copy(status = RecoveryKeyStatus.SHOWN, recoveryCode = code, authError = null, accountId = client.userId().orEmpty())
                }
            }
        }
    }
}

fun recoveryKeyViewModelFactory(client: GimClient, recoveryKitCodec: RecoveryKitCodec): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { RecoveryKeyViewModel(client, recoveryKitCodec) }
    }
