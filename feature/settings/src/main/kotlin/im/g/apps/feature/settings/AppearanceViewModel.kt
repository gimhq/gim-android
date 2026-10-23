package im.g.apps.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.StateFlow

/** The light/dark choice on [AppearanceScreen], saved in [store]. */
class AppearanceViewModel(private val store: ThemeModeStore) : ViewModel() {
    val mode: StateFlow<ThemeMode> = store.mode

    fun select(mode: ThemeMode) = store.set(mode)
}

fun appearanceViewModelFactory(store: ThemeModeStore): ViewModelProvider.Factory = viewModelFactory {
    initializer { AppearanceViewModel(store) }
}
