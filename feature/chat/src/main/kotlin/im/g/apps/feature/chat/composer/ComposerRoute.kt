package im.g.apps.feature.chat.composer

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.feature.chat.timeline.UiText
import kotlinx.coroutines.launch

/**
 * Composer bound to [viewModel], including the message actions sheet, delete confirmation and the
 * photo / file pickers. Fill [im.g.apps.feature.chat.ChatRoute]'s `composer` slot with it, passing the slot's
 * `inputVisible` as [inputVisible] (false hides the input bar but keeps the sheets), pass
 * [ComposerViewModel.onMessageLongPress] as `onMessageLongPress`, and share [snackbarHostState] with
 * the chat screen so errors show there. The sheet's Report calls [onReport] with the sender and message ids.
 */
@Composable
fun ComposerRoute(
    viewModel: ComposerViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    inputVisible: Boolean = true,
    onReport: (userId: String, messageId: String) -> Unit = { _, _ -> },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.events.collect { event ->
            when (event) {
                is ComposerEvent.Error -> launch { snackbarHostState.showSnackbar(event.message.resolve(context)) }
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.onAttachmentPicked(it.toString()) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onAttachmentPicked(it.toString()) }
    }

    if (inputVisible) {
        Composer(
            state = state,
            onDraftChange = viewModel::onDraftChange,
            onSend = viewModel::send,
            onCancelMode = viewModel::cancelMode,
            onAttachPhoto = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onAttachFile = { filePicker.launch(arrayOf("*/*")) },
            modifier = modifier,
        )
    }

    val currentOnReport by rememberUpdatedState(onReport)
    val callbacks = remember(viewModel, clipboard) {
        MessageActionCallbacks(
            onReply = viewModel::reply,
            onCopy = { text ->
                viewModel.dismissActions()
                clipboard.setText(AnnotatedString(text))
            },
            onEdit = viewModel::edit,
            onDelete = viewModel::requestDelete,
            onReact = viewModel::toggleReaction,
            onRetry = viewModel::retry,
            onCancelSend = viewModel::cancelSend,
            onReport = { userId, messageId ->
                viewModel.dismissActions()
                currentOnReport(userId, messageId)
            },
        )
    }
    state.actions?.let { actions ->
        MessageActionSheet(
            actions = actions,
            quickReactions = state.quickReactions,
            callbacks = callbacks,
            onDismiss = viewModel::dismissActions,
        )
    }
    if (state.confirmDeleteId != null) {
        DeleteMessageDialog(onConfirm = viewModel::confirmDelete, onDismiss = viewModel::cancelDelete)
    }
}

/** Resolves [UiText] outside composition, e.g. for snackbars. */
internal fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> context.getString(id, *args.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(id, count, count)
}
