package im.g.apps.feature.contacts

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException

/** User-facing error categories of the contacts screens; mapped from [GimException]. */
@Immutable
sealed interface ContactsError {
    data object Network : ContactsError

    data object NotRegistered : ContactsError

    data object InvalidInput : ContactsError

    data object NotFound : ContactsError

    /** No invitee of a new chat or member change exists (or has a device); nothing was created. */
    data object UserNotFound : ContactsError

    data object Server : ContactsError

    /** gim-core's own retries are used up (throttled, or a remote hub is unavailable); retry after [seconds]. */
    data class RetryLater(val seconds: Long) : ContactsError

    data object NotSupported : ContactsError

    data object Unknown : ContactsError
}

internal fun GimException.toContactsError(): ContactsError = when (this) {
    is GimException.Network -> ContactsError.Network
    is GimException.NotRegistered -> ContactsError.NotRegistered
    is GimException.InvalidInput -> ContactsError.InvalidInput
    is GimException.NotFound -> ContactsError.NotFound
    is GimException.Server -> ContactsError.Server
    is GimException.RetryLater -> ContactsError.RetryLater(retryAfterSecs.coerceAtLeast(1))
    is GimException.NotImplemented -> ContactsError.NotSupported
    is GimException.Crypto,
    is GimException.Storage,
    is GimException.Cancelled,
    is GimException.Internal,
    -> ContactsError.Unknown
}

/**
 * Like [toContactsError] for `createDirectChat`, `createGroupChat` and `addMembers`, whose `NotFound` means that
 * none of the invited users exists.
 */
internal fun GimException.toInviteError(): ContactsError =
    if (this is GimException.NotFound) ContactsError.UserNotFound else toContactsError()

internal fun ContactsError.message(context: Context): String = when (this) {
    ContactsError.Network -> context.getString(R.string.contacts_error_network)
    ContactsError.NotRegistered -> context.getString(R.string.contacts_error_not_registered)
    ContactsError.InvalidInput -> context.getString(R.string.contacts_error_invalid_input)
    ContactsError.NotFound -> context.getString(R.string.contacts_error_not_found)
    ContactsError.UserNotFound -> context.getString(R.string.contacts_error_user_not_found)
    ContactsError.Server -> context.getString(R.string.contacts_error_server)
    is ContactsError.RetryLater -> context.getString(R.string.contacts_error_retry_later, seconds)
    ContactsError.NotSupported -> context.getString(R.string.contacts_error_not_supported)
    ContactsError.Unknown -> context.getString(R.string.contacts_error_unknown)
}

@Composable
internal fun ContactsError.message(): String = message(LocalContext.current)

/** The signed-in user's id, or null when there is no session or it cannot be read. */
internal suspend fun GimClient.selfUserIdOrNull(): String? = try {
    session()?.userId
} catch (e: GimException) {
    null
}
