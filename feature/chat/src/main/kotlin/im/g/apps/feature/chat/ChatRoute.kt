package im.g.apps.feature.chat

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.feature.chat.composer.resolve
import im.g.apps.feature.chat.timeline.ChatEvent
import kotlinx.coroutines.launch

/**
 * Chat screen entry point. [composer] fills the composer slot; [onOpenFile] receives a downloaded
 * attachment's local path and MIME type; [onMessageLongPress] receives a message id for the action menu.
 * Pass the composer ViewModel's `reply` as [onReply] and `toggleReaction` as [onToggleReaction] so TalkBack
 * can reply and reaction chips toggle; without them TalkBack offers no Reply and chips do nothing.
 * Errors show in [snackbarHostState]; share it with [im.g.apps.feature.chat.composer.ComposerRoute].
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
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onReply: ((messageId: String) -> Unit)? = null,
    onToggleReaction: ((messageId: String, emoji: String) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnOpenFile by rememberUpdatedState(onOpenFile)
    val context = LocalContext.current
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatEvent.OpenFile -> currentOnOpenFile(event.path, event.mime)
                is ChatEvent.Error -> launch { snackbarHostState.showSnackbar(event.message.resolve(context)) }
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
        onRetryMedia = viewModel::retryMedia,
        snackbarHostState = snackbarHostState,
        onReply = onReply,
        onToggleReaction = onToggleReaction,
    )
}
