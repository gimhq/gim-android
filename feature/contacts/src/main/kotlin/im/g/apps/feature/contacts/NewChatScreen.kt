package im.g.apps.feature.contacts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.designsystem.theme.GimTheme

@Composable
fun NewChatRoute(
    viewModel: NewChatViewModel,
    onOpenChat: (chatId: String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentOnOpenChat by rememberUpdatedState(onOpenChat)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is NewChatEvent.OpenChat -> currentOnOpenChat(event.chatId)
                is NewChatEvent.ShowError -> snackbarHostState.showSnackbar(event.error.message(context))
            }
        }
    }
    NewChatScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onQueryChange = viewModel::onQueryChange,
        onStartChat = viewModel::startChat,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewChatScreen(
    state: NewChatUiState,
    onQueryChange: (String) -> Unit,
    onStartChat: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_new_chat_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        UserSearchField(
            query = state.query,
            onQueryChange = onQueryChange,
            state = state.search,
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth(),
        ) {
            Button(onClick = onStartChat, enabled = state.canStartChat) {
                Text(stringResource(R.string.contacts_start_chat))
            }
        }
    }
}

@Preview
@Composable
private fun NewChatScreenPreview() {
    GimTheme {
        NewChatScreen(
            state = NewChatUiState(
                query = "lukas",
                search = UserSearchState.Found(UserProfile("u-lukas", "lukas", "Lukas Berger")),
            ),
            onQueryChange = {},
            onStartChat = {},
            onBack = {},
        )
    }
}
