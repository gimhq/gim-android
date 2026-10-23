package im.g.apps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import im.g.apps.core.designsystem.theme.GimTheme
import im.g.apps.feature.settings.ThemeMode
import im.g.apps.feature.settings.ThemeModeStore
import im.g.apps.navigation.GimNavHost
import im.g.apps.navigation.Routes
import im.g.apps.navigation.resolveStartDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** An [AppCompatActivity] so the in-app language (AppCompatDelegate.setApplicationLocales) works below API 33. */
class MainActivity : AppCompatActivity() {
    /** The chat a tapped push notification asks to open, until [GimApp] opened it. */
    private val openChat = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openChat.value = intent.getStringExtra(EXTRA_CHAT_ID)
        val container = (application as GimApplication).container
        setContent {
            GimAppTheme(container.themeModeStore, onDarkThemeChanged = ::applySystemBarStyle) {
                GimApp(container, openChat) { openChat.value = null }
            }
        }
    }

    /** Keeps the status and navigation bar icons readable when the app theme differs from the phone's. */
    private fun applySystemBarStyle(darkTheme: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CHAT_ID)?.let { openChat.value = it }
    }

    companion object {
        /** Intent extra: open this chat (set by push notifications). */
        const val EXTRA_CHAT_ID = "im.g.apps.extra.CHAT_ID"

        // The navigation bar scrims enableEdgeToEdge() uses by default (3-button navigation only).
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

/** [GimTheme] in the [store]'s mode; reports the effective light/dark choice to [onDarkThemeChanged]. */
@Composable
fun GimAppTheme(
    store: ThemeModeStore,
    onDarkThemeChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val mode by store.mode.collectAsState()
    val darkTheme = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    DisposableEffect(darkTheme) {
        onDarkThemeChanged(darkTheme)
        onDispose {}
    }
    GimTheme(darkTheme = darkTheme, content = content)
}

/**
 * Shows an empty surface while the stored client is restored, then the navigation graph. Opens [openChat] once
 * signed in, and asks for the notification permission (Android 13+) once push is registered.
 */
@Composable
fun GimApp(
    container: AppContainer,
    openChat: StateFlow<String?> = MutableStateFlow(null),
    onChatOpened: () -> Unit = {},
) {
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(container) {
        // Compose test frame clocks resume inline; gim-core calls need a real dispatcher.
        val route = withContext(Dispatchers.IO) { resolveStartDestination(container.manager) }
        if (route == Routes.CHAT_LIST) container.sync.onSignedIn()
        start = route
    }
    NotificationPermissionRequest(container)
    val navController = rememberNavController()
    Surface(Modifier.fillMaxSize()) {
        start?.let { route ->
            GimNavHost(container, route, navController = navController)
            val chatId by openChat.collectAsState()
            LaunchedEffect(chatId) {
                val id = chatId ?: return@LaunchedEffect
                if (container.sync.signedIn.value) navController.navigate(Routes.chat(id)) { launchSingleTop = true }
                onChatOpened()
            }
        }
    }
}

@Composable
private fun NotificationPermissionRequest(container: AppContainer) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val pushActive by container.push.active.collectAsState()
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(pushActive) {
        if (!pushActive || asked) return@LaunchedEffect
        asked = true
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(permission)
        }
    }
}
