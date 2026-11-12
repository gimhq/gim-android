package im.g.apps.feature.contacts

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.bridge.ChatKind
import im.g.apps.core.bridge.ChatSummary
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.displayLabel
import im.g.apps.core.bridge.displayNameOrShortId
import im.g.apps.core.designsystem.component.Avatar
import im.g.apps.core.designsystem.component.EmptyState
import im.g.apps.core.designsystem.component.GimConfirmDialog
import im.g.apps.core.designsystem.component.NoteToSelfAvatar
import im.g.apps.core.designsystem.theme.GimTheme
import im.g.apps.core.designsystem.R as DesignR
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the group info screen. */
object GroupInfoTestTags {
    /** Your own row in the member list; tapping it opens the note to self. */
    const val SELF = "group_info_self"
}

/** Test tags of the "Chat actions" group and its dialogs. */
object ChatActionsTestTags {
    const val GROUP = "chat_actions"
    const val BLOCK_CONFIRM = "chat_actions_block_confirm"

    fun action(action: ChatAction) = "chat_action_${action.name.lowercase()}"

    fun muteOption(option: MuteOption) = "chat_actions_mute_${option.name.lowercase()}"
}

/** [onReport] opens the report about a user (the peer) with messages from a chat. */
@Composable
fun GroupInfoRoute(
    viewModel: GroupInfoViewModel,
    onBack: () -> Unit,
    onLeftChat: () -> Unit = onBack,
    onReport: (userId: String, chatId: String) -> Unit = { _, _ -> },
    onOpenChat: (chatId: String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentOnLeftChat by rememberUpdatedState(onLeftChat)
    val currentOnReport by rememberUpdatedState(onReport)
    val currentOnOpenChat by rememberUpdatedState(onOpenChat)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                GroupInfoEvent.LeftChat -> currentOnLeftChat()
                is GroupInfoEvent.OpenReport -> currentOnReport(event.userId, event.chatId)
                is GroupInfoEvent.OpenChat -> currentOnOpenChat(event.chatId)
                is GroupInfoEvent.ShowError -> snackbarHostState.showSnackbar(event.error.message(context))
            }
        }
    }
    GroupInfoScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        actions = GroupInfoActions(
            onBack = onBack,
            onRename = viewModel::startRename,
            onRenameChange = viewModel::onRenameChange,
            onConfirmRename = viewModel::confirmRename,
            onAddMember = viewModel::openAddMember,
            onAddMemberQueryChange = viewModel::onAddMemberQueryChange,
            onConfirmAddMember = viewModel::addMember,
            onRemoveMember = viewModel::requestRemove,
            onConfirmRemove = viewModel::confirmRemove,
            onLeave = viewModel::requestLeave,
            onConfirmLeave = viewModel::confirmLeave,
            onDismissDialog = viewModel::dismissDialog,
            onChatAction = viewModel::onChatAction,
            onMute = viewModel::mute,
            onConfirmBlock = viewModel::confirmBlock,
            onOpenNoteToSelf = viewModel::openNoteToSelf,
        ),
    )
}

/** Callbacks of [GroupInfoScreen], bundled to keep the signature readable. */
@Immutable
internal class GroupInfoActions(
    val onBack: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onRenameChange: (String) -> Unit = {},
    val onConfirmRename: () -> Unit = {},
    val onAddMember: () -> Unit = {},
    val onAddMemberQueryChange: (String) -> Unit = {},
    val onConfirmAddMember: (UserProfile) -> Unit = {},
    val onRemoveMember: (UserProfile) -> Unit = {},
    val onConfirmRemove: () -> Unit = {},
    val onLeave: () -> Unit = {},
    val onConfirmLeave: () -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    val onChatAction: (ChatAction) -> Unit = {},
    val onMute: (MuteOption) -> Unit = {},
    val onConfirmBlock: () -> Unit = {},
    val onOpenNoteToSelf: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupInfoScreen(
    state: GroupInfoUiState,
    actions: GroupInfoActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val chat = state.chat
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = if (chat?.kind == ChatKind.DIRECT) {
                        R.string.contacts_contact_info_title
                    } else {
                        R.string.contacts_group_info_title
                    }
                    Text(stringResource(title))
                },
                navigationIcon = { BackButton(actions.onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val modifier = Modifier
            .padding(padding)
            .fillMaxSize()
        when {
            chat == null && state.missing -> EmptyState(
                title = stringResource(R.string.contacts_chat_missing),
                body = "",
                modifier = modifier,
            )
            chat == null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> ChatDetails(chat, state, actions, modifier)
        }
    }
    GroupInfoDialogs(state, actions)
}

@Composable
private fun ChatDetails(
    chat: ChatSummary,
    state: GroupInfoUiState,
    actions: GroupInfoActions,
    modifier: Modifier,
) {
    val isGroup = chat.kind == ChatKind.GROUP
    LazyColumn(modifier = modifier.padding(horizontal = 16.dp)) {
        item(key = "header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val peer = if (isGroup || chat.isNoteToSelf) null else state.peer
                val name = peer?.displayLabel ?: displayNameOrShortId(chat.name)
                val title = if (chat.isNoteToSelf) stringResource(DesignR.string.gim_note_to_self) else name
                if (chat.isNoteToSelf) {
                    NoteToSelfAvatar(name = name, imagePath = state.headerPhotoPath, size = 72.dp)
                } else {
                    Avatar(name = title, imagePath = state.headerPhotoPath, size = 72.dp)
                }
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                val subtitle = when {
                    chat.isNoteToSelf -> stringResource(DesignR.string.gim_note_to_self_hint)
                    peer != null -> userSubtitle(peer)
                    isGroup -> {
                        val count = if (state.membersUnavailable) chat.memberCount else state.members.size
                        pluralStringResource(R.plurals.contacts_member_count, count, count)
                    }
                    else -> null
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (isGroup) {
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = actions.onRename, enabled = !state.busy) {
                        Text(stringResource(R.string.contacts_rename_group))
                    }
                    TextButton(onClick = actions.onAddMember, enabled = !state.busy) {
                        Text(stringResource(R.string.contacts_add_member))
                    }
                }
            }
        }
        // Groups list them after the members.
        if (!isGroup) {
            if (state.chatActions.isNotEmpty()) {
                item(key = "chat-actions") { ChatActionsGroup(chat, state, onAction = actions.onChatAction) }
            }
            return@LazyColumn
        }
        item(key = "members-title") {
            Text(
                text = stringResource(R.string.contacts_members),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
        }
        if (state.membersUnavailable) {
            item(key = "members-unavailable") {
                Text(
                    text = stringResource(R.string.contacts_members_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.members, key = { it.user.userId }) { member ->
            UserRow(
                user = member.user,
                photoPath = state.photos[member.user.userId],
                isSelf = member.isSelf,
                modifier = if (member.isSelf) {
                    Modifier
                        .clickable(onClickLabel = stringResource(DesignR.string.gim_note_to_self), onClick = actions.onOpenNoteToSelf)
                        .testTag(GroupInfoTestTags.SELF)
                } else {
                    Modifier
                },
            ) {
                if (!member.isSelf) {
                    RemoveButton(
                        description = stringResource(R.string.contacts_remove_member_description, member.user.displayLabel),
                        onClick = { actions.onRemoveMember(member.user) },
                        enabled = !state.busy,
                    )
                }
            }
        }
        if (state.chatActions.isNotEmpty()) {
            item(key = "chat-actions") { ChatActionsGroup(chat, state, onAction = actions.onChatAction) }
        }
        item(key = "leave") {
            TextButton(
                onClick = actions.onLeave,
                enabled = !state.busy && !state.leaving && !state.left,
                modifier = Modifier.padding(vertical = 16.dp),
            ) {
                if (state.leaving) {
                    Text(stringResource(R.string.contacts_leaving))
                } else {
                    Text(stringResource(R.string.contacts_leave_group), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ChatActionsGroup(chat: ChatSummary, state: GroupInfoUiState, onAction: (ChatAction) -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.testTag(ChatActionsTestTags.GROUP)) {
        Text(
            text = stringResource(R.string.contacts_chat_actions),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        state.chatActions.forEach { action ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onAction(action) },
                    enabled = !state.busy,
                    modifier = Modifier.testTag(ChatActionsTestTags.action(action)),
                ) {
                    val color = if (action == ChatAction.BLOCK || action == ChatAction.REPORT) {
                        MaterialTheme.colorScheme.error
                    } else {
                        Color.Unspecified
                    }
                    Text(stringResource(action.label), color = color)
                }
                if (action == ChatAction.UNMUTE) {
                    val until = if (state.mutedForever) {
                        stringResource(R.string.contacts_muted_forever)
                    } else {
                        val time = DateUtils.formatDateTime(
                            context,
                            chat.mutedUntilMs,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                        )
                        stringResource(R.string.contacts_muted_until, time)
                    }
                    Text(
                        text = until,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupInfoDialogs(state: GroupInfoUiState, actions: GroupInfoActions) {
    state.addMemberDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = actions.onDismissDialog,
            title = { Text(stringResource(R.string.contacts_add_member)) },
            text = {
                UserSearchField(
                    query = dialog.query,
                    onQueryChange = actions.onAddMemberQueryChange,
                    state = dialog.search,
                    ) { user ->
                    Button(onClick = { actions.onConfirmAddMember(user) }, enabled = state.canAdd(user)) {
                        Text(stringResource(if (state.canAdd(user)) R.string.contacts_add else R.string.contacts_added))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = actions.onDismissDialog) { Text(stringResource(R.string.contacts_cancel)) } },
        )
    }
    state.pendingRemoval?.let { user ->
        ConfirmDialog(
            text = stringResource(R.string.contacts_remove_member_confirm, user.displayLabel),
            confirmLabel = stringResource(R.string.contacts_remove),
            onConfirm = actions.onConfirmRemove,
            onDismiss = actions.onDismissDialog,
        )
    }
    if (state.confirmingLeave) {
        ConfirmDialog(
            text = stringResource(R.string.contacts_leave_group_confirm),
            confirmLabel = stringResource(R.string.contacts_leave),
            onConfirm = actions.onConfirmLeave,
            onDismiss = actions.onDismissDialog,
        )
    }
    if (state.choosingMute) {
        AlertDialog(
            onDismissRequest = actions.onDismissDialog,
            title = { Text(stringResource(R.string.contacts_mute_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.contacts_mute_body))
                    MuteOption.entries.forEach { option ->
                        TextButton(
                            onClick = { actions.onMute(option) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(ChatActionsTestTags.muteOption(option)),
                        ) { Text(stringResource(option.label)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = actions.onDismissDialog) { Text(stringResource(R.string.contacts_cancel)) } },
        )
    }
    val peer = state.chat?.peer
    if (state.confirmingBlock && peer != null) {
        val blocked = state.chat?.blocked == true
        GimConfirmDialog(
            title = stringResource(
                if (blocked) R.string.contacts_unblock_title else R.string.contacts_block_title,
                peer.displayLabel,
            ),
            body = stringResource(if (blocked) R.string.contacts_unblock_body else R.string.contacts_block_body),
            confirmLabel = stringResource(
                if (blocked) R.string.contacts_chat_action_unblock else R.string.contacts_chat_action_block,
            ),
            dismissLabel = stringResource(R.string.contacts_cancel),
            onConfirm = actions.onConfirmBlock,
            onDismiss = actions.onDismissDialog,
            modifier = Modifier.testTag(ChatActionsTestTags.BLOCK_CONFIRM),
            destructive = !blocked,
        )
    }
    state.renameDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = actions.onDismissDialog,
            title = { Text(stringResource(R.string.contacts_rename_group)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = actions.onRenameChange,
                    label = { Text(stringResource(R.string.contacts_group_name_label)) },
                    supportingText = {
                        Text(stringResource(R.string.contacts_group_name_counter, draft.length, MAX_GROUP_NAME_LENGTH))
                    },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = actions.onConfirmRename, enabled = state.canRename) {
                    Text(stringResource(R.string.contacts_save))
                }
            },
            dismissButton = { TextButton(onClick = actions.onDismissDialog) { Text(stringResource(R.string.contacts_cancel)) } },
        )
    }
}

@Composable
private fun ConfirmDialog(text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.contacts_cancel)) } },
    )
}

@Preview
@Composable
private fun GroupInfoScreenPreview() {
    val me = UserProfile("u-sofia", "Sofia Costa")
    val ana = UserProfile("u-ana", "Ana Kovač")
    GimTheme {
        GroupInfoScreen(
            state = GroupInfoUiState(
                chat = ChatSummary("chat-aegean", ChatKind.GROUP, "Aegean Crew", null, null, 0, 0, 2),
                members = persistentListOf(GroupMember(me, isSelf = true), GroupMember(ana, isSelf = false)),
            ),
            actions = GroupInfoActions(),
        )
    }
}
