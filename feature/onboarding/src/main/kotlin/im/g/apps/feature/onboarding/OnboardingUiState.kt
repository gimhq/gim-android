package im.g.apps.feature.onboarding

import androidx.compose.runtime.Immutable
import im.g.apps.core.bridge.Session

/** Inline error shown under a single text field. */
enum class FieldError {
    InvalidServerUrl,
    InvalidHandle,
    HandleTaken,
    InvalidDisplayName,
}

/** Error that is not tied to a single field. */
enum class OnboardingError {
    CannotReachServer,
    Generic,
}

@Immutable
data class OnboardingUiState(
    val serverUrl: String = "",
    val handle: String = "",
    val displayName: String = "",
    val serverUrlError: FieldError? = null,
    val handleError: FieldError? = null,
    val displayNameError: FieldError? = null,
    val error: OnboardingError? = null,
    val isLoading: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !isLoading &&
            isValidServerUrl(serverUrl) &&
            isValidHandle(handle) &&
            isValidDisplayName(displayName) &&
            serverUrlError == null &&
            handleError == null &&
            displayNameError == null
}

sealed interface OnboardingEvent {
    data class Registered(val session: Session) : OnboardingEvent
}
