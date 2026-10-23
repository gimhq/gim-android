package im.g.apps.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A selectable language: its [tag] and its [name] in that language. */
@Immutable
data class LanguageOption(val tag: String, val name: String)

@Immutable
data class LanguageUiState(
    val options: List<LanguageOption> = emptyList(),
    /** The chosen tag; null follows the phone language. */
    val selected: String? = null,
)

/** The in-app language on [LanguageScreen]: "Use phone language" plus one option per tag in [tags]. */
class LanguageViewModel(private val appLocales: AppLocales, tags: List<String>) : ViewModel() {
    private val _state = MutableStateFlow(
        LanguageUiState(
            options = tags.map { LanguageOption(it, languageDisplayName(it)) },
            selected = appLocales.current(),
        ),
    )
    val state: StateFlow<LanguageUiState> = _state.asStateFlow()

    /** Applies [tag] (null: phone language) right away; the activity recreates to show it. */
    fun select(tag: String?) {
        _state.value = _state.value.copy(selected = tag)
        appLocales.set(tag)
    }
}

fun languageViewModelFactory(appLocales: AppLocales, tags: List<String>): ViewModelProvider.Factory = viewModelFactory {
    initializer { LanguageViewModel(appLocales, tags) }
}
