package im.g.apps.feature.contacts

import androidx.annotation.StringRes
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException

/** User-facing error categories of the contacts screens; mapped from [GimException]. */
enum class ContactsError {
    NETWORK,
    NOT_REGISTERED,
    INVALID_INPUT,
    NOT_FOUND,
    SERVER,
    NOT_SUPPORTED,

    /** gim-core cannot rename groups yet (`set_group_info` is NotImplemented until S17). */
    RENAME_NOT_SUPPORTED,
    UNKNOWN,
}

internal fun GimException.toContactsError(): ContactsError = when (this) {
    is GimException.Network -> ContactsError.NETWORK
    is GimException.NotRegistered -> ContactsError.NOT_REGISTERED
    is GimException.InvalidInput -> ContactsError.INVALID_INPUT
    is GimException.NotFound -> ContactsError.NOT_FOUND
    is GimException.Server -> ContactsError.SERVER
    is GimException.NotImplemented -> ContactsError.NOT_SUPPORTED
    is GimException.Crypto,
    is GimException.Storage,
    is GimException.Cancelled,
    is GimException.Internal,
    -> ContactsError.UNKNOWN
}

@get:StringRes
internal val ContactsError.messageRes: Int
    get() = when (this) {
        ContactsError.NETWORK -> R.string.contacts_error_network
        ContactsError.NOT_REGISTERED -> R.string.contacts_error_not_registered
        ContactsError.INVALID_INPUT -> R.string.contacts_error_invalid_input
        ContactsError.NOT_FOUND -> R.string.contacts_error_not_found
        ContactsError.SERVER -> R.string.contacts_error_server
        ContactsError.NOT_SUPPORTED -> R.string.contacts_error_not_supported
        ContactsError.RENAME_NOT_SUPPORTED -> R.string.contacts_error_rename_not_supported
        ContactsError.UNKNOWN -> R.string.contacts_error_unknown
    }

/** The signed-in user's id, or null when there is no session or it cannot be read. */
internal suspend fun GimClient.selfUserIdOrNull(): String? = try {
    session()?.userId
} catch (e: GimException) {
    null
}
