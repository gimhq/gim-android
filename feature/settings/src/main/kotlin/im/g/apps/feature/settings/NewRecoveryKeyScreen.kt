package im.g.apps.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.bridge.RecoveryKitLabels
import im.g.apps.core.designsystem.component.GimConfirmDialog
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.theme.GimTheme

/** Test tags; the device E2E tests repeat these literals. */
object NewRecoveryKeyTestTags {
    const val CREATE = "new_key_create"
    const val PROGRESS = "new_key_progress"
    const val ACCOUNT_ID = "new_key_account_id"
    const val RECOVERY_KEY = "new_key_recovery_key"
    const val COPY = "new_key_copy"
    const val SHARE = "new_key_share"
    const val SAVED = "new_key_saved"
    const val CHECK = "new_key_check"
    const val CHECK_CONFIRM = "new_key_check_confirm"
    const val DONE = "new_key_done"
}

/** [onShareKit] saves the kit text as a file; [onDone] runs once the flow ends (done, or left). */
@Composable
fun NewRecoveryKeyRoute(
    viewModel: NewRecoveryKeyViewModel,
    onShareKit: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val currentOnDone by rememberUpdatedState(onDone)
    BackHandler(enabled = !state.left) { viewModel.back() }
    LaunchedEffect(state.left) { if (state.left) currentOnDone() }
    NewRecoveryKeyScreen(
        state = state,
        actions = NewRecoveryKeyActions(
            onBack = viewModel::back,
            onCreate = viewModel::create,
            formatKit = viewModel::kitText,
            onCopyKit = { copySensitive(context, it) },
            onShareKit = onShareKit,
            onContinueFromKit = viewModel::continueFromKit,
            onCheckChange = viewModel::onCheckChange,
            onConfirmCheck = viewModel::confirmCheck,
            onStay = viewModel::stay,
            onLeave = viewModel::leave,
            onDone = viewModel::finish,
        ),
        modifier = modifier,
    )
}

class NewRecoveryKeyActions(
    val onBack: () -> Unit = {},
    val onCreate: () -> Unit = {},
    /** The kit text for the localized labels. */
    val formatKit: (RecoveryKitLabels) -> String = { "" },
    val onCopyKit: (String) -> Unit = {},
    val onShareKit: (String) -> Unit = {},
    val onContinueFromKit: () -> Unit = {},
    val onCheckChange: (String) -> Unit = {},
    val onConfirmCheck: () -> Unit = {},
    val onStay: () -> Unit = {},
    val onLeave: () -> Unit = {},
    val onDone: () -> Unit = {},
)

/** One screen per [NewRecoveryKeyStep], laid out like onboarding's recovery kit and check. */
@Composable
fun NewRecoveryKeyScreen(state: NewRecoveryKeyUiState, actions: NewRecoveryKeyActions, modifier: Modifier = Modifier) {
    BackScaffold(title = stringResource(R.string.new_key_title), onBack = actions.onBack, modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.step) {
                NewRecoveryKeyStep.WARNING -> WarningStep(state, actions)
                NewRecoveryKeyStep.KIT -> KitStep(state, actions)
                NewRecoveryKeyStep.CHECK -> CheckStep(state, actions)
                NewRecoveryKeyStep.DONE -> DoneStep(actions)
            }
        }
    }
    if (state.confirmLeave) {
        GimConfirmDialog(
            title = stringResource(R.string.new_key_leave_title),
            body = stringResource(R.string.new_key_leave_body),
            confirmLabel = stringResource(R.string.new_key_leave),
            dismissLabel = stringResource(R.string.new_key_stay),
            onConfirm = actions.onLeave,
            onDismiss = actions.onStay,
            destructive = true,
        )
    }
}

@Composable
private fun ColumnScope.WarningStep(state: NewRecoveryKeyUiState, actions: NewRecoveryKeyActions) {
    Heading(R.string.new_key_warning_title)
    listOf(R.string.new_key_warning_old_key, R.string.new_key_warning_once, R.string.new_key_warning_save).forEach {
        Text(text = stringResource(it), style = MaterialTheme.typography.bodyLarge)
    }
    state.error?.let { ErrorText(it) }
    if (state.creating) {
        Row(
            modifier = Modifier.testTag(NewRecoveryKeyTestTags.PROGRESS),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Text(text = stringResource(R.string.new_key_creating), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Button(
        onClick = actions.onCreate,
        enabled = !state.creating,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NewRecoveryKeyTestTags.CREATE),
    ) { Text(stringResource(R.string.new_key_create)) }
}

@Composable
private fun ColumnScope.KitStep(state: NewRecoveryKeyUiState, actions: NewRecoveryKeyActions) {
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

    Heading(R.string.new_key_kit_title)
    Text(text = stringResource(R.string.new_key_kit_explanation), style = MaterialTheme.typography.bodyLarge)
    KitValue(
        label = R.string.new_key_kit_account_id_label,
        value = state.accountId,
        description = stringResource(R.string.new_key_kit_account_id_description, state.accountId),
        tag = NewRecoveryKeyTestTags.ACCOUNT_ID,
    )
    KitValue(
        label = R.string.new_key_kit_recovery_key_label,
        value = code,
        description = stringResource(R.string.new_key_kit_recovery_key_description, code),
        tag = NewRecoveryKeyTestTags.RECOVERY_KEY,
    )
    OutlinedButton(
        onClick = {
            actions.onCopyKit(kitText)
            copied = true
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NewRecoveryKeyTestTags.COPY),
    ) { Text(stringResource(if (copied) R.string.new_key_kit_copied else R.string.new_key_kit_copy)) }
    OutlinedButton(
        onClick = { actions.onShareKit(kitText) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NewRecoveryKeyTestTags.SHARE),
    ) { Text(stringResource(R.string.new_key_kit_share)) }
    state.unsynced?.let {
        Text(
            text = it.describe(R.string.new_key_unsynced_named, R.plurals.new_key_unsynced_count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(
        onClick = actions.onContinueFromKit,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NewRecoveryKeyTestTags.SAVED),
    ) { Text(stringResource(R.string.new_key_kit_saved)) }
}

/** A labelled, selectable monospace value that TalkBack reads as [description]. */
@Composable
private fun KitValue(@StringRes label: Int, value: String, description: String, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(16.dp)
                        .semantics { contentDescription = description }
                        .testTag(tag),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.CheckStep(state: NewRecoveryKeyUiState, actions: NewRecoveryKeyActions) {
    val focusManager = LocalFocusManager.current
    val confirm = {
        focusManager.clearFocus()
        actions.onConfirmCheck()
    }
    Heading(R.string.new_key_check_title)
    Text(text = stringResource(R.string.new_key_check_explanation), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = state.check,
        onValueChange = actions.onCheckChange,
        label = { Text(stringResource(R.string.new_key_check_label)) },
        supportingText = if (state.checkMismatch) {
            {
                Text(
                    text = stringResource(R.string.new_key_check_mismatch),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        } else {
            null
        },
        isError = state.checkMismatch,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { confirm() }),
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NewRecoveryKeyTestTags.CHECK),
    )
    Button(
        onClick = confirm,
        enabled = state.canConfirmCheck,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NewRecoveryKeyTestTags.CHECK_CONFIRM),
    ) { Text(stringResource(R.string.new_key_check_confirm)) }
}

@Composable
private fun ColumnScope.DoneStep(actions: NewRecoveryKeyActions) {
    Heading(R.string.new_key_done_title)
    Text(text = stringResource(R.string.new_key_done_body), style = MaterialTheme.typography.bodyLarge)
    Button(
        onClick = actions.onDone,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(NewRecoveryKeyTestTags.DONE),
    ) { Text(stringResource(R.string.new_key_done)) }
}

@Composable
internal fun Heading(@StringRes text: Int) {
    Text(text = stringResource(text), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}

/** An error that TalkBack announces as it appears. */
@Composable
internal fun ErrorText(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The servers by URL in [named] (one `%1$s`), else counted with [counted]. */
@Composable
internal fun ServerList.describe(@StringRes named: Int, @PluralsRes counted: Int): String =
    if (urls.isNotEmpty()) stringResource(named, urls.joinToString(", ")) else pluralStringResource(counted, count, count)

/** Copies [text] flagged as sensitive, so Android 13+ hides it from the clipboard preview. */
private fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText(null, text).apply {
        description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33); ignored by older releases. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

@GimPreviews
@Composable
private fun NewRecoveryKeyKitPreview() {
    GimTheme {
        NewRecoveryKeyScreen(
            state = NewRecoveryKeyUiState(
                step = NewRecoveryKeyStep.KIT,
                accountId = "uabcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvq",
                recoveryCode = "7BQ2-K9MX-4TVA-0HZR-PC63-W8NE-1SJD-5GFY-QK2M-V7A",
                unsynced = ServerList(listOf("https://two.example"), 1),
            ),
            actions = NewRecoveryKeyActions(),
        )
    }
}
