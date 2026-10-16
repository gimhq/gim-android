package im.g.apps.feature.chat.composer

import androidx.compose.runtime.Immutable
import im.g.apps.feature.chat.timeline.UiText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/** What the next send does. */
@Immutable
sealed interface ComposerMode {
    data object Normal : ComposerMode

    /** The next text or attachment quotes [messageId]. */
    data class Replying(val messageId: String, val senderName: String, val preview: UiText) : ComposerMode

    /** Send replaces the body of [messageId]; [originalText] is its current body. */
    data class Editing(val messageId: String, val originalText: String) : ComposerMode
}

/** The signed-in user's membership of the chat, as far as sending goes; from gim-core's `ChatState`. */
enum class ChatMembership {
    /** `ChatState.Active`. */
    JOINED,

    /** `ChatState.Leaving`: `leaveChat` was accepted; nothing can be sent until another member commits it. */
    LEAVING,

    /** `ChatState.Left` after leaving (or hub-side). */
    LEFT,

    /** `ChatState.Left` whose latest own membership change is a removal by another member. */
    REMOVED,
}

/** Actions offered for one long-pressed message; availability is decided by [ComposerViewModel]. */
@Immutable
data class MessageActionsUi(
    val messageId: String,
    val canReply: Boolean,
    /** Text body or caption to copy; null when there is nothing to copy. */
    val copyText: String?,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val canReact: Boolean,
    /** Quick reactions the signed-in user already added. */
    val ownReactions: ImmutableSet<String> = persistentSetOf(),
    /** Retry, for own messages that failed to send (rejected by the hub or out of retries). */
    val canRetry: Boolean,
    /** Delete a failed own message: removes it from the outbox and the timeline (`cancelSend`). */
    val canDiscard: Boolean = false,
)

@Immutable
data class ComposerUiState(
    val draft: String = "",
    val mode: ComposerMode = ComposerMode.Normal,
    /** Message actions sheet, or null when hidden. */
    val actions: MessageActionsUi? = null,
    /** Message waiting for delete confirmation, or null. */
    val confirmDeleteId: String? = null,
    /** An attachment is being copied and handed to the client. */
    val isAttaching: Boolean = false,
    val quickReactions: ImmutableList<String> = QUICK_REACTIONS,
    val membership: ChatMembership = ChatMembership.JOINED,
) {
    val isMember: Boolean get() = membership == ChatMembership.JOINED

    val canSend: Boolean get() = draft.isNotBlank() && isMember

    /** Attachments are not offered while editing. */
    val canAttach: Boolean get() = mode !is ComposerMode.Editing && !isAttaching && isMember
}

/** One-shot events from [ComposerViewModel]. */
sealed interface ComposerEvent {
    data class Error(val message: UiText) : ComposerEvent
}

val QUICK_REACTIONS: ImmutableList<String> = persistentListOf("👍", "❤️", "😂", "😮", "😢", "🙏")
