package im.g.apps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import im.g.apps.navigation.GimNavHost
import im.g.apps.navigation.Routes
import im.g.apps.navigation.resolveStartDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** The chat a tapped push notification asks to open, until [GimApp] opened it. */
    private val openChat = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openChat.value = intent.getStringExtra(EXTRA_CHAT_ID)
        val container = (application as GimApplication).container
        setContent {
            GimTheme {
                GimApp(container, openChat) { openChat.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CHAT_ID)?.let { openChat.value = it }
    }

    companion object {
        /** Intent extra: open this chat (set by push notifications). */
        const val EXTRA_CHAT_ID = "im.g.apps.extra.CHAT_ID"
    }
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
