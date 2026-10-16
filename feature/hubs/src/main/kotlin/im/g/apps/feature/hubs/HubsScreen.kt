package im.g.apps.feature.hubs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.bridge.HubInfo
import im.g.apps.core.bridge.HubState
import im.g.apps.core.designsystem.component.EmptyState
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.theme.GimTheme
import java.net.URI
import kotlinx.collections.immutable.persistentListOf

/** Test tags; the hubs E2E test repeats these literals. */
internal object HubsTestTags {
    const val ROW_PREFIX = "hubs_row_"
    const val ADD_URL = "hubs_add_url"
    const val ADD_CONFIRM = "hubs_add_confirm"
    const val ADD_ERROR = "hubs_add_error"
}

@Composable
fun HubsRoute(viewModel: HubsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HubsScreen(
        state = state,
        actions = HubsActions(
            onBack = onBack,
            onAddHub = viewModel::openAddHub,
            onAddHubUrlChange = viewModel::addHubUrlChanged,
            onSubmitAddHub = viewModel::submitAddHub,
            onDismissAddHub = viewModel::dismissAddHub,
            onMakePrimary = viewModel::makePrimary,
            onRemove = viewModel::remove,
            onConfirmRemoveOwnedGroups = viewModel::confirmRemoveOwnedGroups,
            onErrorShown = viewModel::errorShown,
        ),
        modifier = modifier,
    )
}

/** Callbacks of [HubsScreen]. */
class HubsActions(
    val onBack: () -> Unit = {},
    val onAddHub: () -> Unit = {},
    val onAddHubUrlChange: (String) -> Unit = {},
    val onSubmitAddHub: () -> Unit = {},
    val onDismissAddHub: () -> Unit = {},
    val onMakePrimary: (HubInfo) -> Unit = {},
    val onRemove: (HubInfo) -> Unit = {},
    val onConfirmRemoveOwnedGroups: () -> Unit = {},
    val onErrorShown: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubsScreen(state: HubsUiState, actions: HubsActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hubs_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.hubs_back))
                    }
                },
                actions = {
                    IconButton(onClick = actions.onAddHub, enabled = !state.loading) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.hubs_add))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            when {
                state.hubs.isNotEmpty() || state.removed.isNotEmpty() -> HubList(state, actions)
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> EmptyState(
                    title = stringResource(R.string.hubs_empty_title),
                    body = "",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    state.addHub?.let { AddHubDialog(it, actions) }
    when (val error = state.error) {
        null -> Unit
        is HubsError.OwnsGroups -> OwnedGroupsDialog(error.groupCount, actions)
        else -> AlertDialog(
            onDismissRequest = actions.onErrorShown,
            text = { Text(errorMessage(error)) },
            confirmButton = { TextButton(onClick = actions.onErrorShown) { Text(stringResource(R.string.hubs_ok)) } },
        )
    }
}

@Composable
private fun HubList(state: HubsUiState, actions: HubsActions) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.hubs, key = { it.hubId }) { hub ->
            HubRow(hub, state, actions)
            HorizontalDivider()
        }
        if (state.removed.isNotEmpty()) {
            item(key = "removed_header") {
                Text(
                    text = stringResource(R.string.hubs_removed_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(state.removed, key = { "removed_" + it.hubId }) { hub -> HubRow(hub, state, actions) }
        }
    }
}

@Composable
private fun HubRow(hub: HubInfo, state: HubsUiState, actions: HubsActions) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HubsTestTags.ROW_PREFIX + hub.hubId)
            .alpha(if (hub.state == HubState.REMOVED) DISABLED_ALPHA else 1f)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = hubHost(hub.url) ?: stringResource(R.string.hubs_unknown_address),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (hub.isHome) Badge(stringResource(R.string.hubs_badge_home))
            if (hub.isPrimary) Badge(stringResource(R.string.hubs_badge_primary))
        }
        Text(
            text = stateLabel(hub.state) + " · " + stringResource(
                if (hub.signedIn) R.string.hubs_signed_in else R.string.hubs_not_signed_in,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { actions.onMakePrimary(hub) },
                enabled = state.canMakePrimary(hub) && !state.working,
            ) { Text(stringResource(R.string.hubs_make_primary)) }
            TextButton(
                onClick = { actions.onRemove(hub) },
                enabled = state.canRemove(hub) && !state.working,
            ) { Text(stringResource(R.string.hubs_remove)) }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AddHubDialog(add: AddHubState, actions: HubsActions) {
    AlertDialog(
        onDismissRequest = actions.onDismissAddHub,
        title = { Text(stringResource(R.string.hubs_add_title)) },
        text = {
            OutlinedTextField(
                value = add.url,
                onValueChange = actions.onAddHubUrlChange,
                label = { Text(stringResource(R.string.hubs_add_url_label)) },
                placeholder = { Text(stringResource(R.string.hubs_add_url_hint)) },
                singleLine = true,
                enabled = !add.submitting,
                isError = add.error != null,
                supportingText = add.error?.let { error ->
                    { Text(errorMessage(error), modifier = Modifier.testTag(HubsTestTags.ADD_ERROR)) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.testTag(HubsTestTags.ADD_URL),
            )
        },
        confirmButton = {
            TextButton(
                onClick = actions.onSubmitAddHub,
                enabled = !add.submitting && add.url.isNotBlank(),
                modifier = Modifier.testTag(HubsTestTags.ADD_CONFIRM),
            ) { Text(stringResource(R.string.hubs_add_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissAddHub, enabled = !add.submitting) {
                Text(stringResource(R.string.hubs_cancel))
            }
        },
    )
}

@Composable
private fun OwnedGroupsDialog(groupCount: Int, actions: HubsActions) {
    AlertDialog(
        onDismissRequest = actions.onErrorShown,
        title = { Text(stringResource(R.string.hubs_owned_title)) },
        text = { Text(stringResource(R.string.hubs_owned_body, groupCount)) },
        confirmButton = {
            TextButton(onClick = actions.onConfirmRemoveOwnedGroups) { Text(stringResource(R.string.hubs_owned_confirm)) }
        },
        dismissButton = { TextButton(onClick = actions.onErrorShown) { Text(stringResource(R.string.hubs_cancel)) } },
    )
}

@Composable
private fun stateLabel(state: HubState): String = when (state) {
    HubState.UNVERIFIED -> stringResource(R.string.hubs_state_unverified)
    HubState.VERIFIED -> stringResource(R.string.hubs_state_verified)
    HubState.REMOVED -> stringResource(R.string.hubs_state_removed)
}

@Composable
private fun errorMessage(error: HubsError): String = when (error) {
    HubsError.InvalidUrl -> stringResource(R.string.hubs_error_invalid_url)
    HubsError.Unreachable -> stringResource(R.string.hubs_error_unreachable)
    HubsError.NotVerified -> stringResource(R.string.hubs_error_not_verified)
    is HubsError.RetryLater -> stringResource(R.string.hubs_error_retry_later, error.seconds)
    is HubsError.OwnsGroups -> stringResource(R.string.hubs_owned_body, error.groupCount)
    HubsError.HomeHub -> stringResource(R.string.hubs_error_home_hub)
    HubsError.LastHub -> stringResource(R.string.hubs_error_last_hub)
    HubsError.NotDrained -> stringResource(R.string.hubs_error_not_drained)
    HubsError.CannotMakePrimary -> stringResource(R.string.hubs_error_cannot_make_primary)
    HubsError.NotListed -> stringResource(R.string.hubs_error_not_listed)
    HubsError.NotRegistered -> stringResource(R.string.hubs_error_not_registered)
    HubsError.Server -> stringResource(R.string.hubs_error_server)
    HubsError.Unknown -> stringResource(R.string.hubs_error_unknown)
}

/** `host[:port]` of a hub URL; the URL itself if it does not parse, null without one. */
internal fun hubHost(url: String?): String? {
    if (url == null) return null
    val uri = runCatching { URI(url) }.getOrNull()
    val host = uri?.host ?: return url
    return if (uri.port > 0) "$host:${uri.port}" else host
}

private const val DISABLED_ALPHA = 0.38f

@GimPreviews
@Composable
private fun HubsScreenPreview() {
    GimTheme {
        HubsScreen(
            state = HubsUiState(
                hubs = persistentListOf(
                    HubInfo("hub-a", "https://hub.example.eu", HubState.VERIFIED, 0, true, true, true),
                    HubInfo("hub-b", "https://athens.example.eu", HubState.VERIFIED, 1, false, false, true),
                    HubInfo("hub-c", null, HubState.UNVERIFIED, 2, false, false, false),
                ),
                removed = persistentListOf(
                    HubInfo("hub-d", "https://split.example.eu", HubState.REMOVED, 3, false, false, false),
                ),
                loading = false,
            ),
            actions = HubsActions(),
        )
    }
}
