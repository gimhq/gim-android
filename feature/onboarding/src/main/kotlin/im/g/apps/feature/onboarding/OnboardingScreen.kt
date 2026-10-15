package im.g.apps.feature.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.theme.GimTheme

/** Callbacks of the stateless [OnboardingScreen]. */
class OnboardingCallbacks(
    val onServerUrlChange: (String) -> Unit,
    val onCreateAccount: () -> Unit,
    val onBack: () -> Unit,
    val onDisplayNameChange: (String) -> Unit,
    val onSubmitDisplayName: () -> Unit,
    val onCopyRecoveryCode: (String) -> Unit,
    val onRecoveryCodeSavedChange: (Boolean) -> Unit,
    val onAcknowledgeRecoveryCode: () -> Unit,
    val onHandleChange: (String) -> Unit,
    val onClaimHandle: () -> Unit,
    val onSkipHandle: () -> Unit,
)

/** Hosts [OnboardingScreen]; [onFinished] runs once the account exists. System back goes to [OnboardingViewModel.back]. */
@Composable
fun OnboardingRoute(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnFinished by rememberUpdatedState(onFinished)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                OnboardingEvent.Finished -> currentOnFinished()
            }
        }
    }
    val context = LocalContext.current
    val callbacks = remember(viewModel, context) {
        OnboardingCallbacks(
            onServerUrlChange = viewModel::onServerUrlChange,
            onCreateAccount = viewModel::createAccount,
            onBack = viewModel::back,
            onDisplayNameChange = viewModel::onDisplayNameChange,
            onSubmitDisplayName = viewModel::submitDisplayName,
            onCopyRecoveryCode = { copySensitive(context, it) },
            onRecoveryCodeSavedChange = viewModel::onRecoveryCodeSavedChange,
            onAcknowledgeRecoveryCode = viewModel::acknowledgeRecoveryCode,
            onHandleChange = viewModel::onHandleChange,
            onClaimHandle = viewModel::claimHandle,
            onSkipHandle = viewModel::skipHandle,
        )
    }
    OnboardingScreen(state = state, callbacks = callbacks, modifier = modifier)
}

/** Copies [text] flagged as sensitive, so Android 13+ hides it from the clipboard preview. */
private fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText(null, text).apply {
        description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33); ignored by older releases. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    callbacks: OnboardingCallbacks,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.step) {
                OnboardingStep.WELCOME -> WelcomeStep(state, callbacks)
                OnboardingStep.DISPLAY_NAME -> DisplayNameStep(state, callbacks)
                OnboardingStep.RECOVERY_CODE -> RecoveryCodeStep(state, callbacks)
                OnboardingStep.HANDLE -> HandleStep(state, callbacks)
            }
            state.error?.let { error ->
                Text(
                    text = error.message(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep(state: OnboardingUiState, callbacks: OnboardingCallbacks) {
    val focusManager = LocalFocusManager.current
    StepHeader(R.string.onboarding_welcome_title, R.string.onboarding_welcome_explanation)
    OnboardingField(
        value = state.serverUrl,
        onValueChange = callbacks.onServerUrlChange,
        label = R.string.onboarding_server_url_label,
        placeholder = R.string.onboarding_server_url_placeholder,
        error = state.serverUrlError,
        enabled = !state.isLoading,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        tag = OnboardingTestTags.SERVER_URL,
    )
    Button(
        onClick = {
            focusManager.clearFocus()
            callbacks.onCreateAccount()
        },
        enabled = state.canCreateAccount,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.CREATE_ACCOUNT),
    ) {
        Text(stringResource(R.string.onboarding_create_account))
    }
    // Provided by the link and restore flows; disabled until they ship.
    UnavailableOption(R.string.onboarding_link_device, OnboardingTestTags.LINK_DEVICE)
    UnavailableOption(R.string.onboarding_restore, OnboardingTestTags.RESTORE)
}

@Composable
private fun UnavailableOption(@StringRes label: Int, tag: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag),
        ) {
            Text(stringResource(label))
        }
        Text(
            text = stringResource(R.string.onboarding_available_soon),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp),
        )
    }
}

@Composable
private fun ColumnScope.DisplayNameStep(state: OnboardingUiState, callbacks: OnboardingCallbacks) {
    val focusManager = LocalFocusManager.current
    TextButton(
        onClick = callbacks.onBack,
        enabled = !state.isLoading,
        modifier = Modifier.testTag(OnboardingTestTags.BACK),
    ) {
        Text(stringResource(R.string.onboarding_back))
    }
    StepHeader(R.string.onboarding_display_name_title, R.string.onboarding_display_name_explanation)
    OnboardingField(
        value = state.displayName,
        onValueChange = callbacks.onDisplayNameChange,
        label = R.string.onboarding_display_name_label,
        error = state.displayNameError,
        enabled = !state.isLoading,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                focusManager.clearFocus()
                callbacks.onSubmitDisplayName()
            },
        ),
        tag = OnboardingTestTags.DISPLAY_NAME,
    )
    ProgressButton(
        label = R.string.onboarding_submit,
        progressDescription = R.string.onboarding_submitting,
        isLoading = state.isLoading,
        enabled = state.canSubmitDisplayName,
        tag = OnboardingTestTags.SUBMIT,
        onClick = {
            focusManager.clearFocus()
            callbacks.onSubmitDisplayName()
        },
    )
}

@Composable
private fun ColumnScope.RecoveryCodeStep(state: OnboardingUiState, callbacks: OnboardingCallbacks) {
    val code = state.recoveryCode.orEmpty()
    var copied by rememberSaveable { mutableStateOf(false) }
    StepHeader(R.string.onboarding_recovery_title, R.string.onboarding_recovery_explanation)
    val description = stringResource(R.string.onboarding_recovery_code_description, code)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Shown exactly as gim-core returns it: blocks of four separated by '-'.
        Text(
            text = code,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(16.dp)
                .semantics { contentDescription = description }
                .testTag(OnboardingTestTags.RECOVERY_CODE),
        )
    }
    OutlinedButton(
        onClick = {
            callbacks.onCopyRecoveryCode(code)
            copied = true
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.COPY_CODE),
    ) {
        Text(stringResource(if (copied) R.string.onboarding_code_copied else R.string.onboarding_copy_code))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = state.recoveryCodeSaved,
                role = Role.Checkbox,
                onValueChange = callbacks.onRecoveryCodeSavedChange,
            )
            .testTag(OnboardingTestTags.CODE_SAVED),
    ) {
        Checkbox(checked = state.recoveryCodeSaved, onCheckedChange = null)
        Text(
            text = stringResource(R.string.onboarding_code_saved),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
    Button(
        onClick = callbacks.onAcknowledgeRecoveryCode,
        enabled = state.recoveryCodeSaved,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.CODE_CONTINUE),
    ) {
        Text(stringResource(R.string.onboarding_continue))
    }
}

@Composable
private fun ColumnScope.HandleStep(state: OnboardingUiState, callbacks: OnboardingCallbacks) {
    val focusManager = LocalFocusManager.current
    StepHeader(R.string.onboarding_handle_title, R.string.onboarding_handle_explanation)
    OnboardingField(
        value = state.handle,
        onValueChange = callbacks.onHandleChange,
        label = R.string.onboarding_handle_label,
        supporting = R.string.onboarding_handle_supporting,
        error = state.handleError,
        enabled = !state.isLoading,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                focusManager.clearFocus()
                callbacks.onClaimHandle()
            },
        ),
        tag = OnboardingTestTags.HANDLE,
    )
    ProgressButton(
        label = R.string.onboarding_claim_handle,
        progressDescription = R.string.onboarding_claiming_handle,
        isLoading = state.isLoading,
        enabled = state.canClaimHandle,
        tag = OnboardingTestTags.CLAIM_HANDLE,
        onClick = {
            focusManager.clearFocus()
            callbacks.onClaimHandle()
        },
    )
    TextButton(
        onClick = callbacks.onSkipHandle,
        enabled = !state.isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.SKIP_HANDLE),
    ) {
        Text(stringResource(R.string.onboarding_skip))
    }
}

@Composable
private fun StepHeader(@StringRes title: Int, @StringRes explanation: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.headlineMedium,
    )
    Text(
        text = stringResource(explanation),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OnboardingField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    error: FieldError?,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    tag: String,
    @StringRes placeholder: Int? = null,
    @StringRes supporting: Int? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val supportingText = error?.messageRes() ?: supporting
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        placeholder = placeholder?.let { { Text(stringResource(it)) } },
        supportingText = supportingText?.let { { Text(stringResource(it)) } },
        isError = error != null,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}

@Composable
private fun ProgressButton(
    @StringRes label: Int,
    @StringRes progressDescription: Int,
    isLoading: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val progress = stringResource(progressDescription)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Box {
            if (isLoading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(20.dp)
                        .semantics { contentDescription = progress },
                )
            } else {
                Text(stringResource(label))
            }
        }
    }
}

@StringRes
private fun FieldError.messageRes(): Int = when (this) {
    FieldError.InvalidServerUrl -> R.string.onboarding_error_server_url
    FieldError.InvalidHandle -> R.string.onboarding_error_handle
    FieldError.HandleTaken -> R.string.onboarding_error_handle_taken
    FieldError.InvalidDisplayName -> R.string.onboarding_error_display_name
}

@Composable
private fun OnboardingError.message(): String = when (this) {
    OnboardingError.CannotReachServer -> stringResource(R.string.onboarding_error_cannot_reach_server)
    is OnboardingError.TryAgainLater ->
        pluralStringResource(R.plurals.onboarding_error_try_again_later, seconds.toInt(), seconds)
    OnboardingError.Generic -> stringResource(R.string.onboarding_error_generic)
}

internal object OnboardingTestTags {
    const val SERVER_URL = "onboarding_server_url"
    const val CREATE_ACCOUNT = "onboarding_create_account"
    const val LINK_DEVICE = "onboarding_link_device"
    const val RESTORE = "onboarding_restore"
    const val BACK = "onboarding_back"
    const val DISPLAY_NAME = "onboarding_display_name"
    const val SUBMIT = "onboarding_submit"
    const val RECOVERY_CODE = "onboarding_recovery_code"
    const val COPY_CODE = "onboarding_copy_code"
    const val CODE_SAVED = "onboarding_code_saved"
    const val CODE_CONTINUE = "onboarding_code_continue"
    const val HANDLE = "onboarding_handle"
    const val CLAIM_HANDLE = "onboarding_claim_handle"
    const val SKIP_HANDLE = "onboarding_skip_handle"
}

private val previewCallbacks = OnboardingCallbacks({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

@GimPreviews
@Composable
private fun OnboardingWelcomePreview() {
    GimTheme { OnboardingScreen(OnboardingUiState.withServerUrl("https://hub.example.eu"), previewCallbacks) }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingDisplayNameLoadingPreview() {
    GimTheme {
        OnboardingScreen(
            OnboardingUiState(step = OnboardingStep.DISPLAY_NAME, displayName = "Sofia Costa", isLoading = true),
            previewCallbacks,
        )
    }
}

@GimPreviews
@Composable
private fun OnboardingRecoveryCodePreview() {
    GimTheme {
        OnboardingScreen(
            OnboardingUiState(
                step = OnboardingStep.RECOVERY_CODE,
                recoveryCode = "7BQ2-K9MX-4TVA-0HZR-PC63-W8NE-1SJD-5GFY-QK2M-V7A",
            ),
            previewCallbacks,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingHandleErrorPreview() {
    GimTheme {
        OnboardingScreen(
            OnboardingUiState(
                step = OnboardingStep.HANDLE,
                handle = "sofia",
                handleError = FieldError.HandleTaken,
                error = OnboardingError.TryAgainLater(30),
            ),
            previewCallbacks,
        )
    }
}
