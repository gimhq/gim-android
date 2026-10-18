package im.g.apps.feature.contacts

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.suspendedHubName
import im.g.apps.core.designsystem.R as DesignR
import im.g.apps.core.designsystem.component.userNotAdmittedMessage
import im.g.apps.core.designsystem.component.userSuspendedMessage

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

    /** The chat is leaving or left (`ChatNotActive`): it is read-only. */
    data object ChatNotActive : ContactsError

    /** The hub suspended this user (`UserSuspended`); [hub] names it when the user has several hubs. Not retried. */
    data class Suspended(val hub: String?) : ContactsError

    /** The hub's user policy does not admit this user (`UserNotAdmitted`); [hub] as for [Suspended]. Not retried. */
    data class NotAdmitted(val hub: String?) : ContactsError

    /** The hub owning the chat does not admit this user (`NotAdmittedByPeer`). Not retried. */
    data object NotAdmittedByPeer : ContactsError

    /** No invitee could be added: the hubs of [users] (names, or user ids if unknown) do not admit this user. */
    data class ContactNotAdmitted(val users: List<String>) : ContactsError

    data object Unknown : ContactsError
}

/** [suspendedHub] is [suspendedHubName] of this error. */
internal fun GimException.toContactsError(suspendedHub: String? = null): ContactsError = when (this) {
    is GimException.Network -> ContactsError.Network
    is GimException.NotRegistered -> ContactsError.NotRegistered
    is GimException.InvalidInput -> ContactsError.InvalidInput
    is GimException.NotFound -> ContactsError.NotFound
    is GimException.Server -> ContactsError.Server
    is GimException.RetryLater -> ContactsError.RetryLater(retryAfterSecs.coerceAtLeast(1))
    is GimException.NotImplemented -> ContactsError.NotSupported
    is GimException.ChatNotActive -> ContactsError.ChatNotActive
    is GimException.UserSuspended -> ContactsError.Suspended(suspendedHub)
    is GimException.UserNotAdmitted -> ContactsError.NotAdmitted(suspendedHub)
    is GimException.NotAdmittedByPeer -> ContactsError.NotAdmittedByPeer
    is GimException.ContactNotAdmitted -> ContactsError.ContactNotAdmitted(userIds)
    is GimException.Crypto,
    is GimException.Storage,
    is GimException.Cancelled,
    is GimException.Internal,
    is GimException.HubNotVerified,
    is GimException.HubOwnsGroups,
    is GimException.LastHub,
    is GimException.HomeHub,
    is GimException.HubNotDrained,
    -> ContactsError.Unknown
}

/**
 * Like [toContactsError] for `createDirectChat`, `createGroupChat` and `addMembers`, whose `NotFound` means that
 * none of the invited users exists; `ContactNotAdmitted` names the refused users found in [invitees].
 */
internal fun GimException.toInviteError(
    suspendedHub: String? = null,
    invitees: List<UserProfile> = emptyList(),
): ContactsError = when (this) {
    is GimException.NotFound -> ContactsError.UserNotFound
    is GimException.ContactNotAdmitted -> ContactsError.ContactNotAdmitted(
        userIds.map { id -> invitees.firstOrNull { it.userId == id }?.contactName() ?: id },
    )
    else -> toContactsError(suspendedHub)
}

/** The display name, else "@handle", else the user id. */
private fun UserProfile.contactName(): String = when {
    displayName.isNotBlank() && displayName != userId -> displayName
    handle.isNotBlank() -> "@$handle"
    else -> userId
}

internal fun ContactsError.message(context: Context): String = when (this) {
    ContactsError.Network -> context.getString(R.string.contacts_error_network)
    ContactsError.NotRegistered -> context.getString(R.string.contacts_error_not_registered)
    ContactsError.InvalidInput -> context.getString(R.string.contacts_error_invalid_input)
    ContactsError.NotFound -> context.getString(R.string.contacts_error_not_found)
    ContactsError.UserNotFound -> context.getString(R.string.contacts_error_user_not_found)
    ContactsError.Server -> context.getString(R.string.contacts_error_server)
    is ContactsError.RetryLater -> context.getString(R.string.contacts_error_retry_later, seconds)
    ContactsError.NotSupported -> context.getString(R.string.contacts_error_not_supported)
    ContactsError.ChatNotActive -> context.getString(R.string.contacts_error_chat_not_active)
    is ContactsError.Suspended -> userSuspendedMessage(context, hub)
    is ContactsError.NotAdmitted -> userNotAdmittedMessage(context, hub)
    ContactsError.NotAdmittedByPeer -> context.getString(DesignR.string.gim_error_not_admitted_by_peer)
    is ContactsError.ContactNotAdmitted ->
        context.getString(R.string.contacts_error_contact_not_admitted, users.joinToString(", "))
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
