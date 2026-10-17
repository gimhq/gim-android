package im.g.apps.feature.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.bridge.DeviceInfo
import im.g.apps.core.designsystem.component.EmptyState
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.component.userSuspendedMessage
import im.g.apps.core.designsystem.theme.GimTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.collections.immutable.persistentListOf

/** Test tags; the device E2E test repeats these literals. */
internal object DevicesTestTags {
    const val USER_ID = "devices_user_id"
    const val ROW_PREFIX = "devices_row_"
}

@Composable
fun DevicesRoute(
    viewModel: DevicesViewModel,
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    DevicesScreen(
        state = state,
        actions = DevicesActions(
            onBack = onBack,
            onRefresh = viewModel::refresh,
            onCopyUserId = { clipboard.setText(AnnotatedString(it)) },
            onRevoke = viewModel::requestRevoke,
            onConfirmRevoke = viewModel::confirmRevoke,
            onDismissRevoke = viewModel::dismissRevoke,
            onErrorShown = viewModel::errorShown,
            onSignedOut = onSignedOut,
        ),
        modifier = modifier,
    )
}

/** Callbacks of [DevicesScreen]. */
class DevicesActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCopyUserId: (String) -> Unit = {},
    val onRevoke: (DeviceInfo) -> Unit = {},
    val onConfirmRevoke: () -> Unit = {},
    val onDismissRevoke: () -> Unit = {},
    val onErrorShown: () -> Unit = {},
    val onSignedOut: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(state: DevicesUiState, actions: DevicesActions, modifier: Modifier = Modifier) {
    val snackbarHostState = remember { SnackbarHostState() }
    val errorText = state.error?.let { errorMessage(it) }
    LaunchedEffect(state.error) {
        if (errorText != null) {
            snackbarHostState.showSnackbar(errorText)
            actions.onErrorShown()
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.devices_back))
                    }
                },
                actions = {
                    IconButton(onClick = actions.onRefresh, enabled = !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.devices_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.loading || state.revoking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.userId?.let { UserIdHeader(it, onCopy = { actions.onCopyUserId(it) }) }
            HorizontalDivider()
            when {
                state.devices.isNotEmpty() -> DeviceList(state, actions)
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> EmptyState(
                    title = stringResource(R.string.devices_empty_title),
                    body = "",
                    modifier = Modifier.fillMaxSize(),
                    action = if (state.suspended) {
                        null
                    } else {
                        { TextButton(onClick = actions.onRefresh) { Text(stringResource(R.string.devices_retry)) } }
                    },
                )
            }
        }
    }
    state.pendingRevoke?.let { RevokeDialog(it, actions) }
    if (state.removed) RemovedDialog(actions.onSignedOut)
}

@Composable
private fun UserIdHeader(userId: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.devices_user_id_label), style = MaterialTheme.typography.labelLarge)
            Text(
                text = userId,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag(DevicesTestTags.USER_ID),
            )
            Text(
                text = stringResource(R.string.devices_user_id_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onCopy) { Text(stringResource(R.string.devices_copy)) }
    }
}

@Composable
private fun DeviceList(state: DevicesUiState, actions: DevicesActions) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.devices, key = { it.deviceId }) { device ->
            DeviceRow(
                device = device,
                added = formatDeviceDate(device.createdAtMs, locale),
                canRevoke = state.canRevoke(device) && !state.revoking,
                onRevoke = { actions.onRevoke(device) },
            )
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceInfo, added: String, canRevoke: Boolean, onRevoke: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DevicesTestTags.ROW_PREFIX + device.deviceId)
            .alpha(if (device.revoked) DISABLED_ALPHA else 1f)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (device.isCurrent) {
                    stringResource(R.string.devices_this_device)
                } else {
                    stringResource(R.string.devices_other_device, device.shortId)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = device.deviceId,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (device.revoked) {
                    stringResource(R.string.devices_revoked)
                } else {
                    stringResource(R.string.devices_added, added)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!device.isCurrent && !device.revoked) {
            IconButton(onClick = onRevoke, enabled = canRevoke) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.devices_revoke))
            }
        }
    }
}

@Composable
private fun RevokeDialog(device: DeviceInfo, actions: DevicesActions) {
    AlertDialog(
        onDismissRequest = actions.onDismissRevoke,
        title = { Text(stringResource(R.string.devices_revoke_title)) },
        text = { Text(stringResource(R.string.devices_revoke_body, device.shortId)) },
        confirmButton = {
            TextButton(onClick = actions.onConfirmRevoke) { Text(stringResource(R.string.devices_revoke_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissRevoke) { Text(stringResource(R.string.devices_cancel)) }
        },
    )
}

/** Not dismissable: this device has no session left, so the only way on is onboarding. */
@Composable
private fun RemovedDialog(onSignedOut: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.devices_removed_title)) },
        text = { Text(stringResource(R.string.devices_removed_body)) },
        confirmButton = {
            TextButton(onClick = onSignedOut) { Text(stringResource(R.string.devices_removed_ok)) }
        },
    )
}

@Composable
private fun errorMessage(error: DevicesError): String = when (error) {
    DevicesError.Network -> stringResource(R.string.devices_error_network)
    is DevicesError.RetryLater -> stringResource(R.string.devices_error_retry_later, error.seconds)
    DevicesError.CurrentDevice -> stringResource(R.string.devices_error_current_device)
    DevicesError.NotFound -> stringResource(R.string.devices_error_not_found)
    DevicesError.Server -> stringResource(R.string.devices_error_server)
    is DevicesError.Suspended -> userSuspendedMessage(error.hub)
    DevicesError.Unknown -> stringResource(R.string.devices_error_unknown)
}

/** The first characters of the device id, enough to tell devices apart. */
internal val DeviceInfo.shortId: String
    get() = deviceId.take(SHORT_ID_LENGTH)

/** [createdAtMs] as a medium date in [locale]. */
internal fun formatDeviceDate(createdAtMs: Long, locale: Locale, zone: TimeZone = TimeZone.getDefault()): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = zone }.format(Date(createdAtMs))

private const val SHORT_ID_LENGTH = 8
private const val DISABLED_ALPHA = 0.38f

@GimPreviews
@Composable
private fun DevicesScreenPreview() {
    GimTheme {
        DevicesScreen(
            state = DevicesUiState(
                userId = "u-7f3a9c21e4b84d10",
                devices = persistentListOf(
                    DeviceInfo("d-1a2b3c4d5e6f", 1_760_000_000_000, isCurrent = true, revoked = false),
                    DeviceInfo("d-9f8e7d6c5b4a", 1_760_500_000_000, isCurrent = false, revoked = false),
                    DeviceInfo("d-0a0b0c0d0e0f", 0, isCurrent = false, revoked = true),
                ),
                loading = false,
            ),
            actions = DevicesActions(),
        )
    }
}
