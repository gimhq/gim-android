package im.g.apps.core.bridge

/** Errors raised by [GimClient]; mirrors gim-core's UniFFI error enum. */
sealed class GimException(message: String) : Exception(message) {
    class Network(message: String = "network unavailable") : GimException(message)

    class Unauthorized(message: String = "not registered") : GimException(message)

    class NotFound(val what: String) : GimException("not found: $what")

    class InvalidInput(val field: String, val reason: String) : GimException("invalid $field: $reason")

    class Conflict(message: String = "conflict") : GimException(message)

    class Internal(message: String) : GimException(message)
}
