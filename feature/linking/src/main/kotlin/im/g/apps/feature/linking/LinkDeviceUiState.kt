package im.g.apps.feature.linking

import androidx.compose.runtime.Immutable

/** Screens of the existing-device side of a device link, in order. */
enum class LinkDeviceStep {
    /** Camera scanner and the paste fallback. */
    SCAN,

    /** `approveLink` is running. */
    APPROVING,

    /** The SAS of the pending approval, compared with the new device's. */
    CONFIRM,

    /** `confirmApproval` is running. */
    CONFIRMING,

    /** The approval was confirmed or rejected. */
    DONE,
}

sealed interface LinkDeviceError {
    /** The scanned or pasted text is not a link code (`InvalidInput`). */
    data object InvalidCode : LinkDeviceError

    data object CannotReachServer : LinkDeviceError

    /** The hub throttles requests; retry after [seconds]. */
    data class TryAgainLater(val seconds: Long) : LinkDeviceError

    data object Generic : LinkDeviceError
}

@Immutable
data class LinkDeviceUiState(
    val step: LinkDeviceStep = LinkDeviceStep.SCAN,
    val pastedCode: String = "",
    /** `PendingApproval.sas()` from [LinkDeviceStep.CONFIRM] on. */
    val sas: String? = null,
    /** `PendingApproval.deviceId()` from [LinkDeviceStep.CONFIRM] on. */
    val deviceId: String? = null,
    /** On [LinkDeviceStep.DONE]: whether the new device was added (true) or revoked (false). */
    val linked: Boolean = false,
    val error: LinkDeviceError? = null,
) {
    val canSubmitPastedCode: Boolean
        get() = step == LinkDeviceStep.SCAN && pastedCode.isNotBlank()
}
