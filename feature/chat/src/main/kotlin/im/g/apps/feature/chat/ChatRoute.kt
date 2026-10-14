package im.g.apps.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.feature.chat.timeline.ChatEvent

/**
 * Chat screen entry point. [composer] fills the composer slot; [onOpenFile] receives a downloaded
 * attachment's local path and MIME type; [onMessageLongPress] receives a message id for the action menu.
 */
@Composable
fun ChatRoute(
    viewModel: ChatViewModel,
    composer: @Composable () -> Unit,
    onBack: () -> Unit,
    onOpenGroupInfo: () -> Unit,
    onOpenFile: (path: String, mime: String) -> Unit,
    onMessageLongPress: (messageId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnOpenFile by rememberUpdatedState(onOpenFile)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatEvent.OpenFile -> currentOnOpenFile(event.path, event.mime)
            }
        }
    }
    ChatScreen(
        state = state,
        composer = composer,
        onBack = onBack,
        onOpenGroupInfo = onOpenGroupInfo,
        onLoadOlder = viewModel::loadOlder,
        onVisible = viewModel::onVisible,
        onRetry = viewModel::retry,
        onImageNeeded = viewModel::onImageNeeded,
        onOpenFile = viewModel::onOpenFile,
        onMessageLongPress = onMessageLongPress,
        modifier = modifier,
    )
}
