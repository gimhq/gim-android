package im.g.apps.feature.onboarding

import androidx.compose.runtime.Immutable

/** Screens of the new-account flow, in order. */
enum class OnboardingStep {
    /** Server address and the ways to start: create, link, restore (later). */
    WELCOME,
    DISPLAY_NAME,

    /** The recovery code from `createIdentity`, shown once. */
    RECOVERY_CODE,
    HANDLE,

    /** Link to an existing device: the `startLink` QR code while `waitLink` runs. */
    LINK_QR,

    /** Link to an existing device: the SAS from `waitLink`, compared with the other device. */
    LINK_SAS,
}

/** Inline error shown under a single text field. */
enum class FieldError {
    InvalidServerUrl,
    InvalidHandle,
    HandleTaken,
    InvalidDisplayName,
}

/** Error that is not tied to a single field. */
sealed interface OnboardingError {
    data object CannotReachServer : OnboardingError

    /** The hub throttles requests; retry after [seconds]. */
    data class TryAgainLater(val seconds: Long) : OnboardingError

    /** No device approved the link within ten minutes. */
    data object LinkTimedOut : OnboardingError

    /** Linking failed (for example, the bundle from the other device did not verify). */
    data object LinkFailed : OnboardingError

    data object Generic : OnboardingError
}

@Immutable
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val serverUrl: String = "",
    val serverUrlError: FieldError? = null,
    val displayName: String = "",
    val displayNameError: FieldError? = null,
    /** Set while [step] is [OnboardingStep.RECOVERY_CODE] only; dropped once the user confirms saving it. */
    val recoveryCode: String? = null,
    val recoveryCodeSaved: Boolean = false,
    val handle: String = "",
    val handleError: FieldError? = null,
    /** `LinkSession.qrString()` while [step] is [OnboardingStep.LINK_QR] and the link is waiting; null otherwise. */
    val linkQr: String? = null,
    /** The six-digit SAS ("123 456") while [step] is [OnboardingStep.LINK_SAS]. */
    val linkSas: String? = null,
    val error: OnboardingError? = null,
    val isLoading: Boolean = false,
) {
    val canCreateAccount: Boolean
        get() = !isLoading && isValidServerUrl(serverUrl) && serverUrlError == null

    val canSubmitDisplayName: Boolean
        get() = !isLoading && isValidDisplayName(displayName) && displayNameError == null

    val canClaimHandle: Boolean
        get() = !isLoading && isValidHandle(handle) && handleError == null

    /** On [OnboardingStep.LINK_QR] after the wait ended with an error: a new link can be started. */
    val canRetryLink: Boolean
        get() = step == OnboardingStep.LINK_QR && !isLoading && linkQr == null

    /** Whether system back is handled by [OnboardingViewModel.back] rather than leaving the app. */
    val handlesBack: Boolean
        get() = step == OnboardingStep.DISPLAY_NAME || step == OnboardingStep.RECOVERY_CODE ||
            step == OnboardingStep.LINK_QR || step == OnboardingStep.LINK_SAS

    companion object {
        /** Initial state with [serverUrl] prefilled and flagged when invalid. */
        fun withServerUrl(serverUrl: String) =
            OnboardingUiState(serverUrl = serverUrl, serverUrlError = serverUrlError(serverUrl))
    }
}

/** Blank is not an error yet (the user has not typed); anything else must be a valid URL. */
internal fun serverUrlError(value: String): FieldError? =
    FieldError.InvalidServerUrl.takeIf { value.isNotBlank() && !isValidServerUrl(value) }

sealed interface OnboardingEvent {
    /** The account exists (with or without a handle) or this device is linked: continue to the chat list. */
    data object Finished : OnboardingEvent
}
