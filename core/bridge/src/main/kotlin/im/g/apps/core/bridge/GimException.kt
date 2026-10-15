package im.g.apps.core.bridge

import java.net.URI
import java.net.URISyntaxException

/** Error thrown by every fallible [GimClient] call; mirrors gim-core's `GimError` (`error.rs`). */
sealed class GimException(message: String) : Exception(message) {
    /** The server could not be reached or the connection broke. */
    class Network(val reason: String) : GimException("network error: $reason")

    /** The call needs a session; call [GimClient.register] first. */
    class NotRegistered : GimException("not registered")

    /** An argument was rejected before anything was sent. */
    class InvalidInput(val reason: String) : GimException("invalid input: $reason")

    /** The chat, message, user or media does not exist locally or on the server. */
    class NotFound(val reason: String) : GimException("not found: $reason")

    /** MLS or media encryption/decryption failed. */
    class Crypto(val reason: String) : GimException("crypto error: $reason")

    /** The local database or file system failed. */
    class Storage(val reason: String) : GimException("storage error: $reason")

    /** gim-server answered with a non-success HTTP status (`u16` in gim-core). */
    class Server(val status: Int, val reason: String) : GimException("server error $status: $reason")

    /** The operation was cancelled (sign-out, `stopSync`, `cancelSend`). */
    class Cancelled : GimException("cancelled")

    /** The call is part of the API contract but not implemented yet. */
    class NotImplemented : GimException("not implemented")

    /** A bug or an unexpected failure inside gim-core or a platform callback. */
    class Internal(val reason: String) : GimException("internal error: $reason")
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
