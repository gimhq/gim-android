package im.g.apps.core.designsystem.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import im.g.apps.core.designsystem.theme.GimTheme

/**
 * Yes/no confirmation. [destructive] paints the confirm button in the error color. While
 * [enabled] is false (e.g. the action is running) both buttons are disabled and dismissal is ignored.
 */
@Composable
fun GimConfirmDialog(
    title: String,
    body: String?,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        modifier = modifier,
        title = { Text(title) },
        text = body?.let { { Text(it) } },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = enabled,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) { Text(dismissLabel) }
        },
    )
}

@GimPreviews
@Composable
private fun GimConfirmDialogPreview() {
    GimTheme {
        GimConfirmDialog(
            title = "Delete message?",
            body = "It will be removed for everyone in this chat.",
            confirmLabel = "Delete",
            dismissLabel = "Keep",
            onConfirm = {},
            onDismiss = {},
            destructive = true,
        )
    }
}
