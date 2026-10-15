package im.g.apps.feature.onboarding

import androidx.compose.runtime.Immutable

/** Screens of the new-account flow, in order. */
enum class OnboardingStep {
    /** Server address and the ways to start: create, link (later), restore (later). */
    WELCOME,
    DISPLAY_NAME,

    /** The recovery code from `createIdentity`, shown once. */
    RECOVERY_CODE,
    HANDLE,
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
    val error: OnboardingError? = null,
    val isLoading: Boolean = false,
) {
    val canCreateAccount: Boolean
        get() = !isLoading && isValidServerUrl(serverUrl) && serverUrlError == null

    val canSubmitDisplayName: Boolean
        get() = !isLoading && isValidDisplayName(displayName) && displayNameError == null

    val canClaimHandle: Boolean
        get() = !isLoading && isValidHandle(handle) && handleError == null

    /** Whether system back is handled by [OnboardingViewModel.back] rather than leaving the app. */
    val handlesBack: Boolean
        get() = step == OnboardingStep.DISPLAY_NAME || step == OnboardingStep.RECOVERY_CODE

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
    /** The account exists (with or without a handle): continue to the chat list. */
    data object Finished : OnboardingEvent
}
