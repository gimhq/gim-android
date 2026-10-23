package im.g.apps.feature.chat.composer

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import im.g.apps.core.designsystem.component.GimConfirmDialog
import im.g.apps.feature.chat.R
import kotlinx.collections.immutable.ImmutableList

/** Callbacks of the message actions sheet; each receives the message id. */
class MessageActionCallbacks(
    val onReply: (String) -> Unit,
    val onCopy: (String) -> Unit,
    val onEdit: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onReact: (messageId: String, emoji: String) -> Unit,
    val onRetry: (String) -> Unit,
    val onCancelSend: (String) -> Unit,
)

/** Bottom sheet listing the actions available for one message: quick reactions first, then Reply. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    actions: MessageActionsUi,
    quickReactions: ImmutableList<String>,
    callbacks: MessageActionCallbacks,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        MessageActionsContent(actions, quickReactions, callbacks)
    }
}

@Composable
internal fun MessageActionsContent(
    actions: MessageActionsUi,
    quickReactions: ImmutableList<String>,
    callbacks: MessageActionCallbacks,
) {
    val id = actions.messageId
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        if (actions.canReact) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                quickReactions.forEach { emoji ->
                    ReactionButton(emoji, emoji in actions.ownReactions) { callbacks.onReact(id, emoji) }
                }
            }
        }
        if (actions.canReply) ActionRow(R.string.composer_action_reply) { callbacks.onReply(id) }
        actions.copyText?.let { text -> ActionRow(R.string.composer_action_copy) { callbacks.onCopy(text) } }
        if (actions.canEdit) ActionRow(R.string.composer_action_edit) { callbacks.onEdit(id) }
        if (actions.canRetry) ActionRow(R.string.composer_action_retry) { callbacks.onRetry(id) }
        if (actions.canDiscard) {
            ActionRow(R.string.composer_action_delete, MaterialTheme.colorScheme.error) { callbacks.onCancelSend(id) }
        }
        if (actions.canDelete) {
            ActionRow(R.string.composer_action_delete, MaterialTheme.colorScheme.error) { callbacks.onDelete(id) }
        }
    }
}

/** Confirmation before deleting a message for everyone. */
@Composable
fun DeleteMessageDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    GimConfirmDialog(
        title = stringResource(R.string.composer_delete_title),
        body = stringResource(R.string.composer_delete_body),
        confirmLabel = stringResource(R.string.composer_action_delete),
        dismissLabel = stringResource(R.string.composer_delete_keep),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
    )
}

@Composable
private fun ActionRow(@StringRes label: Int, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Text(
        text = stringResource(label),
        color = color,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

@Composable
private fun ReactionButton(emoji: String, own: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.composer_react, emoji)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .background(if (own) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = own
            },
    ) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
    }
}
