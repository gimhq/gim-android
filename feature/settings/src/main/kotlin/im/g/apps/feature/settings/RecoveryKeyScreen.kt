package im.g.apps.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.bridge.RecoveryKitLabels
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.explainer.AccountExplainerPoint
import im.g.apps.core.designsystem.explainer.AccountExplainerPointContent
import im.g.apps.core.designsystem.explainer.AccountExplainerTestTags
import im.g.apps.core.designsystem.theme.GimTheme

/** Test tags; the device E2E tests repeat these literals. */
object RecoveryKeyTestTags {
    const val SHOW = "recovery_key_show"
    const val ACCOUNT_ID = "recovery_key_account_id"
    const val RECOVERY_KEY = "recovery_key_value"
    const val COPY = "recovery_key_copy"
    const val SAVE = "recovery_key_save"
    const val QR_TOGGLE = "recovery_key_qr_toggle"
    const val QR = "recovery_key_qr"
    const val MISSING = "recovery_key_missing"
    const val CREATE_NEW = "recovery_key_create_new"
}

/**
 * The key reveal: [authenticator] confirms it is the user before the key is shown; leaving the screen or the app
 * hides it again. [onShareKit] saves the kit text as a file; [onCreateNewKey] opens "Create a new recovery key".
 */
@Composable
fun RecoveryKeyRoute(
    viewModel: RecoveryKeyViewModel,
    authenticator: DeviceAuthenticator,
    onShareKit: (String) -> Unit,
    onCreateNewKey: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val title = stringResource(R.string.recovery_key_auth_title)
    val subtitle = stringResource(R.string.recovery_key_auth_subtitle)
    val currentAuthenticator by rememberUpdatedState(authenticator)
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.hide() }
    RecoveryKeyScreen(
        state = state,
        actions = RecoveryKeyActions(
            onBack = onBack,
            onShow = {
                viewModel.startAuth()
                currentAuthenticator.authenticate(title, subtitle, viewModel::onAuthResult)
            },
            formatKit = viewModel::kitText,
            onCopyKit = { copySensitive(context, it) },
            onShareKit = onShareKit,
            onToggleQr = viewModel::toggleQr,
            onCreateNewKey = onCreateNewKey,
        ),
        modifier = modifier,
    )
}

class RecoveryKeyActions(
    val onBack: () -> Unit = {},
    val onShow: () -> Unit = {},
    /** The kit text for the localized labels. */
    val formatKit: (RecoveryKitLabels) -> String = { "" },
    val onCopyKit: (String) -> Unit = {},
    val onShareKit: (String) -> Unit = {},
    val onToggleQr: () -> Unit = {},
    val onCreateNewKey: () -> Unit = {},
)

/** "Your recovery key": what it is, then the key behind "Show recovery key", or how to get one on this device. */
@Composable
fun RecoveryKeyScreen(state: RecoveryKeyUiState, actions: RecoveryKeyActions, modifier: Modifier = Modifier) {
    BackScaffold(title = stringResource(R.string.recovery_key_title), onBack = actions.onBack, modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.status) {
                RecoveryKeyStatus.LOADING -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.CenterHorizontally),
                )
                RecoveryKeyStatus.LOCKED -> LockedContent(state, actions)
                RecoveryKeyStatus.SHOWN -> ShownContent(state, actions)
                RecoveryKeyStatus.MISSING -> MissingContent(actions)
                RecoveryKeyStatus.ERROR -> ErrorText(R.string.recovery_key_error)
            }
        }
    }
}

@Composable
private fun ColumnScope.LockedContent(state: RecoveryKeyUiState, actions: RecoveryKeyActions) {
    AccountExplainerPointContent(
        point = AccountExplainerPoint.RECOVERY_KEY,
        modifier = Modifier.testTag(AccountExplainerTestTags.point(AccountExplainerPoint.RECOVERY_KEY)),
    )
    listOf(R.string.recovery_key_together, R.string.recovery_key_once, R.string.recovery_key_keep_safe).forEach {
        Text(text = stringResource(it), style = MaterialTheme.typography.bodyLarge)
    }
    state.authError?.let { ErrorText(it) }
    Button(
        onClick = actions.onShow,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecoveryKeyTestTags.SHOW),
    ) { Text(stringResource(R.string.recovery_key_show)) }
}

@Composable
private fun ColumnScope.ShownContent(state: RecoveryKeyUiState, actions: RecoveryKeyActions) {
    SecureWindow()
    val code = state.recoveryCode.orEmpty()
    val kitText = actions.formatKit(
        RecoveryKitLabels(
            title = stringResource(R.string.new_key_kit_file_title),
            accountId = stringResource(R.string.new_key_kit_account_id_label),
            recoveryKey = stringResource(R.string.new_key_kit_recovery_key_label),
            note = stringResource(R.string.new_key_kit_file_note),
        ),
    )
    var copied by rememberSaveable { mutableStateOf(false) }
    Text(text = stringResource(R.string.recovery_key_keep_safe), style = MaterialTheme.typography.bodyLarge)
    KitValue(
        label = R.string.new_key_kit_account_id_label,
        value = state.accountId,
        description = stringResource(R.string.new_key_kit_account_id_description, state.accountId),
        tag = RecoveryKeyTestTags.ACCOUNT_ID,
    )
    KitValue(
        label = R.string.new_key_kit_recovery_key_label,
        value = code,
        description = stringResource(R.string.new_key_kit_recovery_key_description, code),
        tag = RecoveryKeyTestTags.RECOVERY_KEY,
    )
    OutlinedButton(
        onClick = {
            actions.onCopyKit(kitText)
            copied = true
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecoveryKeyTestTags.COPY),
    ) { Text(stringResource(if (copied) R.string.new_key_kit_copied else R.string.new_key_kit_copy)) }
    OutlinedButton(
        onClick = { actions.onShareKit(kitText) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecoveryKeyTestTags.SAVE),
    ) { Text(stringResource(R.string.new_key_kit_share)) }
    OutlinedButton(
        onClick = actions.onToggleQr,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecoveryKeyTestTags.QR_TOGGLE),
    ) { Text(stringResource(if (state.showQr) R.string.recovery_key_qr_hide else R.string.recovery_key_qr_show)) }
    if (state.showQr) {
        ProfileQrCode(
            text = kitText,
            contentDescription = stringResource(R.string.recovery_key_qr_description),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(RecoveryKeyTestTags.QR),
        )
    }
    Text(
        text = stringResource(R.string.recovery_key_hidden_on_leave),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ColumnScope.MissingContent(actions: RecoveryKeyActions) {
    Text(
        text = stringResource(R.string.recovery_key_missing_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.testTag(RecoveryKeyTestTags.MISSING),
    )
    Text(text = stringResource(R.string.recovery_key_missing_body), style = MaterialTheme.typography.bodyLarge)
    Button(
        onClick = actions.onCreateNewKey,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RecoveryKeyTestTags.CREATE_NEW),
    ) { Text(stringResource(R.string.settings_new_recovery_key)) }
}

@GimPreviews
@Composable
private fun RecoveryKeyLockedPreview() {
    GimTheme { RecoveryKeyScreen(RecoveryKeyUiState(status = RecoveryKeyStatus.LOCKED), RecoveryKeyActions()) }
}

@GimPreviews
@Composable
private fun RecoveryKeyMissingPreview() {
    GimTheme { RecoveryKeyScreen(RecoveryKeyUiState(status = RecoveryKeyStatus.MISSING), RecoveryKeyActions()) }
}
