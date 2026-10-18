package im.g.apps.core.bridge

import java.net.URI
import java.net.URISyntaxException

/** Error thrown by every fallible [GimClient] call; mirrors gim-core's `GimError` (`error.rs`). */
sealed class GimException(message: String) : Exception(message) {
    /** The server could not be reached or the connection broke. */
    class Network(val reason: String) : GimException("network error: $reason")

    /** No session, signed out, or this device was revoked: go to onboarding. */
    class NotRegistered : GimException("not registered")

    /**
     * An argument was rejected, the call does not fit the state, the payload is too large for the hub, or the hub
     * rejected the request as invalid (400 `validation_failed`, 422). Never parse [reason].
     */
    class InvalidInput(val reason: String) : GimException("invalid input: $reason")

    /** The chat, message, user or media does not exist locally or on the server. */
    class NotFound(val reason: String) : GimException("not found: $reason")

    /** MLS or media encryption/decryption failed. */
    class Crypto(val reason: String) : GimException("crypto error: $reason")

    /**
     * The local database or file system failed; also "store is locked by another process" when another client on the
     * same data dir held `<dataDir>/gim.lock` past the timeout (nothing was changed; retry later).
     */
    class Storage(val reason: String) : GimException("storage error: $reason")

    /**
     * gim-hub answered with a non-success HTTP status (`u16` in gim-core). A 4xx (e.g. 403 `not_member`) is a
     * definite rejection gim-core does not resend; a 5xx, 408 or 429 is resent unchanged.
     */
    class Server(val status: Int, val reason: String) : GimException("server error $status: $reason")

    /** The hub throttles (429) or a remote hub is unavailable (503) after gim-core's own retries; retry later. */
    class RetryLater(val status: Int, val retryAfterSecs: Long, val reason: String) :
        GimException("retry after $retryAfterSecs s (status $status): $reason")

    /** The operation was cancelled (sign-out, `stopSync`, `cancelSend`). */
    class Cancelled : GimException("cancelled")

    /** The call is part of the API contract but not implemented yet. */
    class NotImplemented : GimException("not implemented")

    /**
     * The call sends to or changes a chat this device is leaving ([ChatState.LEAVING]) or has left
     * ([ChatState.LEFT]): show the chat read-only.
     */
    class ChatNotActive(val chatId: String, val state: ChatState) :
        GimException("chat $chatId is not active: $state")

    /**
     * The server at the configured URL did not prove that it is the expected hub at that URL (a wrong URL, or a relay
     * in between); the device did not sign in there. Check the hub URL.
     */
    class HubNotVerified(val reason: String) : GimException("hub not verified: $reason")

    /**
     * [GimClient.removeHub] without `confirmOwnedGroups`: the hub owns [groupCount] chats of this user. Ask the user,
     * then call again with `confirmOwnedGroups = true`.
     */
    class HubOwnsGroups(val hubId: String, val groupCount: Int) : GimException("hub $hubId owns $groupCount chats")

    /** [GimClient.removeHub] of the only listed hub: add another hub first. */
    class LastHub(val hubId: String) : GimException("hub $hubId is the only hub")

    /** [GimClient.removeHub] of the hub the client was created for: make another hub primary instead. */
    class HomeHub(val hubId: String) : GimException("hub $hubId is the home hub")

    /** [GimClient.removeHub] while messages or member changes sent through the hub are not settled; retry later. */
    class HubNotDrained(val hubId: String, val reason: String) : GimException("hub $hubId is not drained: $reason")

    /**
     * An admin of hub [hubId] suspended this user: the hub refused the sign-in (403 `user_suspended`). The session and
     * local data are kept; show "account suspended" ([suspendedHubName]), do not retry and do not sign out. [hubId] is
     * empty for a home hub without a hub identity.
     */
    class UserSuspended(val hubId: String) : GimException("user suspended on hub $hubId")

    /**
     * The user policy of hub [hubId] does not admit this user: the hub refused the registration (onboarding,
     * [GimClient.addHub]: nothing was kept, so the call may be retried against another hub) or a sign-in. Handled like
     * [UserSuspended]: do not retry and do not sign out. [hubId] is empty if unknown.
     */
    class UserNotAdmitted(val hubId: String) : GimException("user not admitted on hub $hubId")

    /**
     * Hub [hubId], which owns chat [chatId], does not admit this user: the message or member change was refused there
     * and is not retried. Other chats are not affected; do not offer a retry.
     */
    class NotAdmittedByPeer(val hubId: String, val chatId: String) :
        GimException("not admitted by hub $hubId of chat $chatId")

    /**
     * [GimClient.createDirectChat] / [GimClient.createGroupChat] / [GimClient.addMembers]: no user could be added, and
     * the hubs of [userIds] do not admit this user.
     */
    class ContactNotAdmitted(val userIds: List<String>) : GimException("contacts not admitted: $userIds")

    /** A bug or an unexpected failure inside gim-core or a platform callback. */
    class Internal(val reason: String) : GimException("internal error: $reason")
}

/**
 * The hub an "account suspended" or "not admitted" message for [e] names: its URL when the user has several hubs and
 * the [GimException.UserSuspended.hubId] / [GimException.UserNotAdmitted.hubId] is one of them. Null (the generic "this
 * hub" text) for one hub, an empty or unknown hub id, a hub list that cannot be read, or any other error.
 */
suspend fun GimClient.suspendedHubName(e: GimException): String? {
    val hubId = when (e) {
        is GimException.UserSuspended -> e.hubId
        is GimException.UserNotAdmitted -> e.hubId
        else -> return null
    }
    if (hubId.isEmpty()) return null
    val hubs = try {
        hubs()
    } catch (_: GimException) {
        return null
    }
    return if (hubs.size > 1) hubs.firstOrNull { it.hubId == hubId }?.url else null
}

/** Same check as gim-core's `create_client`: an `http`/`https` URL with a host, else [GimException.InvalidInput]. */
internal fun requireValidServerUrl(value: String) {
    val uri = try {
        URI(value)
    } catch (e: URISyntaxException) {
        throw GimException.InvalidInput("server_url: ${e.reason}")
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        throw GimException.InvalidInput("server_url: unsupported scheme '${uri.scheme.orEmpty()}'")
    }
    if (uri.host.isNullOrEmpty()) throw GimException.InvalidInput("server_url: missing host")
}
