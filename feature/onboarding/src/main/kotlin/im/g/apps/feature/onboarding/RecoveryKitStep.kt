package im.g.apps.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import im.g.apps.core.bridge.RecoveryKitLabels
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.coroutines.launch

/**
 * The recovery kit, shown once: Account ID and recovery key, with copy, password manager (API 34+) and file
 * actions, then "I saved it".
 */
@Composable
internal fun ColumnScope.RecoveryKitStep(state: OnboardingUiState, callbacks: OnboardingCallbacks) {
    val accountId = state.accountId.orEmpty()
    // Shown exactly as gim-core returns it: blocks of four separated by '-'.
    val code = state.recoveryCode.orEmpty()
    val kitText = callbacks.formatRecoveryKit(
        accountId,
        code,
        RecoveryKitLabels(
            title = stringResource(R.string.onboarding_kit_file_title),
            accountId = stringResource(R.string.onboarding_kit_account_id_label),
            recoveryKey = stringResource(R.string.onboarding_kit_recovery_key_label),
            note = stringResource(R.string.onboarding_kit_file_note),
        ),
    )
    val scope = rememberCoroutineScope()
    var copied by rememberSaveable { mutableStateOf(false) }
    var passwordFailed by rememberSaveable { mutableStateOf(false) }

    StepHeader(R.string.onboarding_kit_title, R.string.onboarding_kit_explanation)
    KitValue(
        label = R.string.onboarding_kit_account_id_label,
        value = accountId,
        description = stringResource(R.string.onboarding_kit_account_id_description, accountId),
        tag = OnboardingTestTags.KIT_ACCOUNT_ID,
    )
    KitValue(
        label = R.string.onboarding_kit_recovery_key_label,
        value = code,
        description = stringResource(R.string.onboarding_recovery_code_description, code),
        tag = OnboardingTestTags.RECOVERY_CODE,
    )
    OutlinedButton(
        onClick = {
            callbacks.onCopyRecoveryKit(kitText)
            copied = true
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.KIT_COPY),
    ) {
        Text(stringResource(if (copied) R.string.onboarding_kit_copied else R.string.onboarding_kit_copy))
    }
    if (isPasswordManagerAvailable()) {
        OutlinedButton(
            onClick = {
                passwordFailed = false
                scope.launch {
                    passwordFailed = callbacks.onSaveToPasswordManager(accountId, code) == PasswordSaveResult.FAILED
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(OnboardingTestTags.KIT_SAVE_PASSWORD),
        ) {
            Text(stringResource(R.string.onboarding_kit_save_password))
        }
    }
    OutlinedButton(
        onClick = { callbacks.onShareRecoveryKit(kitText) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.KIT_SHARE),
    ) {
        Text(stringResource(R.string.onboarding_kit_share))
    }
    if (passwordFailed) {
        Text(
            text = stringResource(R.string.onboarding_kit_save_password_failed),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Button(
        onClick = callbacks.onContinueFromKit,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.CODE_CONTINUE),
    ) {
        Text(stringResource(R.string.onboarding_kit_saved))
    }
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

/** The user types the recovery key's last four characters to show the kit was saved. */
@Composable
internal fun ColumnScope.RecoveryCheckStep(state: OnboardingUiState, callbacks: OnboardingCallbacks) {
    val focusManager = LocalFocusManager.current
    TextButton(
        onClick = callbacks.onBack,
        modifier = Modifier.testTag(OnboardingTestTags.BACK),
    ) {
        Text(stringResource(R.string.onboarding_back))
    }
    StepHeader(R.string.onboarding_kit_check_title, R.string.onboarding_kit_check_explanation)
    OnboardingField(
        value = state.kitCheck,
        onValueChange = callbacks.onKitCheckChange,
        label = R.string.onboarding_kit_check_label,
        error = state.kitCheckError,
        enabled = !state.isLoading,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                focusManager.clearFocus()
                callbacks.onConfirmKitCheck()
            },
        ),
        monospace = true,
        tag = OnboardingTestTags.KIT_CHECK,
    )
    Button(
        onClick = {
            focusManager.clearFocus()
            callbacks.onConfirmKitCheck()
        },
        enabled = state.canConfirmKitCheck,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.KIT_CHECK_CONFIRM),
    ) {
        Text(stringResource(R.string.onboarding_kit_check_confirm))
    }
}

@GimPreviews
@Composable
private fun OnboardingRecoveryCheckPreview() {
    GimTheme {
        OnboardingScreen(
            OnboardingUiState(
                step = OnboardingStep.RECOVERY_CHECK,
                recoveryCode = "7BQ2-K9MX-4TVA-0HZR-PC63-W8NE-1SJD-5GFY-QK2M-V7A",
                kitCheck = "V7AB",
                kitCheckError = FieldError.KitCheckMismatch,
            ),
            previewCallbacks,
        )
    }
}
