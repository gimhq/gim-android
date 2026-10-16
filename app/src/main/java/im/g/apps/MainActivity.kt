package im.g.apps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import im.g.apps.core.designsystem.theme.GimTheme
import im.g.apps.navigation.GimNavHost
import im.g.apps.navigation.Routes
import im.g.apps.navigation.resolveStartDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GimApplication).container
        setContent {
            GimTheme {
                GimApp(container)
            }
        }
    }
}

/** Shows an empty surface while the stored client is restored, then the navigation graph. */
@Composable
fun GimApp(container: AppContainer) {
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(container) {
        // Compose test frame clocks resume inline; gim-core calls need a real dispatcher.
        val route = withContext(Dispatchers.IO) { resolveStartDestination(container.manager) }
        if (route == Routes.CHAT_LIST) container.sync.onSignedIn()
        start = route
    }
    Surface(Modifier.fillMaxSize()) {
        start?.let { GimNavHost(container, it) }
    }
}
