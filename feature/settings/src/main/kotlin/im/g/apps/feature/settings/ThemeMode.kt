package im.g.apps.feature.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app's light/dark choice: follow the phone, or always light or dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The [AppCompatDelegate] night mode for this choice, so the window background matches it from the first frame. */
fun ThemeMode.toNightMode(): Int = when (this) {
    ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
}

/** Holds the [ThemeMode]; the app theme follows [mode]. */
interface ThemeModeStore {
    val mode: StateFlow<ThemeMode>

    fun set(mode: ThemeMode)
}

/** Persists the mode in the [PREFS] shared preferences, which sign-out does not wipe. */
class SharedPrefsThemeModeStore(context: Context) : ThemeModeStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(read())
    override val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    override fun set(mode: ThemeMode) {
        prefs.edit().putString(KEY, mode.name).apply()
        _mode.value = mode
    }

    private fun read(): ThemeMode {
        val stored = prefs.getString(KEY, null)
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
    }

    companion object {
        const val PREFS = "gim_settings"
        const val KEY = "theme_mode"
    }
}

/** A [ThemeModeStore] that keeps the mode in memory only (tests). */
class InMemoryThemeModeStore(initial: ThemeMode = ThemeMode.SYSTEM) : ThemeModeStore {
    private val _mode = MutableStateFlow(initial)
    override val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    override fun set(mode: ThemeMode) {
        _mode.value = mode
    }
}
