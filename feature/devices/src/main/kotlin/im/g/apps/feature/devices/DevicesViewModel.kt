package im.g.apps.feature.devices

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import im.g.apps.core.bridge.DeviceInfo
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** User-facing errors of the devices screen; mapped from [GimException]. */
sealed interface DevicesError {
    data object Network : DevicesError

    data class RetryLater(val seconds: Long) : DevicesError

    /** [GimClient.revokeDevice] rejected the current device. */
    data object CurrentDevice : DevicesError

    data object NotFound : DevicesError

    data object Server : DevicesError

    data object Unknown : DevicesError
}

@Immutable
data class DevicesUiState(
    /** The signed-in user's id, shown for restore; null without a session. */
    val userId: String? = null,
    /** This device first, then active devices, then revoked ones. */
    val devices: PersistentList<DeviceInfo> = persistentListOf(),
    val loading: Boolean = true,
    /** The device awaiting revoke confirmation. */
    val pendingRevoke: DeviceInfo? = null,
    val revoking: Boolean = false,
    /** The latest error, shown once and cleared by [DevicesViewModel.errorShown]. */
    val error: DevicesError? = null,
    /** This device was revoked elsewhere ([GimException.NotRegistered]): only sign-out is left. */
    val removed: Boolean = false,
) {
    fun canRevoke(device: DeviceInfo): Boolean = !device.isCurrent && !device.revoked
}

/** Lists the user's devices ([GimClient.devices]) and revokes other ones ([GimClient.revokeDevice]). */
class DevicesViewModel(private val client: GimClient) : ViewModel() {
    private val _state = MutableStateFlow(DevicesUiState(userId = client.userId()))
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch { load() }
    }

    fun requestRevoke(device: DeviceInfo) {
        if (!_state.value.canRevoke(device)) return
        _state.update { it.copy(pendingRevoke = device) }
    }

    fun dismissRevoke() {
        _state.update { it.copy(pendingRevoke = null) }
    }

    fun confirmRevoke() {
        val device = _state.value.pendingRevoke ?: return
        _state.update { it.copy(pendingRevoke = null, revoking = true) }
        viewModelScope.launch {
            try {
                client.revokeDevice(device.deviceId)
                load()
            } catch (e: GimException) {
                fail(e)
            } finally {
                _state.update { it.copy(revoking = false) }
            }
        }
    }

    fun errorShown() {
        _state.update { it.copy(error = null) }
    }

    private suspend fun load() {
        try {
            val devices = client.devices()
                .sortedWith(compareBy({ !it.isCurrent }, { it.revoked }))
                .toPersistentList()
            _state.update { it.copy(devices = devices, loading = false) }
        } catch (e: GimException) {
            fail(e)
        }
    }

    private fun fail(e: GimException) {
        _state.update {
            if (e is GimException.NotRegistered) {
                it.copy(loading = false, pendingRevoke = null, removed = true)
            } else {
                it.copy(loading = false, error = e.toDevicesError())
            }
        }
    }
}

internal fun GimException.toDevicesError(): DevicesError = when (this) {
    is GimException.Network -> DevicesError.Network
    is GimException.RetryLater -> DevicesError.RetryLater(retryAfterSecs)
    is GimException.InvalidInput -> DevicesError.CurrentDevice
    is GimException.NotFound -> DevicesError.NotFound
    is GimException.Server -> DevicesError.Server
    else -> DevicesError.Unknown
}

fun devicesViewModelFactory(client: GimClient): ViewModelProvider.Factory = viewModelFactory {
    initializer { DevicesViewModel(client) }
}
