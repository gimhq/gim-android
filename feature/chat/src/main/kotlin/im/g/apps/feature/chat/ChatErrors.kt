package im.g.apps.feature.chat

import androidx.annotation.StringRes
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.suspendedHubName
import im.g.apps.core.designsystem.R as DesignR
import im.g.apps.feature.chat.timeline.UiText
import kotlinx.collections.immutable.persistentListOf

private const val HTTP_BAD_GATEWAY = 502

/**
 * Message for a failed user action: "Try again in N s" when gim-core asks to retry later, a read-only notice when the
 * chat is leaving or left ([GimException.ChatNotActive]), "account suspended" naming [suspendedHub]
 * ([suspendedHubName]) for [GimException.UserSuspended], else [fallback].
 */
internal fun GimException.toUiText(@StringRes fallback: Int, suspendedHub: String? = null): UiText = when (this) {
    is GimException.RetryLater ->
        UiText.Res(R.string.chat_error_retry_later, persistentListOf(retryAfterSecs.coerceAtLeast(1).toString()))
    is GimException.ChatNotActive -> UiText.Res(R.string.chat_error_not_active)
    is GimException.UserSuspended -> if (suspendedHub == null) {
        UiText.Res(DesignR.string.gim_error_user_suspended)
    } else {
        UiText.Res(DesignR.string.gim_error_user_suspended_on_hub, persistentListOf(suspendedHub))
    }
    else -> UiText.Res(fallback)
}

/**
 * `fetchMedia` failures a later attempt may fix: the uploader's hub stays unreachable (`RetryLater`) or keeps
 * refusing (`Server(502)`) when the own hub fetches a blob uploaded on another hub.
 */
internal val GimException.isMediaUnavailable: Boolean
    get() = this is GimException.RetryLater || (this is GimException.Server && status == HTTP_BAD_GATEWAY)
