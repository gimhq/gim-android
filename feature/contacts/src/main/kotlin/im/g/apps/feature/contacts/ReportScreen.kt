package im.g.apps.feature.contacts

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.bridge.ReportReason
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.component.GimSnackbarHost
import im.g.apps.core.designsystem.component.GimTopBar
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

object ReportTestTags {
    const val NOTE = "report_note"
    const val WARNING = "report_warning"
    const val ALSO_BLOCK = "report_also_block"
    const val SEND = "report_send"
    const val SENT = "report_sent"
    const val DONE = "report_done"

    fun reason(reason: ReportReason) = "report_reason_${reason.name.lowercase()}"

    fun message(messageId: String) = "report_message_$messageId"
}

/** [onDone] leaves the screen after the confirmation. */
@Composable
fun ReportRoute(viewModel: ReportViewModel, onBack: () -> Unit, onDone: () -> Unit = onBack) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ReportEvent.ShowError -> snackbarHostState.showSnackbar(event.error.message(context))
            }
        }
    }
    ReportScreen(
        state = state,
        actions = ReportActions(
            onBack = onBack,
            onReasonChange = viewModel::onReasonChange,
            onNoteChange = viewModel::onNoteChange,
            onToggleMessage = viewModel::toggleMessage,
            onAlsoBlockChange = viewModel::onAlsoBlockChange,
            onSend = viewModel::send,
            onDone = onDone,
        ),
        snackbarHostState = snackbarHostState,
    )
}

internal class ReportActions(
    val onBack: () -> Unit = {},
    val onReasonChange: (ReportReason) -> Unit = {},
    val onNoteChange: (String) -> Unit = {},
    val onToggleMessage: (String) -> Unit = {},
    val onAlsoBlockChange: (Boolean) -> Unit = {},
    val onSend: () -> Unit = {},
    val onDone: () -> Unit = {},
)

/** The report form (reason, note, messages, the plaintext warning, "Also block"), then a confirmation. */
@Composable
internal fun ReportScreen(
    state: ReportUiState,
    actions: ReportActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = {
            GimTopBar(
                title = state.name?.let { stringResource(R.string.contacts_report_title, it) }
                    ?: stringResource(R.string.contacts_report_title_unknown),
                navigationIcon = { BackButton(if (state.sent) actions.onDone else actions.onBack) },
            )
        },
        snackbarHost = { GimSnackbarHost(snackbarHostState) },
    ) { padding ->
        val modifier = Modifier
            .padding(padding)
            .fillMaxSize()
        if (state.sent) ReportSent(state, actions.onDone, modifier) else ReportForm(state, actions, modifier)
    }
}

@Composable
private fun ReportForm(state: ReportUiState, actions: ReportActions, modifier: Modifier) {
    LazyColumn(modifier = modifier.padding(horizontal = 16.dp)) {
        item(key = "reason-title") { SectionTitle(R.string.contacts_report_reason_title) }
        items(ReportReason.entries, key = { "reason-${it.name}" }) { reason ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = state.reason == reason,
                        enabled = !state.sending,
                        role = Role.RadioButton,
                        onClick = { actions.onReasonChange(reason) },
                    )
                    .testTag(ReportTestTags.reason(reason)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.reason == reason, onClick = null, enabled = !state.sending)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(reason.label))
            }
        }
        item(key = "note") {
            OutlinedTextField(
                value = state.note,
                onValueChange = actions.onNoteChange,
                label = { Text(stringResource(R.string.contacts_report_note_label)) },
                supportingText = {
                    Text(stringResource(R.string.contacts_report_note_counter, state.note.length, MAX_REPORT_NOTE_LENGTH))
                },
                enabled = !state.sending,
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag(ReportTestTags.NOTE),
            )
        }
        item(key = "messages-title") {
            SectionTitle(R.string.contacts_report_messages_title)
            Text(
                text = stringResource(R.string.contacts_report_messages_hint, MAX_REPORT_MESSAGES),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "warning") {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag(ReportTestTags.WARNING),
            ) {
                Text(
                    text = stringResource(R.string.contacts_report_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        val messages = state.messages
        when {
            messages == null -> item(key = "messages-loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            messages.isEmpty() -> item(key = "messages-empty") {
                Text(
                    text = stringResource(R.string.contacts_report_messages_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            else -> {
                item(key = "messages-counter") {
                    Text(
                        text = stringResource(
                            R.string.contacts_report_messages_counter,
                            state.selected.size,
                            MAX_REPORT_MESSAGES,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(messages, key = { "message-${it.messageId}" }) { message ->
                    CheckRow(
                        text = message.text,
                        checked = message.messageId in state.selected,
                        enabled = !state.sending && state.canPick(message.messageId),
                        onCheckedChange = { actions.onToggleMessage(message.messageId) },
                        modifier = Modifier.testTag(ReportTestTags.message(message.messageId)),
                        maxLines = 3,
                    )
                }
            }
        }
        item(key = "also-block") {
            CheckRow(
                text = stringResource(R.string.contacts_report_also_block),
                checked = state.alsoBlock,
                enabled = !state.sending,
                onCheckedChange = actions.onAlsoBlockChange,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag(ReportTestTags.ALSO_BLOCK),
            )
        }
        item(key = "send") {
            Button(
                onClick = actions.onSend,
                enabled = state.canSend,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .testTag(ReportTestTags.SEND),
            ) {
                Text(stringResource(if (state.sending) R.string.contacts_report_sending else R.string.contacts_report_send))
            }
        }
    }
}

@Composable
private fun ReportSent(state: ReportUiState, onDone: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .testTag(ReportTestTags.SENT),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.contacts_report_sent_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(text = stringResource(R.string.contacts_report_sent_body), textAlign = TextAlign.Center)
        if (state.blocked) {
            Text(text = stringResource(R.string.contacts_report_sent_blocked), textAlign = TextAlign.Center)
        }
        if (state.blockFailed) {
            Text(
                text = stringResource(R.string.contacts_report_block_failed),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = onDone, modifier = Modifier.testTag(ReportTestTags.DONE)) {
            Text(stringResource(R.string.contacts_report_done))
        }
    }
}

@Composable
private fun SectionTitle(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun CheckRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(text = text, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

private val ReportReason.label: Int
    get() = when (this) {
        ReportReason.SPAM -> R.string.contacts_report_reason_spam
        ReportReason.HARASSMENT -> R.string.contacts_report_reason_harassment
        ReportReason.INAPPROPRIATE -> R.string.contacts_report_reason_inappropriate
        ReportReason.SCAM -> R.string.contacts_report_reason_scam
        ReportReason.OTHER -> R.string.contacts_report_reason_other
    }

@GimPreviews
@Composable
private fun ReportScreenPreview() {
    GimTheme {
        ReportScreen(
            state = ReportUiState(
                name = "Lukas Berger",
                reason = ReportReason.SPAM,
                messages = persistentListOf(
                    ReportMessage("m-1", "Buy cheap boat parts at example.com", 0),
                    ReportMessage("m-2", "Last chance!", 0),
                ),
                selected = persistentSetOf("m-1"),
            ),
            actions = ReportActions(),
        )
    }
}
