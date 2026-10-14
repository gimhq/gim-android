package im.g.apps.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
    val onHandleChange: (String) -> Unit,
    val onDisplayNameChange: (String) -> Unit,
    val onSubmit: () -> Unit,
)

@Composable
fun OnboardingRoute(
    viewModel: OnboardingViewModel,
    onRegistered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnRegistered by rememberUpdatedState(onRegistered)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is OnboardingEvent.Registered -> currentOnRegistered()
            }
        }
    }
    val callbacks = remember(viewModel) {
        OnboardingCallbacks(
            onServerUrlChange = viewModel::onServerUrlChange,
            onHandleChange = viewModel::onHandleChange,
            onDisplayNameChange = viewModel::onDisplayNameChange,
            onSubmit = viewModel::submit,
        )
    }
    OnboardingScreen(state = state, callbacks = callbacks, modifier = modifier)
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    callbacks: OnboardingCallbacks,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.onboarding_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    imeAction = ImeAction.Next,
                ),
                tag = OnboardingTestTags.SERVER_URL,
            )
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
                    imeAction = ImeAction.Next,
                ),
                tag = OnboardingTestTags.HANDLE,
            )
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
                        callbacks.onSubmit()
                    },
                ),
                tag = OnboardingTestTags.DISPLAY_NAME,
            )
            state.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            SubmitButton(
                isLoading = state.isLoading,
                enabled = state.canSubmit,
                onClick = {
                    focusManager.clearFocus()
                    callbacks.onSubmit()
                },
            )
        }
    }
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
private fun SubmitButton(isLoading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val submitting = stringResource(R.string.onboarding_submitting)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.SUBMIT),
    ) {
        Box {
            if (isLoading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(20.dp)
                        .semantics { contentDescription = submitting },
                )
            } else {
                Text(stringResource(R.string.onboarding_submit))
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

@StringRes
private fun OnboardingError.messageRes(): Int = when (this) {
    OnboardingError.CannotReachServer -> R.string.onboarding_error_cannot_reach_server
    OnboardingError.Generic -> R.string.onboarding_error_generic
}

internal object OnboardingTestTags {
    const val SERVER_URL = "onboarding_server_url"
    const val HANDLE = "onboarding_handle"
    const val DISPLAY_NAME = "onboarding_display_name"
    const val SUBMIT = "onboarding_submit"
}

private val previewCallbacks = OnboardingCallbacks({}, {}, {}, {})

@GimPreviews
@Composable
private fun OnboardingScreenEmptyPreview() {
    GimTheme { OnboardingScreen(OnboardingUiState(), previewCallbacks) }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenErrorPreview() {
    GimTheme {
        OnboardingScreen(
            OnboardingUiState(
                serverUrl = "gim.example.eu",
                handle = "sofia",
                displayName = "Sofia Costa",
                serverUrlError = FieldError.InvalidServerUrl,
                handleError = FieldError.HandleTaken,
                error = OnboardingError.CannotReachServer,
            ),
            previewCallbacks,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenLoadingPreview() {
    GimTheme {
        OnboardingScreen(
            OnboardingUiState(
                serverUrl = "https://gim.example.eu",
                handle = "sofia",
                displayName = "Sofia Costa",
                isLoading = true,
            ),
            previewCallbacks,
        )
    }
}
