package im.g.apps.feature.linking

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.g.apps.core.designsystem.R as DesignR
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.component.userNotAdmittedMessage
import im.g.apps.core.designsystem.component.userSuspendedMessage
import im.g.apps.core.designsystem.theme.GimTheme

/** Camera access for the scanner. */
enum class CameraAccess {
    GRANTED,

    /** Not asked yet: explain why, then ask. */
    NOT_REQUESTED,

    /** Denied once; the system still shows the prompt, so explain and ask again. */
    RATIONALE,

    /** Denied for good; only the app settings can grant it. */
    DENIED,
}

/** Callbacks of the stateless [LinkDeviceScreen]. */
class LinkDeviceCallbacks(
    val onBack: () -> Unit,
    val onRequestCamera: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onPastedCodeChange: (String) -> Unit,
    val onSubmitPastedCode: () -> Unit,
    val onConfirm: () -> Unit,
    val onReject: () -> Unit,
    val onDone: () -> Unit,
)

/**
 * Hosts [LinkDeviceScreen] with the camera permission flow and the CameraX scanner. [onBack] leaves the screen;
 * leaving while the SAS is shown rejects the approval first, so the half-linked device is revoked.
 */
@Composable
fun LinkDeviceRoute(
    viewModel: LinkDeviceViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    var access by remember { mutableStateOf(cameraAccess(context, asked)) }
    var cameraUnavailable by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        access = cameraAccess(context, asked = true)
    }
    // The permission may change in the app settings while this screen is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = cameraAccess(context, asked) }

    val back = {
        when (state.step) {
            LinkDeviceStep.CONFIRM -> viewModel.confirm(false)
            LinkDeviceStep.APPROVING, LinkDeviceStep.CONFIRMING -> Unit
            LinkDeviceStep.SCAN, LinkDeviceStep.DONE -> currentOnBack()
        }
    }
    BackHandler(onBack = back)
    val callbacks = remember(viewModel, context) {
        LinkDeviceCallbacks(
            onBack = back,
            onRequestCamera = { launcher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
            onPastedCodeChange = viewModel::onPastedCodeChange,
            onSubmitPastedCode = viewModel::submitPastedCode,
            onConfirm = { viewModel.confirm(true) },
            onReject = { viewModel.confirm(false) },
            onDone = { currentOnBack() },
        )
    }
    LinkDeviceScreen(
        state = state,
        camera = if (cameraUnavailable) null else access,
        callbacks = callbacks,
        scanner = { QrScanner(viewModel::onCodeScanned, onUnavailable = { cameraUnavailable = true }, modifier = it) },
        modifier = modifier,
    )
}

private fun cameraAccess(context: Context, asked: Boolean): CameraAccess {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
        return CameraAccess.GRANTED
    }
    val activity = context.findActivity()
    return when {
        activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA) ->
            CameraAccess.RATIONALE
        asked -> CameraAccess.DENIED
        else -> CameraAccess.NOT_REQUESTED
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Stateless link screen. [camera] is null when the device has no usable camera; [scanner] draws the camera
 * preview once access is [CameraAccess.GRANTED].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkDeviceScreen(
    state: LinkDeviceUiState,
    camera: CameraAccess?,
    callbacks: LinkDeviceCallbacks,
    scanner: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.linking_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.linking_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.step) {
                LinkDeviceStep.SCAN -> ScanStep(state, camera, callbacks, scanner)
                LinkDeviceStep.APPROVING -> Progress(R.string.linking_approving)
                LinkDeviceStep.CONFIRM, LinkDeviceStep.CONFIRMING -> ConfirmStep(state, callbacks)
                LinkDeviceStep.DONE -> DoneStep(state, callbacks)
            }
            state.error?.let { error ->
                Text(
                    text = error.message(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag(LinkDeviceTestTags.ERROR),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ScanStep(
    state: LinkDeviceUiState,
    camera: CameraAccess?,
    callbacks: LinkDeviceCallbacks,
    scanner: @Composable (Modifier) -> Unit,
) {
    Explanation(R.string.linking_scan_explanation, stringResource(DesignR.string.designsystem_link_existing_device))
    when (camera) {
        CameraAccess.GRANTED -> scanner(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .testTag(LinkDeviceTestTags.SCANNER),
        )
        CameraAccess.NOT_REQUESTED, CameraAccess.RATIONALE -> {
            Explanation(
                if (camera == CameraAccess.RATIONALE) R.string.linking_camera_rationale else R.string.linking_camera_request,
            )
            Button(
                onClick = callbacks.onRequestCamera,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(LinkDeviceTestTags.ALLOW_CAMERA),
            ) {
                Text(stringResource(R.string.linking_allow_camera))
            }
        }
        CameraAccess.DENIED -> {
            Explanation(R.string.linking_camera_denied)
            OutlinedButton(
                onClick = callbacks.onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(LinkDeviceTestTags.OPEN_SETTINGS),
            ) {
                Text(stringResource(R.string.linking_open_settings))
            }
        }
        null -> Explanation(R.string.linking_camera_unavailable)
    }
    Text(stringResource(R.string.linking_paste_title), style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = state.pastedCode,
        onValueChange = callbacks.onPastedCodeChange,
        label = { Text(stringResource(R.string.linking_paste_label)) },
        isError = state.error == LinkDeviceError.InvalidCode,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { callbacks.onSubmitPastedCode() }),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LinkDeviceTestTags.PASTE_CODE),
    )
    Button(
        onClick = callbacks.onSubmitPastedCode,
        enabled = state.canSubmitPastedCode,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LinkDeviceTestTags.SUBMIT_CODE),
    ) {
        Text(stringResource(R.string.linking_submit_code))
    }
}

@Composable
private fun ColumnScope.ConfirmStep(state: LinkDeviceUiState, callbacks: LinkDeviceCallbacks) {
    val busy = state.step == LinkDeviceStep.CONFIRMING
    Text(stringResource(R.string.linking_compare_title), style = MaterialTheme.typography.headlineSmall)
    Explanation(R.string.linking_compare_explanation)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = state.sas.orEmpty(),
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(16.dp)
                .testTag(LinkDeviceTestTags.SAS),
        )
    }
    Text(
        text = stringResource(R.string.linking_device_id, state.deviceId.orEmpty()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(LinkDeviceTestTags.DEVICE_ID),
    )
    val confirming = stringResource(R.string.linking_confirming)
    Button(
        onClick = callbacks.onConfirm,
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LinkDeviceTestTags.CONFIRM),
    ) {
        if (busy) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(20.dp)
                    .semantics { contentDescription = confirming },
            )
        } else {
            Text(stringResource(R.string.linking_confirm))
        }
    }
    OutlinedButton(
        onClick = callbacks.onReject,
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LinkDeviceTestTags.REJECT),
    ) {
        Text(stringResource(R.string.linking_reject))
    }
}

@Composable
private fun ColumnScope.DoneStep(state: LinkDeviceUiState, callbacks: LinkDeviceCallbacks) {
    Text(
        text = stringResource(if (state.linked) R.string.linking_done_linked else R.string.linking_done_rejected),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.testTag(LinkDeviceTestTags.DONE),
    )
    Button(onClick = callbacks.onDone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.linking_done))
    }
}

@Composable
private fun ColumnScope.Progress(@StringRes label: Int) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
}

@Composable
private fun Explanation(@StringRes text: Int, vararg formatArgs: Any) {
    Text(
        text = stringResource(text, *formatArgs),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LinkDeviceError.message(): String = when (this) {
    LinkDeviceError.InvalidCode -> stringResource(R.string.linking_error_invalid_code)
    LinkDeviceError.CannotReachServer -> stringResource(R.string.linking_error_cannot_reach_server)
    is LinkDeviceError.TryAgainLater -> pluralStringResource(R.plurals.linking_error_try_again_later, seconds.toInt(), seconds)
    is LinkDeviceError.Suspended -> userSuspendedMessage(hub)
    is LinkDeviceError.NotAdmitted -> userNotAdmittedMessage(hub)
    LinkDeviceError.Generic -> stringResource(R.string.linking_error_generic)
}

internal object LinkDeviceTestTags {
    const val SCANNER = "linking_scanner"
    const val ALLOW_CAMERA = "linking_allow_camera"
    const val OPEN_SETTINGS = "linking_open_settings"
    const val PASTE_CODE = "linking_paste_code"
    const val SUBMIT_CODE = "linking_submit_code"
    const val SAS = "linking_sas"
    const val DEVICE_ID = "linking_device_id"
    const val CONFIRM = "linking_confirm"
    const val REJECT = "linking_reject"
    const val DONE = "linking_done"
    const val ERROR = "linking_error"
}

private val previewCallbacks = LinkDeviceCallbacks({}, {}, {}, {}, {}, {}, {}, {})

@GimPreviews
@Composable
private fun LinkDeviceScanPreview() {
    GimTheme {
        LinkDeviceScreen(
            state = LinkDeviceUiState(error = LinkDeviceError.InvalidCode),
            camera = CameraAccess.RATIONALE,
            callbacks = previewCallbacks,
            scanner = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LinkDeviceConfirmPreview() {
    GimTheme {
        LinkDeviceScreen(
            state = LinkDeviceUiState(step = LinkDeviceStep.CONFIRM, sas = "482 913", deviceId = "d7f3a9c2"),
            camera = CameraAccess.GRANTED,
            callbacks = previewCallbacks,
            scanner = {},
        )
    }
}
