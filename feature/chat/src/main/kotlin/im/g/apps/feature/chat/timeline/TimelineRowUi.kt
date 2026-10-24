package im.g.apps.feature.chat.timeline

import androidx.compose.runtime.Immutable
import im.g.apps.core.bridge.SendFailure
import im.g.apps.core.designsystem.component.ReactionChipModel
import im.g.apps.core.designsystem.component.TickState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** One row of the chat timeline, precomputed off the main thread. [key] is stable across updates. */
@Immutable
sealed interface TimelineRowUi {
    val key: String
    val contentType: String
}

@Immutable
data class MessageRow(
    override val key: String,
    override val contentType: String,
    val outgoing: Boolean,
    /** Sender shown above the bubble: groups only, inbound only, first bubble of a run only. */
    val senderName: String?,
    /** Text body, or the caption of an image or file. */
    val body: String?,
    val image: ImageUi?,
    val file: FileUi?,
    val replyPreview: ReplyPreviewUi?,
    val reactions: ImmutableList<ReactionChipModel>,
    val edited: Boolean,
    val deleted: Boolean,
    val unsupported: Boolean,
    val tick: TickState?,
    /** Local time, "HH:mm". */
    val time: String,
    /** Same sender as the chronologically previous message, within [GROUPING_WINDOW_MS]. */
    val groupedWithPrevious: Boolean,
    /** Same sender as the chronologically next message, within [GROUPING_WINDOW_MS]. */
    val groupedWithNext: Boolean,
    /** The attachment could not be fetched from the uploader's hub for now: offer a retry. */
    val mediaUnavailable: Boolean = false,
    /** Why the send failed; set for a failed own message. */
    val sendFailure: SendFailure? = null,
    /** Sender of an inbound group message, on every bubble of the run; null otherwise. */
    val sender: SenderUi? = null,
) : TimelineRowUi {
    val failed: Boolean get() = tick == TickState.FAILED

    /** The sender's avatar sits beside the last bubble of a run. */
    val showAvatar: Boolean get() = sender != null && !groupedWithNext

    /** A failed send may be retried unless its [sendFailure] says the user is refused (gim-core's guidance). */
    val retryable: Boolean get() = sendFailure?.retryable ?: true
}

@Immutable
data class DateSeparatorRow(
    override val key: String,
    val label: UiText,
) : TimelineRowUi {
    override val contentType: String get() = CONTENT_TYPE_DATE
}

/** Membership change ("Ana was added"). */
@Immutable
data class SystemRow(
    override val key: String,
    val text: UiText,
) : TimelineRowUi {
    override val contentType: String get() = CONTENT_TYPE_SYSTEM
}

@Immutable
data class SenderUi(
    val userId: String,
    val name: String,
)

@Immutable
data class ImageUi(
    /** Width / height, clamped so extreme images keep a usable bubble size. */
    val aspectRatio: Float,
    /** Local file (thumbnail first, then full size); null until fetched. */
    val path: String?,
)

@Immutable
data class FileUi(
    val name: String,
    val sizeLabel: String,
    val mime: String,
    /** Short type label drawn as the file icon, e.g. "PDF". */
    val typeLabel: String,
)

@Immutable
data class ReplyPreviewUi(
    val senderName: String,
    val text: String,
)

@Immutable
data class ChatHeaderUi(
    val title: String = "",
    /** Typing label, member count for groups, or null. */
    val subtitle: UiText? = null,
    val avatarPath: String? = null,
    val isGroup: Boolean = false,
)

@Immutable
data class ChatUiState(
    val header: ChatHeaderUi = ChatHeaderUi(),
    /** Newest first, for `LazyColumn(reverseLayout = true)`. */
    val items: ImmutableList<TimelineRowUi> = persistentListOf(),
    val isLoadingOlder: Boolean = false,
    val hasMoreOlder: Boolean = true,
    val typingLabel: UiText? = null,
    val unreadCount: Int = 0,
    /** A direct chat whose peer the user blocked: the screen offers Unblock instead of the composer. */
    val blocked: Boolean = false,
)

/** One-shot events from [im.g.apps.feature.chat.ChatViewModel]. */
sealed interface ChatEvent {
    data class OpenFile(val path: String, val mime: String) : ChatEvent

    /** A user-initiated action failed; shown as a snackbar. */
    data class Error(val message: UiText) : ChatEvent
}

const val GROUPING_WINDOW_MS = 5 * 60_000L

internal const val CONTENT_TYPE_TEXT = "message-text"
internal const val CONTENT_TYPE_IMAGE = "message-image"
internal const val CONTENT_TYPE_FILE = "message-file"
internal const val CONTENT_TYPE_DELETED = "message-deleted"
internal const val CONTENT_TYPE_UNSUPPORTED = "message-unsupported"
internal const val CONTENT_TYPE_DATE = "date"
internal const val CONTENT_TYPE_SYSTEM = "system"
