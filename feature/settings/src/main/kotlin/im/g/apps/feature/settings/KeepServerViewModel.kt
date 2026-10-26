package im.g.apps.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [current]: the server the saved [choice] points to (null: off); [input]: the address field; [restartNeeded]: the
 * running client still uses another server, which changes when the app starts again.
 */
data class KeepServerUiState(
    val choice: KeepServerChoice,
    val current: String?,
    val buildDefault: String?,
    val input: String,
    val error: KeepServerUrlError? = null,
    val restartNeeded: Boolean = false,
)

/**
 * The keep server screen: change the address, reset it to [buildDefault] or turn it off, saved in [store]. [applied] is
 * the address the running client was created with.
 */
class KeepServerViewModel(
    private val store: KeepServerStore,
    private val buildDefault: String?,
    private val applied: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(stateFor(store.choice.value))
    val state: StateFlow<KeepServerUiState> = _state.asStateFlow()

    fun onInputChange(value: String) = _state.update { it.copy(input = value, error = null) }

    fun save() {
        val url = normalizeKeepServerUrl(_state.value.input)
        validateKeepServerUrl(url)?.let { error ->
            _state.update { it.copy(error = error) }
            return
        }
        choose(if (url == buildDefault) KeepServerChoice.Default else KeepServerChoice.Custom(url))
    }

    fun reset() = choose(KeepServerChoice.Default)

    fun turnOff() = choose(KeepServerChoice.Off)

    private fun choose(choice: KeepServerChoice) {
        store.set(choice)
        _state.value = stateFor(choice)
    }

    private fun stateFor(choice: KeepServerChoice): KeepServerUiState {
        val current = choice.resolve(buildDefault)
        return KeepServerUiState(
            choice = choice,
            current = current,
            buildDefault = buildDefault,
            input = current.orEmpty(),
            restartNeeded = current != applied,
        )
    }
}

fun keepServerViewModelFactory(store: KeepServerStore, buildDefault: String?, applied: String?): ViewModelProvider.Factory =
    viewModelFactory { initializer { KeepServerViewModel(store, buildDefault, applied) } }
