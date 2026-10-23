package im.g.apps.feature.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.bridge.ChatState
import im.g.apps.core.designsystem.component.Avatar
import im.g.apps.core.designsystem.component.ConnectionBanner
import im.g.apps.core.designsystem.component.EmptyState
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.component.UnreadBadge
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.collections.immutable.persistentListOf

/** Test tags; the device E2E tests repeat these literals. */
internal object ChatListTestTags {
    const val SETTINGS = "chatlist_settings"
}

@Composable
fun ChatListRoute(
    viewModel: ChatListViewModel,
    ownName: String,
    onOpenChat: (chatId: String) -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ChatListScreen(
        state = state,
        ownName = ownName,
        onOpenChat = onOpenChat,
        onNewChat = onNewChat,
        onNewGroup = onNewGroup,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

/** [ownName] is the signed-in user's display name, drawn as the avatar that opens settings. */
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
) {
    Scaffold(
        modifier = modifier,
        topBar = {
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
                        Avatar(name = ownName, imagePath = null, size = 34.dp, modifier = Modifier.clearAndSetSemantics {})
                    }
                },
            )
        },
        floatingActionButton = { NewChatFab(onNewChat = onNewChat, onNewGroup = onNewGroup) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.connection?.let { ConnectionBanner(text = stringResource(it.labelRes())) }
            if (state.isEmpty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = stringResource(R.string.chatlist_empty_title),
                        body = stringResource(R.string.chatlist_empty_body),
                        action = {
                            TextButton(onClick = onNewChat) { Text(stringResource(R.string.chatlist_start_chat)) }
                        },
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.id }, contentType = { "chat" }) { row ->
                        ChatRow(row = row, onClick = { onOpenChat(row.id) })
                    }
                }
            }
        }
    }
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

@Composable
private fun ChatRow(row: ChatRowUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = row.title, imagePath = row.avatarPath, size = 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = row.time,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = previewText(row),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (row.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    UnreadBadge(count = row.unreadCount)
                }
            }
        }
    }
}

@Composable
private fun previewText(row: ChatRowUi): String = when (row.state) {
    ChatState.LEAVING -> stringResource(R.string.chatlist_state_leaving)
    ChatState.LEFT -> stringResource(R.string.chatlist_state_left)
    ChatState.ACTIVE -> when (val sender = row.sender) {
        null -> row.preview
        PreviewSender.You -> stringResource(R.string.chatlist_preview_you, row.preview)
        is PreviewSender.Member -> stringResource(R.string.chatlist_preview_member, sender.firstName, row.preview)
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
                    ChatRowUi("g", "Aegean Crew", null, PreviewSender.Member("Nikos"), "Table booked", "10:02", 3),
                    ChatRowUi("d", "Lukas Berger", null, PreviewSender.You, "See you then!", "Fri", 0),
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
