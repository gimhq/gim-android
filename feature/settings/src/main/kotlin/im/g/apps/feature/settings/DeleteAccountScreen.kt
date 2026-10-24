package im.g.apps.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.theme.GimTheme

/** Test tags; the device E2E tests repeat these literals. */
object DeleteAccountTestTags {
    const val UNDERSTOOD = "delete_account_understood"
    const val DELETE = "delete_account_confirm"
    const val PROGRESS = "delete_account_progress"
}

/**
 * [onDeleted] runs once the account is deleted and the phone wiped: at once, or after the user read which servers
 * could not be reached. Back is swallowed while deleting.
 */
@Composable
fun DeleteAccountRoute(
    viewModel: DeleteAccountViewModel,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnDeleted by rememberUpdatedState(onDeleted)
    BackHandler(enabled = state.deleting || state.deleted) {}
    LaunchedEffect(state.deleted, state.unreachable) {
        if (state.deleted && state.unreachable == null) currentOnDeleted()
    }
    DeleteAccountScreen(
        state = state,
        actions = DeleteAccountActions(
            onBack = onBack,
            onUnderstoodChange = viewModel::setUnderstood,
            onDelete = viewModel::delete,
            onDeleted = onDeleted,
        ),
        modifier = modifier,
    )
}

class DeleteAccountActions(
    val onBack: () -> Unit = {},
    val onUnderstoodChange: (Boolean) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDeleted: () -> Unit = {},
)

/** What deleting means, an "I understand" box and a red button; then progress that cannot be cancelled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteAccountScreen(state: DeleteAccountUiState, actions: DeleteAccountActions, modifier: Modifier = Modifier) {
    val busy = state.deleting || state.deleted
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.delete_account_title)) },
                navigationIcon = {
                    if (!busy) {
                        IconButton(onClick = actions.onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (busy) {
            Deleting(Modifier.padding(padding))
        } else {
            ConfirmForm(state, actions, Modifier.padding(padding))
        }
    }
    val unreachable = state.unreachable
    if (state.deleted && unreachable != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.delete_account_done_title)) },
            text = {
                Text(unreachable.describe(R.string.delete_account_unreachable_named, R.plurals.delete_account_unreachable_count))
            },
            confirmButton = {
                TextButton(onClick = actions.onDeleted) { Text(stringResource(R.string.delete_account_ok)) }
            },
        )
    }
}

@Composable
private fun ConfirmForm(state: DeleteAccountUiState, actions: DeleteAccountActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Heading(R.string.delete_account_heading)
        listOf(
            R.string.delete_account_undo,
            R.string.delete_account_recovery_key,
            R.string.delete_account_chats,
            R.string.delete_account_messages,
        ).forEach { Text(text = stringResource(it), style = MaterialTheme.typography.bodyLarge) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = state.understood, role = Role.Checkbox, onValueChange = actions.onUnderstoodChange)
                .testTag(DeleteAccountTestTags.UNDERSTOOD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.understood, onCheckedChange = null)
            Text(
                text = stringResource(R.string.delete_account_understood),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        state.error?.let { ErrorText(it) }
        Button(
            onClick = actions.onDelete,
            enabled = state.canDelete,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DeleteAccountTestTags.DELETE),
        ) { Text(stringResource(R.string.delete_account_confirm)) }
    }
}

@Composable
private fun Deleting(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(DeleteAccountTestTags.PROGRESS),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.delete_account_deleting),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@GimPreviews
@Composable
private fun DeleteAccountScreenPreview() {
    GimTheme { DeleteAccountScreen(state = DeleteAccountUiState(understood = true), actions = DeleteAccountActions()) }
}
