package im.g.apps.feature.chatlist

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.PreviewKind
import im.g.apps.core.designsystem.component.Avatar
import im.g.apps.core.designsystem.component.ConnectionBanner
import im.g.apps.core.designsystem.component.EmptyState
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.component.GimSnackbarHost
import im.g.apps.core.designsystem.component.StatusTicks
import im.g.apps.core.designsystem.component.UnreadBadge
import im.g.apps.core.designsystem.component.tickLabel
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

/** Test tags; the device E2E tests repeat these literals. */
internal object ChatListTestTags {
    const val SETTINGS = "chatlist_settings"
    const val ARCHIVED = "chatlist_archived"
    fun muted(chatId: String) = "chatlist_muted_$chatId"
    fun pinned(chatId: String) = "chatlist_pinned_$chatId"
    fun tick(chatId: String) = "chatlist_tick_$chatId"
}

/** The chat list, or with [archived] the archived chats (opened from the list's "Archived" entry). */
@Composable
fun ChatListRoute(
    viewModel: ChatListViewModel,
    ownName: String,
    onOpenChat: (chatId: String) -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    archived: Boolean = false,
    onOpenArchived: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Re-read whenever the list re-enters composition, e.g. after a photo change in Settings.
    LaunchedEffect(viewModel) { if (!archived) viewModel.refreshOwnPhoto() }
    ChatListScreen(
        state = state,
        ownName = ownName,
        onOpenChat = onOpenChat,
        onNewChat = onNewChat,
        onNewGroup = onNewGroup,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
        archived = archived,
        onOpenArchived = onOpenArchived,
        onBack = onBack,
        onAction = viewModel::onAction,
        onMute = viewModel::mute,
        onErrorShown = viewModel::errorShown,
    )
}

/**
 * [ownName] is the signed-in user's display name, drawn as the avatar that opens settings. With [archived] it shows
 * [ChatListUiState.archivedRows] under a back button instead. A long press on a row offers its
 * [ChatRowUi.actions]: [onAction], or [onMute] once a duration is picked for [ChatListAction.MUTE].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    state: ChatListUiState,
    ownName: String,
    onOpenChat: (chatId: String) -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    archived: Boolean = false,
    onOpenArchived: () -> Unit = {},
    onBack: () -> Unit = {},
    onAction: (chatId: String, action: ChatListAction) -> Unit = { _, _ -> },
    onMute: (chatId: String, option: MuteOption) -> Unit = { _, _ -> },
    onErrorShown: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val errorMessage = state.actionError?.let { stringResource(it.errorMessage) }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            // Not in this effect: clearing the error restarts it and would cancel the snackbar.
            scope.launch { snackbarHostState.showSnackbar(errorMessage) }
            onErrorShown()
        }
    }
    var mutingChatId by remember { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            if (archived) {
                TopAppBar(
                    title = { Text(stringResource(R.string.chatlist_archived_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.chatlist_back))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.chatlist_title)) },
                    navigationIcon = {
                        val settingsLabel = stringResource(R.string.chatlist_open_settings)
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier
                                .testTag(ChatListTestTags.SETTINGS)
                                .semantics { contentDescription = settingsLabel },
                        ) {
                            // The button's label replaces the initials for screen readers.
                            Avatar(
                                name = ownName,
                                imagePath = state.ownPhotoPath,
                                size = 34.dp,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = { if (!archived) NewChatFab(onNewChat = onNewChat, onNewGroup = onNewGroup) },
        snackbarHost = { GimSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.connection?.let { ConnectionBanner(text = stringResource(it.labelRes())) }
            val rows = if (archived) state.archivedRows else state.rows
            when {
                !archived && state.isEmpty -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = stringResource(R.string.chatlist_empty_title),
                        body = stringResource(R.string.chatlist_empty_body),
                        action = {
                            Button(onClick = onNewChat, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.chatlist_start_chat))
                            }
                        },
                    )
                }
                archived && rows.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.chatlist_archived_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }, contentType = { "chat" }) { row ->
                        ChatRow(
                            row = row,
                            onClick = { onOpenChat(row.id) },
                            onAction = { action ->
                                if (action == ChatListAction.MUTE) mutingChatId = row.id else onAction(row.id, action)
                            },
                        )
                    }
                    if (!archived && state.archivedCount > 0) {
                        item(key = ChatListTestTags.ARCHIVED, contentType = "archived") {
                            ArchivedEntry(count = state.archivedCount, onClick = onOpenArchived)
                        }
                    }
                }
            }
        }
    }
    mutingChatId?.let { chatId ->
        MuteDialog(
            onMute = { option ->
                mutingChatId = null
                onMute(chatId, option)
            },
            onDismiss = { mutingChatId = null },
        )
    }
}

@Composable
private fun ArchivedEntry(count: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .testTag(ChatListTestTags.ARCHIVED)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = stringResource(R.string.chatlist_archived_entry, count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MuteDialog(onMute: (MuteOption) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chatlist_mute_title)) },
        text = {
            Column {
                Text(stringResource(R.string.chatlist_mute_body))
                MuteOption.entries.forEach { option ->
                    TextButton(onClick = { onMute(option) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(option.label))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chatlist_cancel)) } },
    )
}

@Composable
private fun NewChatFab(onNewChat: () -> Unit, onNewGroup: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FloatingActionButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chatlist_new_message))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chatlist_new_chat)) },
                onClick = {
                    expanded = false
                    onNewChat()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chatlist_new_group)) },
                onClick = {
                    expanded = false
                    onNewGroup()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(row: ChatRowUi, onClick: () -> Unit, onAction: (ChatListAction) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val preview = previewText(row)
    val time = timeText(row.time)
    val unread = if (row.unreadCount > 0) {
        pluralStringResource(R.plurals.chatlist_unread_messages, row.unreadCount, row.unreadCount)
    } else {
        null
    }
    val tick = row.tick?.let { tickLabel(it) }
    // The muted and pinned marks add their own labels after this one.
    val description = listOfNotNull(row.title, unread, preview, tick, time)
        .joinToString(stringResource(R.string.chatlist_row_description_separator))
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .combinedClickable(
                    onClickLabel = stringResource(R.string.chatlist_open_chat),
                    onLongClickLabel = stringResource(R.string.chatlist_chat_actions),
                    onLongClick = { menuOpen = true },
                    onClick = onClick,
                )
                .semantics { contentDescription = description }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(name = row.title, imagePath = row.avatarPath, size = 56.dp, colorKey = row.id)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (row.muted) {
                            RowMark(R.drawable.ic_chatlist_muted, R.string.chatlist_muted, ChatListTestTags.muted(row.id))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // Not weighted, so they are measured first and keep their full width; the title ellipsizes instead.
                    if (row.pinned) {
                        RowMark(R.drawable.ic_chatlist_pinned, R.string.chatlist_pinned, ChatListTestTags.pinned(row.id))
                    }
                    row.tick?.let {
                        StatusTicks(
                            state = it,
                            size = 16.dp,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .testTag(ChatListTestTags.tick(row.id)),
                        )
                    }
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (row.unreadCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (row.unreadCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        // The row description already says the count.
                        UnreadBadge(count = row.unreadCount, describe = false)
                    }
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            row.actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.label)) },
                    onClick = {
                        menuOpen = false
                        onAction(action)
                    },
                )
            }
        }
    }
}

/** A small mark next to the title or time, labelled for screen readers. */
@Composable
private fun RowMark(@DrawableRes icon: Int, @StringRes label: Int, tag: String) {
    Icon(
        painter = painterResource(icon),
        contentDescription = stringResource(label),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(16.dp)
            .testTag(tag),
    )
}

@Composable
private fun timeText(label: ChatTimeLabel): String = when (label) {
    ChatTimeLabel.JustNow -> stringResource(R.string.chatlist_time_now)
    is ChatTimeLabel.Minutes -> pluralStringResource(R.plurals.chatlist_time_minutes, label.minutes, label.minutes)
    is ChatTimeLabel.Clock -> label.text
    ChatTimeLabel.Yesterday -> stringResource(R.string.chatlist_time_yesterday)
    is ChatTimeLabel.Weekday -> label.text
    is ChatTimeLabel.Date -> label.text
}

@Composable
private fun previewText(row: ChatRowUi): String = when (row.state) {
    ChatState.LEAVING -> stringResource(R.string.chatlist_state_leaving)
    ChatState.LEFT -> stringResource(R.string.chatlist_state_left)
    ChatState.ACTIVE -> {
        val message = when (val kind = row.previewKind) {
            PreviewKind.Text -> row.preview
            PreviewKind.Photo -> stringResource(R.string.chatlist_preview_photo)
            is PreviewKind.File -> kind.fileName?.let { stringResource(R.string.chatlist_preview_file, it) }
                ?: stringResource(R.string.chatlist_preview_file_unnamed)
            PreviewKind.Deleted -> stringResource(R.string.chatlist_preview_deleted)
        }
        when (val sender = row.sender) {
            null -> message
            PreviewSender.You -> stringResource(R.string.chatlist_preview_you, message)
            is PreviewSender.Member -> stringResource(R.string.chatlist_preview_member, sender.firstName, message)
        }
    }
}

private fun ConnectionBannerUi.labelRes(): Int = when (this) {
    ConnectionBannerUi.WAITING_FOR_NETWORK -> R.string.chatlist_banner_waiting_for_network
    ConnectionBannerUi.CONNECTING -> R.string.chatlist_banner_connecting
    ConnectionBannerUi.UPDATING -> R.string.chatlist_banner_updating
}

@GimPreviews
@Composable
private fun ChatListScreenPreview() {
    GimTheme {
        ChatListScreen(
            state = ChatListUiState(
                rows = persistentListOf(
                    ChatRowUi("g", "Aegean Crew", null, PreviewSender.Member("Nikos"), "Table booked", ChatTimeLabel.Minutes(2), 3),
                    ChatRowUi("d", "Lukas Berger", null, PreviewSender.You, "See you then!", ChatTimeLabel.Weekday("Fri"), 0),
                ),
                connection = ConnectionBannerUi.CONNECTING,
            ),
            ownName = "Sofia Costa",
            onOpenChat = {},
            onNewChat = {},
            onNewGroup = {},
            onOpenSettings = {},
        )
    }
}
