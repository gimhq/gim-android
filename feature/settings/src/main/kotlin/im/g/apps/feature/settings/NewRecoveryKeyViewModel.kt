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

/** Servers named by URL when every one has a known URL ([urls] then holds [count] entries), else only counted. */
@Immutable
data class ServerList(val urls: List<String>, val count: Int)

/** Hub id -> URL (null when unknown) for the user's hubs; empty when the lookup fails. */
internal suspend fun GimClient.hubUrls(): Map<String, String?> = try {
    hubs().associate { it.hubId to it.url }
} catch (_: GimException) {
    emptyMap()
}

/** [hubIds] as a [ServerList], named from [urls]. */
internal fun serverList(hubIds: List<String>, urls: Map<String, String?>): ServerList {
    val named = hubIds.mapNotNull { urls[it] }
    return ServerList(if (named.size == hubIds.size) named else emptyList(), hubIds.size)
}

enum class NewRecoveryKeyStep { WARNING, KIT, CHECK, DONE }

@Immutable
data class NewRecoveryKeyUiState(
    val step: NewRecoveryKeyStep = NewRecoveryKeyStep.WARNING,
    val creating: Boolean = false,
    /** Why the last rotation failed (string resource); the old key still works. */
    @StringRes val error: Int? = null,
    val accountId: String = "",
    /** The new key, from creation until the check passes or the user leaves. */
    val recoveryCode: String? = null,
    /** Servers still holding the old key; null when every server got the new one. */
    val unsynced: ServerList? = null,
    val check: String = "",
    val checkMismatch: Boolean = false,
    /** Leaving the kit before the check: the dialog asks first. */
    val confirmLeave: Boolean = false,
    /** The flow ended; the screen closes. */
    val left: Boolean = false,
) {
    val canConfirmCheck: Boolean get() = check.count { it.isLetterOrDigit() } == KIT_CHECK_SYMBOLS
}

private const val KIT_CHECK_SYMBOLS = 4

/**
 * "Create a new recovery key": warning -> [GimClient.rotateRecoveryCode] -> the new kit (shown once) -> the key's last
 * four characters typed back -> done. Like onboarding's kit, but the old key already stopped working on the kit, so
 * leaving before the check asks first.
 */
class NewRecoveryKeyViewModel(
    private val client: GimClient,
    private val recoveryKitCodec: RecoveryKitCodec,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(NewRecoveryKeyUiState())
    val state: StateFlow<NewRecoveryKeyUiState> = _state.asStateFlow()

    fun create() {
        if (_state.value.creating || _state.value.step != NewRecoveryKeyStep.WARNING) return
        _state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                val (accountId, rotation, unsynced) = withContext(ioDispatcher) {
                    val rotation = client.rotateRecoveryCode()
                    val unsynced = rotation.unsyncedHubIds.takeIf { it.isNotEmpty() }?.let { serverList(it, client.hubUrls()) }
                    Triple(client.userId().orEmpty(), rotation, unsynced)
                }
                _state.update {
                    it.copy(
                        step = NewRecoveryKeyStep.KIT,
                        creating = false,
                        accountId = accountId,
                        recoveryCode = rotation.recoveryCode,
                        unsynced = unsynced,
                    )
                }
            } catch (e: GimException) {
                _state.update { it.copy(creating = false, error = errorFor(e)) }
            }
        }
    }

    /** The kit text the kit screen copies and shares. */
    fun kitText(labels: RecoveryKitLabels): String {
        val state = _state.value
        return recoveryKitCodec.format(state.accountId, state.recoveryCode.orEmpty(), labels)
    }

    fun continueFromKit() = _state.update {
        if (it.step == NewRecoveryKeyStep.KIT) it.copy(step = NewRecoveryKeyStep.CHECK) else it
    }

    fun onCheckChange(value: String) = _state.update { it.copy(check = value, checkMismatch = false) }

    /** Done when the typed characters end the key; the key is then dropped from the state for good. */
    fun confirmCheck() = _state.update {
        val code = it.recoveryCode
        when {
            it.step != NewRecoveryKeyStep.CHECK || !it.canConfirmCheck || code == null -> it
            recoveryKitCodec.matchesKeyEnding(code, it.check) ->
                it.copy(step = NewRecoveryKeyStep.DONE, recoveryCode = null, check = "", checkMismatch = false)
            else -> it.copy(checkMismatch = true)
        }
    }

    /** Warning and done leave, the kit asks first, the check returns to the kit; ignored while creating. */
    fun back() = _state.update {
        when {
            it.creating -> it
            it.step == NewRecoveryKeyStep.KIT -> it.copy(confirmLeave = true)
            it.step == NewRecoveryKeyStep.CHECK -> it.copy(step = NewRecoveryKeyStep.KIT, check = "", checkMismatch = false)
            else -> it.copy(left = true)
        }
    }

    fun stay() = _state.update { it.copy(confirmLeave = false) }

    fun leave() = _state.update { it.copy(confirmLeave = false, recoveryCode = null, left = true) }

    fun finish() = _state.update { it.copy(left = true) }

    @StringRes
    private fun errorFor(e: GimException): Int = when (e) {
        is GimException.Network -> R.string.new_key_error_network
        is GimException.RetryLater -> R.string.new_key_error_busy
        else -> R.string.new_key_error_generic
    }
}

fun newRecoveryKeyViewModelFactory(client: GimClient, recoveryKitCodec: RecoveryKitCodec): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { NewRecoveryKeyViewModel(client, recoveryKitCodec) }
    }
