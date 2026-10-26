package im.g.apps.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.theme.GimTheme

/** Test tags. */
internal object KeepServerTestTags {
    const val CURRENT = "keep_server_current"
    const val INPUT = "keep_server_input"
    const val SAVE = "keep_server_save"
    const val RESET = "keep_server_reset"
    const val TURN_OFF = "keep_server_turn_off"
    const val RESTART = "keep_server_restart"
}

/** Callbacks of [KeepServerScreen]. */
class KeepServerActions(
    val onBack: () -> Unit = {},
    val onInputChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onTurnOff: () -> Unit = {},
)

@Composable
fun KeepServerRoute(viewModel: KeepServerViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    KeepServerScreen(
        state = state,
        actions = KeepServerActions(
            onBack = onBack,
            onInputChange = viewModel::onInputChange,
            onSave = viewModel::save,
            onReset = viewModel::reset,
            onTurnOff = viewModel::turnOff,
        ),
        modifier = modifier,
    )
}

/** "Keep server": what it does, the current address, change it, use the default or turn it off. */
@Composable
fun KeepServerScreen(state: KeepServerUiState, actions: KeepServerActions, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    val save = {
        focusManager.clearFocus()
        actions.onSave()
    }
    BackScaffold(title = stringResource(R.string.keep_server_title), onBack = actions.onBack, modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsRow(
                title = stringResource(R.string.keep_server_current),
                onClick = null,
                value = state.current ?: stringResource(R.string.keep_server_off),
                // One TalkBack stop for the label and the address.
                modifier = Modifier
                    .semantics(mergeDescendants = true) {}
                    .testTag(KeepServerTestTags.CURRENT),
            )
            Text(
                text = stringResource(if (state.current != null) R.string.keep_server_about else R.string.keep_server_off_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (state.restartNeeded) RestartNote()
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = actions.onInputChange,
                    label = { Text(stringResource(R.string.keep_server_address)) },
                    supportingText = state.error?.let { error -> { ErrorText(error) } },
                    isError = state.error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(KeepServerTestTags.INPUT),
                )
                Button(
                    onClick = save,
                    enabled = state.input.isNotBlank() && normalizeKeepServerUrl(state.input) != state.current,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(KeepServerTestTags.SAVE),
                ) { Text(stringResource(R.string.keep_server_save)) }
                if (state.choice != KeepServerChoice.Default) {
                    OutlinedButton(
                        onClick = actions.onReset,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(KeepServerTestTags.RESET),
                    ) { Text(stringResource(R.string.keep_server_reset)) }
                }
                if (state.current != null) {
                    OutlinedButton(
                        onClick = actions.onTurnOff,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(KeepServerTestTags.TURN_OFF),
                    ) { Text(stringResource(R.string.keep_server_turn_off)) }
                }
            }
        }
    }
}

@Composable
private fun ErrorText(error: KeepServerUrlError) {
    Text(
        text = stringResource(
            when (error) {
                KeepServerUrlError.INVALID -> R.string.keep_server_error_invalid
                KeepServerUrlError.INSECURE -> R.string.keep_server_error_insecure
            },
        ),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun RestartNote() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(KeepServerTestTags.RESTART),
    ) {
        Text(
            text = stringResource(R.string.keep_server_restart),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@GimPreviews
@Composable
private fun KeepServerScreenPreview() {
    GimTheme {
        KeepServerScreen(
            state = KeepServerUiState(
                choice = KeepServerChoice.Default,
                current = "https://keep.g.im",
                buildDefault = "https://keep.g.im",
                input = "https://keep.g.im",
            ),
            actions = KeepServerActions(),
        )
    }
}
