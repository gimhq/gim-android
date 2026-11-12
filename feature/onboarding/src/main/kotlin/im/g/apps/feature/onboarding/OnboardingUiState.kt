package im.g.apps.feature.onboarding

import androidx.compose.runtime.Immutable

/** Screens of the new-account flow, in order. */
enum class OnboardingStep {
    /** What gim is, before anything is asked; its pages are UI state. */
    INTRO,

    /** Server address (unless a default is used) and the ways to start: create, link, restore. */
    WELCOME,
    DISPLAY_NAME,

    /** The recovery kit: user ID and the recovery code from `createIdentity`, shown once. */
    RECOVERY_KIT,

    /** The user types the code's last four symbols to show the kit was saved. */
    RECOVERY_CHECK,

    /** Link to an existing device: the `startLink` QR code while `waitLink` runs. */
    LINK_QR,

    /** Link to an existing device: the SAS from `waitLink`, compared with the other device. */
    LINK_SAS,

    /** Restore after losing every device: user id and recovery code for `restoreFromRecovery`. */
    RESTORE,
}

/** Inline error shown under a single text field. */
enum class FieldError {
    InvalidServerUrl,
    InvalidDisplayName,
    InvalidUserId,

    /** The hub has no DeviceList of this user (`NotFound`). */
    UnknownUser,
    InvalidRecoveryCode,

    /** The code does not open the user's backup (`Crypto`). */
    WrongRecoveryCode,

    /** The typed symbols are not the recovery code's ending. */
    KitCheckMismatch,

    /** The pasted text holds neither a user ID nor a recovery code. */
    KitNotRecognized,

    /** The pasted text holds only one of user ID and recovery code. */
    KitIncomplete,
}

/** Error that is not tied to a single field. */
sealed interface OnboardingError {
    data object CannotReachServer : OnboardingError

    /** The server at the address did not prove that it is the hub at that address (wrong address or a relay). */
    data object HubNotVerified : OnboardingError

    /** The hub throttles requests; retry after [seconds]. */
    data class TryAgainLater(val seconds: Long) : OnboardingError

    /** No device approved the link within ten minutes. */
    data object LinkTimedOut : OnboardingError

    /** Linking failed (for example, the bundle from the other device did not verify). */
    data object LinkFailed : OnboardingError

    /** The hub suspended this user (`UserSuspended`); onboarding talks to one hub, so it is not named. Not retried. */
    data object AccountSuspended : OnboardingError

    /**
     * The user policy of [hub] (the server address) does not admit this user (`UserNotAdmitted`); nothing was kept, so
     * the user can go back and pick another hub. Not retried.
     */
    data class NotAdmitted(val hub: String) : OnboardingError

    data object Generic : OnboardingError
}

@Immutable
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.INTRO,
    val serverUrl: String = "",
    val serverUrlError: FieldError? = null,
    /** The app ships a valid server address; Welcome then hides the field until [showServerField]. */
    val hasDefaultServer: Boolean = false,
    /** Whether Welcome shows the server field: without a default, or once the user asks for another server. */
    val showServerField: Boolean = true,
    val displayName: String = "",
    val displayNameError: FieldError? = null,
    /**
     * Set on [OnboardingStep.RECOVERY_KIT] and [OnboardingStep.RECOVERY_CHECK] only; dropped once the check passes.
     */
    val recoveryCode: String? = null,
    /** The new user's ID (normalized), shown in the kit. */
    val accountId: String? = null,
    /** What the user typed on [OnboardingStep.RECOVERY_CHECK]. */
    val kitCheck: String = "",
    val kitCheckError: FieldError? = null,
    /** `LinkSession.qrString()` while [step] is [OnboardingStep.LINK_QR] and the link is waiting; null otherwise. */
    val linkQr: String? = null,
    /** The six-digit SAS ("123 456") while [step] is [OnboardingStep.LINK_SAS]. */
    val linkSas: String? = null,
    val restoreUserId: String = "",
    val restoreUserIdError: FieldError? = null,
    /** The recovery code's normalized symbols, without separators; the field shows them grouped. */
    val restoreCode: String = "",
    val restoreCodeError: FieldError? = null,
    /** A pasted recovery kit; what it holds fills [restoreUserId] and [restoreCode]. */
    val restoreKitText: String = "",
    val restoreKitError: FieldError? = null,
    /** `revokeOthers` of `restoreFromRecovery`: sign out every other device of the user. */
    val revokeOtherDevices: Boolean = true,
    val error: OnboardingError? = null,
    val isLoading: Boolean = false,
) {
    val canCreateAccount: Boolean
        get() = !isLoading && isValidServerUrl(serverUrl) && serverUrlError == null

    val canSubmitDisplayName: Boolean
        get() = !isLoading && isValidDisplayName(displayName) && displayNameError == null

    val canConfirmKitCheck: Boolean
        get() = !isLoading && normalizeRecoveryCode(kitCheck).length == KIT_CHECK_SYMBOLS

    /**
     * On [OnboardingStep.LINK_QR] after the wait ended with an error, unless suspended or not admitted: a new link can
     * be started.
     */
    val canRetryLink: Boolean
        get() = step == OnboardingStep.LINK_QR && !isLoading && linkQr == null && !accountRefused

    /** The hub refused this user (`UserSuspended` or `UserNotAdmitted`): the same call is not retried. */
    val accountRefused: Boolean
        get() = error == OnboardingError.AccountSuspended || error is OnboardingError.NotAdmitted

    val canRestore: Boolean
        get() = !isLoading && isValidUserId(restoreUserId) && restoreUserIdError == null &&
            isValidRecoveryCode(restoreCode) && restoreCodeError == null

    /** Whether system back is handled by [OnboardingViewModel.back] rather than leaving the app. */
    val handlesBack: Boolean
        get() = step == OnboardingStep.DISPLAY_NAME || step == OnboardingStep.RECOVERY_KIT ||
            step == OnboardingStep.RECOVERY_CHECK || step == OnboardingStep.LINK_QR ||
            step == OnboardingStep.LINK_SAS || step == OnboardingStep.RESTORE

    companion object {
        /**
         * Initial state with [defaultServerUrl] (trimmed) prefilled and flagged when invalid; a valid one hides the
         * server field.
         */
        fun initial(defaultServerUrl: String): OnboardingUiState {
            val serverUrl = defaultServerUrl.trim()
            val hasDefault = serverUrl.isNotBlank() && isValidServerUrl(serverUrl)
            return OnboardingUiState(
                serverUrl = serverUrl,
                serverUrlError = serverUrlError(serverUrl),
                hasDefaultServer = hasDefault,
                showServerField = !hasDefault,
            )
        }
    }
}

/** The recovery code symbols typed back on [OnboardingStep.RECOVERY_CHECK]. */
private const val KIT_CHECK_SYMBOLS = 4

/** Blank is not an error yet (the user has not typed); anything else must be a valid URL. */
internal fun serverUrlError(value: String): FieldError? =
    FieldError.InvalidServerUrl.takeIf { value.isNotBlank() && !isValidServerUrl(value) }

sealed interface OnboardingEvent {
    /** The account exists and its kit was checked, or this device is linked or restored: go to the chat list. */
    data object Finished : OnboardingEvent
}
