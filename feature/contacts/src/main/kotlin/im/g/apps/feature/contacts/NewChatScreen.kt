package im.g.apps.feature.contacts

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.designsystem.theme.GimTheme

object NewChatTestTags {
    const val SCAN_QR = "contacts_scan_qr"
    const val SCANNER = "contacts_scanner"
    const val CLOSE_SCANNER = "contacts_close_scanner"
}

/**
 * Hosts [NewChatScreen] with the camera permission flow. [scanner] draws a camera preview that reports decoded QR
 * codes to `onQrCode` and calls `onUnavailable` when no camera can be used; it is only shown with the CAMERA
 * permission granted.
 */
@Composable
fun NewChatRoute(
    viewModel: NewChatViewModel,
    onOpenChat: (chatId: String) -> Unit,
    onBack: () -> Unit,
    scanner: @Composable (onQrCode: (String) -> Unit, onUnavailable: () -> Unit, modifier: Modifier) -> Unit,
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
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.openScanner() else viewModel.onCameraDenied()
    }
    NewChatScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onQueryChange = viewModel::onQueryChange,
        onStartChat = viewModel::startChat,
        onBack = onBack,
        onScanQr = {
            val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            if (permission == PackageManager.PERMISSION_GRANTED) {
                viewModel.openScanner()
            } else {
                launcher.launch(Manifest.permission.CAMERA)
            }
        },
        onCloseScanner = viewModel::closeScanner,
        scanner = { modifier -> scanner(viewModel::onQrScanned, viewModel::onCameraUnavailable, modifier) },
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
    onScanQr: () -> Unit = {},
    onCloseScanner: () -> Unit = {},
    scanner: @Composable (Modifier) -> Unit = {},
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
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            UserSearchField(
                query = state.query,
                onQueryChange = onQueryChange,
                state = state.search,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(onClick = onStartChat, enabled = state.canStartChat) {
                    Text(stringResource(R.string.contacts_start_chat))
                }
            }
            OutlinedButton(
                onClick = onScanQr,
                enabled = !state.starting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(NewChatTestTags.SCAN_QR),
            ) {
                Text(stringResource(R.string.contacts_scan_qr))
            }
            if (!state.scannerOpen) state.scanNotice?.let { ScanNoticeText(it) }
        }
    }
    if (state.scannerOpen) {
        ScannerDialog(notice = state.scanNotice, onClose = onCloseScanner, scanner = scanner)
    }
}

/** Full-screen camera view with a title, a one-line hint, the last [notice] and a close button. */
@Composable
private fun ScannerDialog(notice: ScanNotice?, onClose: () -> Unit, scanner: @Composable (Modifier) -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.contacts_scan_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.contacts_scan_hint), style = MaterialTheme.typography.bodyMedium)
                scanner(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .testTag(NewChatTestTags.SCANNER),
                )
                notice?.let { ScanNoticeText(it) }
                TextButton(onClick = onClose, modifier = Modifier.testTag(NewChatTestTags.CLOSE_SCANNER)) {
                    Text(stringResource(R.string.contacts_scan_close))
                }
            }
        }
    }
}

@Composable
private fun ScanNoticeText(notice: ScanNotice) {
    Text(
        text = stringResource(
            when (notice) {
                ScanNotice.NOT_ACCOUNT_ID -> R.string.contacts_scan_not_account_id
                ScanNotice.OWN_CODE -> R.string.contacts_scan_own_code
                ScanNotice.CAMERA_DENIED -> R.string.contacts_scan_camera_denied
                ScanNotice.CAMERA_UNAVAILABLE -> R.string.contacts_scan_camera_unavailable
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
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
