package im.g.apps.feature.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.collections.immutable.persistentListOf

@Composable
fun NewGroupRoute(
    viewModel: NewGroupViewModel,
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
                is NewGroupEvent.OpenChat -> currentOnOpenChat(event.chatId)
                is NewGroupEvent.ShowError -> snackbarHostState.showSnackbar(context.getString(event.error.messageRes))
            }
        }
    }
    NewGroupScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onQueryChange = viewModel::onQueryChange,
        onAddMember = viewModel::addMember,
        onRemoveMember = viewModel::removeMember,
        onNext = viewModel::next,
        onNameChange = viewModel::onNameChange,
        onCreate = viewModel::create,
        onBack = { if (!viewModel.back()) onBack() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewGroupScreen(
    state: NewGroupUiState,
    onQueryChange: (String) -> Unit,
    onAddMember: (UserProfile) -> Unit,
    onRemoveMember: (userId: String) -> Unit,
    onNext: () -> Unit,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when (state.step) {
                        NewGroupStep.MEMBERS -> R.string.contacts_new_group_members_title
                        NewGroupStep.NAME -> R.string.contacts_new_group_name_title
                    }
                    Text(stringResource(title))
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.step) {
                NewGroupStep.MEMBERS -> MembersStep(state, onQueryChange, onAddMember, onRemoveMember, onNext)
                NewGroupStep.NAME -> NameStep(state, onNameChange, onCreate)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembersStep(
    state: NewGroupUiState,
    onQueryChange: (String) -> Unit,
    onAddMember: (UserProfile) -> Unit,
    onRemoveMember: (userId: String) -> Unit,
    onNext: () -> Unit,
) {
    UserSearchField(query = state.query, onQueryChange = onQueryChange, state = state.search) { user ->
        val selected = state.members.any { it.userId == user.userId }
        Button(onClick = { onAddMember(user) }, enabled = state.canAdd(user)) {
            Text(stringResource(if (selected) R.string.contacts_added else R.string.contacts_add))
        }
    }
    if (state.members.isEmpty()) {
        Text(
            text = stringResource(R.string.contacts_new_group_no_members),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.members.forEach { member ->
                val description = stringResource(R.string.contacts_remove_selected, member.displayName)
                InputChip(
                    selected = false,
                    onClick = { onRemoveMember(member.userId) },
                    label = { Text(member.displayName) },
                    trailingIcon = { CrossGlyph(LocalContentColor.current) },
                    modifier = Modifier.semantics { contentDescription = description },
                )
            }
        }
    }
    Button(onClick = onNext, enabled = state.canProceed, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.contacts_next))
    }
}

@Composable
private fun NameStep(
    state: NewGroupUiState,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    OutlinedTextField(
        value = state.name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.contacts_group_name_label)) },
        supportingText = {
            Text(stringResource(R.string.contacts_group_name_counter, state.name.length, MAX_GROUP_NAME_LENGTH))
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = pluralStringResource(R.plurals.contacts_member_count, state.members.size + 1, state.members.size + 1),
        style = MaterialTheme.typography.titleSmall,
    )
    state.members.forEach { UserRow(user = it) }
    Button(onClick = onCreate, enabled = state.canCreate, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.contacts_create_group))
    }
}

@Preview
@Composable
private fun NewGroupScreenPreview() {
    GimTheme {
        NewGroupScreen(
            state = NewGroupUiState(
                query = "nikos",
                search = UserSearchState.Found(UserProfile("u-nikos", "nikos", "Nikos Papadakis")),
                members = persistentListOf(UserProfile("u-ana", "ana", "Ana Kovač")),
            ),
            onQueryChange = {},
            onAddMember = {},
            onRemoveMember = {},
            onNext = {},
            onNameChange = {},
            onCreate = {},
            onBack = {},
        )
    }
}
