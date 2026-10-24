package im.g.apps.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.designsystem.component.Avatar
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.theme.GimTheme

/** Test tags; the device E2E tests repeat these literals. */
object ProfileTestTags {
    const val NAME = "settings_profile_name"
    const val NAME_FIELD = "settings_profile_name_field"
    const val NAME_SAVE = "settings_profile_name_save"
    const val USERNAME_ADD = "settings_profile_username_add"
    const val USERNAME_CHANGE = "settings_profile_username_change"
    const val USERNAME_REMOVE = "settings_profile_username_remove"
    const val USERNAME_REMOVE_CONFIRM = "settings_profile_username_remove_confirm"
    const val USERNAME_FIELD = "settings_profile_username_field"
    const val USERNAME_CLAIM = "settings_profile_username_claim"
    const val ACCOUNT_ID = "settings_profile_account_id"
    const val COPY_ACCOUNT_ID = "settings_profile_copy_account_id"
    const val QR_CODE = "settings_profile_qr"
    const val PHOTO = "settings_profile_photo"
    const val PHOTO_CHANGE = "settings_profile_photo_change"
    const val PHOTO_REMOVE = "settings_profile_photo_remove"
    const val PHOTO_PROGRESS = "settings_profile_photo_progress"
}

@Composable
fun ProfileRoute(viewModel: ProfileViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // The system photo picker needs no storage permission.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val resolver = context.applicationContext.contentResolver
        if (uri != null) viewModel.changePhoto { resolver.openInputStream(uri) }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is ProfileEvent.Message -> event.text
                is ProfileEvent.CopyAccountId -> {
                    clipboard.setText(AnnotatedString(event.accountId))
                    R.string.profile_account_id_copied
                }
            }
            snackbarHostState.showSnackbar(context.getString(message))
        }
    }
    ProfileScreen(
        state = state,
        actions = ProfileActions(
            onBack = onBack,
            onEditName = viewModel::editName,
            onSaveName = viewModel::saveName,
            onEditUsername = viewModel::editUsername,
            onSaveUsername = viewModel::saveUsername,
            onAskRemoveUsername = viewModel::askRemoveUsername,
            onRemoveUsername = viewModel::removeUsername,
            onCancelEdit = viewModel::cancelEdit,
            onCopyAccountId = viewModel::copyAccountId,
            onChangePhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemovePhoto = viewModel::removePhoto,
        ),
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/** Callbacks of [ProfileScreen]; [onRemovePhoto] is offered only when [ProfileUiState.hasPhoto]. */
class ProfileActions(
    val onBack: () -> Unit = {},
    val onEditName: () -> Unit = {},
    val onSaveName: (String) -> Unit = {},
    val onEditUsername: () -> Unit = {},
    val onSaveUsername: (String) -> Unit = {},
    val onAskRemoveUsername: () -> Unit = {},
    val onRemoveUsername: () -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onCopyAccountId: () -> Unit = {},
    val onChangePhoto: () -> Unit = {},
    val onRemovePhoto: () -> Unit = {},
)

/** The own profile: photo, name, username, Account ID and "My QR code"; the open [ProfileUiState.editor] as a dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    actions: ProfileActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            PhotoHeader(state, actions)
            HorizontalDivider()
            SettingsSectionHeader(stringResource(R.string.profile_section_name))
            SettingsRow(
                title = state.displayName,
                onClick = actions.onEditName,
                modifier = Modifier.testTag(ProfileTestTags.NAME),
                value = stringResource(R.string.profile_name_edit),
            )
            UsernameSection(state.username, actions)
            AccountIdSection(state.accountId, actions.onCopyAccountId)
            QrSection(state.accountId)
            Spacer(Modifier.height(16.dp))
        }
    }
    when (state.editor) {
        ProfileEditor.NAME -> NameDialog(state, actions)
        ProfileEditor.USERNAME -> UsernameDialog(state, actions)
        ProfileEditor.REMOVE_USERNAME -> RemoveUsernameDialog(state.saving, actions)
        null -> Unit
    }
}

/** Own photo (the initials without one), with a spinner while a change is saved, "Change photo" and "Remove photo". */
@Composable
private fun PhotoHeader(state: ProfileUiState, actions: ProfileActions) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Avatar(
                name = state.displayName,
                imagePath = state.photoPath,
                size = 96.dp,
                modifier = Modifier.testTag(ProfileTestTags.PHOTO),
            )
            if (state.photoSaving) {
                CircularProgressIndicator(modifier = Modifier.testTag(ProfileTestTags.PHOTO_PROGRESS))
            }
        }
        Row {
            TextButton(
                onClick = actions.onChangePhoto,
                enabled = !state.photoSaving,
                modifier = Modifier.testTag(ProfileTestTags.PHOTO_CHANGE),
            ) { Text(stringResource(R.string.profile_change_photo)) }
            if (state.hasPhoto) {
                TextButton(
                    onClick = actions.onRemovePhoto,
                    enabled = !state.photoSaving,
                    modifier = Modifier.testTag(ProfileTestTags.PHOTO_REMOVE),
                ) { Text(stringResource(R.string.profile_remove_photo), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun UsernameSection(username: String?, actions: ProfileActions) {
    SettingsSectionHeader(stringResource(R.string.profile_section_username))
    if (username == null) {
        SettingsRow(
            title = stringResource(R.string.profile_username_add),
            onClick = actions.onEditUsername,
            modifier = Modifier.testTag(ProfileTestTags.USERNAME_ADD),
            value = stringResource(R.string.profile_username_why),
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_username, username),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = actions.onEditUsername, modifier = Modifier.testTag(ProfileTestTags.USERNAME_CHANGE)) {
            Text(stringResource(R.string.profile_username_change))
        }
        TextButton(onClick = actions.onAskRemoveUsername, modifier = Modifier.testTag(ProfileTestTags.USERNAME_REMOVE)) {
            Text(stringResource(R.string.profile_username_remove), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AccountIdSection(accountId: String, onCopy: () -> Unit) {
    SettingsSectionHeader(stringResource(R.string.profile_section_account_id))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = accountId,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag(ProfileTestTags.ACCOUNT_ID),
            )
        }
        TextButton(onClick = onCopy, modifier = Modifier.testTag(ProfileTestTags.COPY_ACCOUNT_ID)) {
            Text(stringResource(R.string.profile_copy))
        }
    }
}

/** "My QR code": the plain Account ID, black on white in both themes so any camera can read it. */
@Composable
private fun QrSection(accountId: String) {
    SettingsSectionHeader(stringResource(R.string.profile_section_qr))
    if (accountId.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileQrCode(
            text = accountId,
            contentDescription = stringResource(R.string.profile_qr_description),
            modifier = Modifier
                .size(220.dp)
                .background(Color.White)
                .testTag(ProfileTestTags.QR_CODE),
        )
        Text(
            text = stringResource(R.string.profile_qr_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NameDialog(state: ProfileUiState, actions: ProfileActions) {
    var name by rememberSaveable { mutableStateOf(state.displayName) }
    EditDialog(
        title = stringResource(R.string.profile_name_title),
        saving = state.saving,
        confirmTag = ProfileTestTags.NAME_SAVE,
        onConfirm = { actions.onSaveName(name) },
        onDismiss = actions.onCancelEdit,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.profile_name_label)) },
            singleLine = true,
            isError = state.nameError != null,
            supportingText = state.nameError?.let { error -> { Text(stringResource(error.message)) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.testTag(ProfileTestTags.NAME_FIELD),
        )
    }
}

@Composable
private fun UsernameDialog(state: ProfileUiState, actions: ProfileActions) {
    var username by rememberSaveable { mutableStateOf(state.username.orEmpty()) }
    EditDialog(
        title = stringResource(if (state.username == null) R.string.profile_username_add else R.string.profile_username_change_title),
        saving = state.saving,
        confirmTag = ProfileTestTags.USERNAME_CLAIM,
        onConfirm = { actions.onSaveUsername(username) },
        onDismiss = actions.onCancelEdit,
    ) {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.profile_section_username)) },
            prefix = { Text(stringResource(R.string.profile_username_prefix)) },
            singleLine = true,
            isError = state.usernameError != null,
            supportingText = {
                Text(stringResource(state.usernameError?.message ?: R.string.profile_username_rule))
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.testTag(ProfileTestTags.USERNAME_FIELD),
        )
    }
}

@Composable
private fun EditDialog(
    title: String,
    saving: Boolean,
    confirmTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = content,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !saving, modifier = Modifier.testTag(confirmTag)) {
                Text(stringResource(R.string.profile_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun RemoveUsernameDialog(saving: Boolean, actions: ProfileActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelEdit,
        title = { Text(stringResource(R.string.profile_username_remove_title)) },
        text = { Text(stringResource(R.string.profile_username_remove_body)) },
        confirmButton = {
            TextButton(
                onClick = actions.onRemoveUsername,
                enabled = !saving,
                modifier = Modifier.testTag(ProfileTestTags.USERNAME_REMOVE_CONFIRM),
            ) { Text(stringResource(R.string.profile_username_remove), fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelEdit, enabled = !saving) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@GimPreviews
@Composable
private fun ProfileScreenPreview() {
    GimTheme {
        ProfileScreen(
            state = ProfileUiState(
                displayName = "Sofia Costa",
                username = "sofia",
                accountId = "uabcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvq",
            ),
            actions = ProfileActions(),
        )
    }
}
