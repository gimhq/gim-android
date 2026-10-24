package im.g.apps.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.designsystem.component.Avatar
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.theme.GimTheme

/** Test tags; the device E2E tests repeat these literals. */
internal object SettingsTestTags {
    const val HEADER = "settings_header"
    const val RECOVERY_KEY = "settings_recovery_key"
    const val NOTIFICATIONS = "settings_notifications"
    const val APPEARANCE = "settings_appearance"
    const val LANGUAGE = "settings_language"
    const val HELP = "settings_help"
    const val READ_RECEIPTS = "settings_read_receipts"
    const val TYPING_INDICATOR = "settings_typing_indicator"
    const val BLOCKED_PEOPLE = "settings_blocked_people"
    const val NEW_RECOVERY_KEY = "settings_new_recovery_key"
    const val DELETE_ACCOUNT = "settings_delete_account"
    const val SIGN_OUT = "settings_sign_out"
    const val SIGN_OUT_CONFIRM = "settings_sign_out_confirm"
}

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    languageTag: String? = null,
) {
    // Picks up a name changed in a sub-screen when returning here.
    LifecycleStartEffect(viewModel) {
        viewModel.refresh()
        onStopOrDispose {}
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(context.getString(it)) }
    }
    SettingsScreen(
        state = state,
        actions = actions,
        modifier = modifier,
        themeMode = themeMode,
        languageTag = languageTag,
        snackbarHostState = snackbarHostState,
    )
}

/** Callbacks of [SettingsScreen]; the header is clickable only when [onOpenProfile] is set. */
class SettingsActions(
    val onBack: () -> Unit = {},
    val onOpenProfile: (() -> Unit)? = null,
    val onRecoveryKey: () -> Unit = {},
    val onDevices: () -> Unit = {},
    val onLinkDevice: () -> Unit = {},
    val onNewRecoveryKey: () -> Unit = {},
    val onDeleteAccount: () -> Unit = {},
    val onNotifications: () -> Unit = {},
    val onReadReceiptsChange: (Boolean) -> Unit = {},
    val onTypingIndicatorChange: (Boolean) -> Unit = {},
    val onBlockedPeople: () -> Unit = {},
    val onServers: () -> Unit = {},
    val onAppearance: () -> Unit = {},
    val onLanguage: () -> Unit = {},
    val onHelp: () -> Unit = {},
    val onSignOut: () -> Unit = {},
)

/**
 * Settings home: the own profile header, one composable per section, and sign out at the bottom. [themeMode] and
 * [languageTag] (null: phone language) are shown as the values of the "Look and language" rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    languageTag: String? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            ProfileHeader(state, actions.onOpenProfile)
            HorizontalDivider()
            AccountSection(actions)
            NotificationsSection(actions)
            PrivacySection(actions, state)
            LookAndLanguageSection(actions, themeMode, languageTag)
            AdvancedSection(actions)
            HelpSection(actions)
            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            SignOutRow(actions.onSignOut)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProfileHeader(state: SettingsUiState, onOpenProfile: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.HEADER)
            .then(if (onOpenProfile != null) Modifier.clickable(onClick = onOpenProfile) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = state.displayName, imagePath = null, size = 64.dp)
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = state.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            state.username?.let {
                Text(
                    text = stringResource(R.string.settings_username, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AccountSection(actions: SettingsActions) {
    SettingsSectionHeader(stringResource(R.string.settings_section_account))
    SettingsRow(
        title = stringResource(R.string.settings_recovery_key),
        onClick = actions.onRecoveryKey,
        modifier = Modifier.testTag(SettingsTestTags.RECOVERY_KEY),
    )
    SettingsRow(title = stringResource(R.string.settings_linked_devices), onClick = actions.onDevices)
    SettingsRow(title = stringResource(R.string.settings_link_device), onClick = actions.onLinkDevice)
    SettingsRow(
        title = stringResource(R.string.settings_new_recovery_key),
        onClick = actions.onNewRecoveryKey,
        modifier = Modifier.testTag(SettingsTestTags.NEW_RECOVERY_KEY),
    )
    SettingsRow(
        title = stringResource(R.string.settings_delete_account),
        onClick = actions.onDeleteAccount,
        modifier = Modifier.testTag(SettingsTestTags.DELETE_ACCOUNT),
        titleColor = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun NotificationsSection(actions: SettingsActions) {
    SettingsSectionHeader(stringResource(R.string.settings_section_notifications))
    SettingsRow(
        title = stringResource(R.string.settings_notifications),
        onClick = actions.onNotifications,
        modifier = Modifier.testTag(SettingsTestTags.NOTIFICATIONS),
        value = stringResource(R.string.settings_notifications_value),
    )
}

/** Read receipts, typing indicator (synced to the user's other devices) and the blocked people list. */
@Composable
private fun PrivacySection(actions: SettingsActions, state: SettingsUiState) {
    SettingsSectionHeader(stringResource(R.string.settings_section_privacy))
    SettingsSwitchRow(
        title = stringResource(R.string.settings_read_receipts),
        checked = state.readReceipts,
        onCheckedChange = actions.onReadReceiptsChange,
        modifier = Modifier.testTag(SettingsTestTags.READ_RECEIPTS),
        value = stringResource(R.string.settings_read_receipts_value),
    )
    SettingsSwitchRow(
        title = stringResource(R.string.settings_typing_indicator),
        checked = state.typingIndicator,
        onCheckedChange = actions.onTypingIndicatorChange,
        modifier = Modifier.testTag(SettingsTestTags.TYPING_INDICATOR),
        value = stringResource(R.string.settings_typing_indicator_value),
    )
    Text(
        text = stringResource(R.string.settings_privacy_synced),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    SettingsRow(
        title = stringResource(R.string.settings_blocked_people),
        onClick = actions.onBlockedPeople,
        modifier = Modifier.testTag(SettingsTestTags.BLOCKED_PEOPLE),
    )
}

@Composable
private fun LookAndLanguageSection(actions: SettingsActions, themeMode: ThemeMode, languageTag: String?) {
    SettingsSectionHeader(stringResource(R.string.settings_section_look))
    SettingsRow(
        title = stringResource(R.string.settings_appearance),
        onClick = actions.onAppearance,
        modifier = Modifier.testTag(SettingsTestTags.APPEARANCE),
        value = stringResource(themeMode.label()),
    )
    SettingsRow(
        title = stringResource(R.string.settings_language),
        onClick = actions.onLanguage,
        modifier = Modifier.testTag(SettingsTestTags.LANGUAGE),
        value = languageTag?.let(::languageDisplayName) ?: stringResource(R.string.language_phone),
    )
}

@Composable
private fun AdvancedSection(actions: SettingsActions) {
    SettingsSectionHeader(stringResource(R.string.settings_section_advanced))
    SettingsRow(title = stringResource(R.string.settings_servers), onClick = actions.onServers)
}

@Composable
private fun HelpSection(actions: SettingsActions) {
    SettingsSectionHeader(stringResource(R.string.settings_section_help))
    SettingsRow(
        title = stringResource(R.string.settings_help),
        onClick = actions.onHelp,
        modifier = Modifier.testTag(SettingsTestTags.HELP),
    )
}

/** Sign out behind a confirmation; sign-out leaves this screen when done, until then the dialog stays up disabled. */
@Composable
private fun SignOutRow(onSignOut: () -> Unit) {
    var confirmSignOut by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    SettingsRow(
        title = stringResource(R.string.settings_sign_out),
        onClick = { confirmSignOut = true },
        modifier = Modifier.testTag(SettingsTestTags.SIGN_OUT),
        titleColor = MaterialTheme.colorScheme.error,
    )
    if (confirmSignOut) {
        SignOutDialog(
            enabled = !signingOut,
            onConfirm = {
                signingOut = true
                onSignOut()
            },
            onDismiss = { if (!signingOut) confirmSignOut = false },
        )
    }
}

@Composable
private fun SignOutDialog(enabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sign_out_title)) },
        text = { Text(stringResource(R.string.settings_sign_out_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = enabled,
                modifier = Modifier.testTag(SettingsTestTags.SIGN_OUT_CONFIRM),
            ) { Text(stringResource(R.string.settings_sign_out)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@GimPreviews
@Composable
private fun SettingsScreenPreview() {
    GimTheme {
        SettingsScreen(
            state = SettingsUiState(displayName = "Sofia Costa", username = "sofia", accountId = "u-7f3a9c21"),
            actions = SettingsActions(),
        )
    }
}
