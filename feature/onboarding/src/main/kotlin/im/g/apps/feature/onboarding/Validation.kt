package im.g.apps.feature.onboarding

import java.net.URI
import java.net.URISyntaxException

internal const val DISPLAY_NAME_MAX_LENGTH = 64

/** An `http`/`https` URL with a host, after trimming. */
internal fun isValidServerUrl(value: String): Boolean {
    val uri = try {
        URI(value.trim())
    } catch (e: URISyntaxException) {
        return false
    }
    val scheme = uri.scheme?.lowercase()
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
}

internal fun isValidDisplayName(value: String): Boolean = value.trim().length in 1..DISPLAY_NAME_MAX_LENGTH
