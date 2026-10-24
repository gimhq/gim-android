package im.g.apps

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import im.g.apps.feature.settings.ThemeModeStore
import im.g.apps.feature.settings.toNightMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class GimApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Main.immediate applies the stored mode right here, before the first activity draws its window.
        followThemeMode(container.themeModeStore, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        container.sync.start()
        container.push.start()
        registerTimeZoneChangedReceiver(this, container::onTimeZoneChanged)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = container.sync.setForeground(true)

                override fun onStop(owner: LifecycleOwner) = container.sync.setForeground(false)
            },
        )
    }
}

/**
 * Keeps AppCompat's default night mode on the [store]'s mode, so the window background, the configuration and
 * Compose's isSystemInDarkTheme() agree. A change recreates the running activities.
 */
internal fun followThemeMode(store: ThemeModeStore, scope: CoroutineScope) {
    store.mode.onEach { AppCompatDelegate.setDefaultNightMode(it.toNightMode()) }.launchIn(scope)
}
