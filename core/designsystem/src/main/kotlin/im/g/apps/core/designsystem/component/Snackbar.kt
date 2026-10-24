package im.g.apps.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import im.g.apps.core.designsystem.theme.GimTheme

/** Snackbar host with the app's snackbar look; messages may wrap and are announced politely. */
@Composable
fun GimSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data -> GimSnackbar(data) }
}

@Composable
private fun GimSnackbar(data: SnackbarData) {
    Snackbar(
        snackbarData = data,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        actionColor = MaterialTheme.colorScheme.inversePrimary,
        actionContentColor = MaterialTheme.colorScheme.inversePrimary,
        dismissActionContentColor = MaterialTheme.colorScheme.inverseOnSurface,
    )
}

@GimPreviews
@Composable
private fun GimSnackbarPreview() {
    GimTheme {
        GimSnackbar(
            object : SnackbarData {
                override val visuals = object : SnackbarVisuals {
                    override val message = "Couldn't send the photo. Check your connection and try again."
                    override val actionLabel = "Retry"
                    override val withDismissAction = false
                    override val duration = SnackbarDuration.Short
                }

                override fun performAction() = Unit

                override fun dismiss() = Unit
            },
        )
    }
}
