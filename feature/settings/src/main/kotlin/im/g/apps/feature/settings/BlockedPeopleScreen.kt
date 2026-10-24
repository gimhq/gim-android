package im.g.apps.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.theme.GimTheme

/** Test tags; the device E2E tests repeat these literals. */
object BlockedPeopleTestTags {
    const val EMPTY = "settings_blocked_empty"
    const val UNBLOCK_CONFIRM = "settings_blocked_unblock_confirm"

    fun unblock(userId: String) = "settings_blocked_unblock_$userId"
}

@Composable
fun BlockedPeopleRoute(viewModel: BlockedPeopleViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(context.getString(it)) }
    }
    BlockedPeopleScreen(
        state = state,
        actions = BlockedPeopleActions(
            onBack = onBack,
            onRetry = viewModel::load,
            onAskUnblock = viewModel::askUnblock,
            onUnblock = viewModel::unblock,
            onCancelUnblock = viewModel::cancelUnblock,
        ),
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

class BlockedPeopleActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAskUnblock: (BlockedPerson) -> Unit = {},
    val onUnblock: () -> Unit = {},
    val onCancelUnblock: () -> Unit = {},
)

/** The blocked people, each with "Unblock" behind a confirmation; an empty state when nobody is blocked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedPeopleScreen(
    state: BlockedPeopleUiState,
    actions: BlockedPeopleActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.blocked_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val people = state.people
        when {
            state.loadFailed -> LoadFailed(actions.onRetry, Modifier.padding(padding))
            people == null -> Unit
            people.isEmpty() -> CenteredText(
                text = stringResource(R.string.blocked_empty),
                modifier = Modifier
                    .padding(padding)
                    .testTag(BlockedPeopleTestTags.EMPTY),
            )
            else -> LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(people, key = { it.userId }) { person -> BlockedRow(person, actions.onAskUnblock) }
            }
        }
    }
    state.confirm?.let { UnblockDialog(it, state.unblocking, actions) }
}

@Composable
private fun BlockedRow(person: BlockedPerson, onAskUnblock: (BlockedPerson) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = person.label,
            style = if (person.name == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            fontFamily = if (person.name == null) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onAskUnblock(person) },
            modifier = Modifier.testTag(BlockedPeopleTestTags.unblock(person.userId)),
        ) { Text(stringResource(R.string.blocked_unblock)) }
    }
}

@Composable
private fun LoadFailed(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(text = stringResource(R.string.blocked_load_error), textAlign = TextAlign.Center)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.blocked_retry)) }
    }
}

@Composable
private fun CenteredText(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UnblockDialog(person: BlockedPerson, unblocking: Boolean, actions: BlockedPeopleActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelUnblock,
        title = { Text(stringResource(R.string.blocked_unblock_title, person.label)) },
        text = { Text(stringResource(R.string.blocked_unblock_body)) },
        confirmButton = {
            TextButton(
                onClick = actions.onUnblock,
                enabled = !unblocking,
                modifier = Modifier.testTag(BlockedPeopleTestTags.UNBLOCK_CONFIRM),
            ) { Text(stringResource(R.string.blocked_unblock), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelUnblock, enabled = !unblocking) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@GimPreviews
@Composable
private fun BlockedPeopleScreenPreview() {
    GimTheme {
        BlockedPeopleScreen(
            state = BlockedPeopleUiState(
                people = listOf(
                    BlockedPerson("uaaaa", "Lukas Weber"),
                    BlockedPerson("uabcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvq", null),
                ),
            ),
            actions = BlockedPeopleActions(),
        )
    }
}
