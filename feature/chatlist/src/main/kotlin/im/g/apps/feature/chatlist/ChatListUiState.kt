package im.g.apps.feature.chatlist

import androidx.compose.runtime.Immutable
import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.PreviewKind
import im.g.apps.core.designsystem.component.TickState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * @property rows the chats that are not archived: the note to self first, then pinned ones.
 * @property archivedRows archived chats, shown apart; a new message does not move them back.
 * @property isEmpty true once the first chat list snapshot arrived and it has no chats, archived ones included;
 *   false while loading so the empty state does not flash before the first snapshot.
 * @property ownPhotoPath local file of the signed-in user's photo; null shows their initials.
 * @property actionError a chat action that failed, until [ChatListViewModel.errorShown].
 */
@Immutable
data class ChatListUiState(
    val rows: ImmutableList<ChatRowUi> = persistentListOf(),
    val archivedRows: ImmutableList<ChatRowUi> = persistentListOf(),
    val connection: ConnectionBannerUi? = null,
    val isEmpty: Boolean = false,
    val ownPhotoPath: String? = null,
    val actionError: ChatListAction? = null,
) {
    val archivedCount: Int get() = archivedRows.size
}

@Immutable
data class ChatRowUi(
    val id: String,
    /** Group name, or the other user's display name for a direct chat. */
    val title: String,
    /** Local file of the chat's photo (the peer's for a direct chat without one); null shows initials. */
    val avatarPath: String?,
    /** Who sent the last message, shown before [preview]; null when no prefix is needed. */
    val sender: PreviewSender?,
    /** Last message as gim-core's English text; shown as is only for [PreviewKind.Text] (see [previewKind]). */
    val preview: String,
    /** When the chat last changed, relative to now. */
    val time: ChatTimeLabel,
    val unreadCount: Int,
    /** Leaving or left chats show their state instead of the last message. */
    val state: ChatState = ChatState.ACTIVE,
    /** Muted now: muted until a later time, or until unmuted. */
    val muted: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Delivery state of the last message when it is the signed-in user's own; null otherwise. */
    val tick: TickState? = null,
    /** What the last message is; media and deleted messages are rendered from string resources. */
    val previewKind: PreviewKind = PreviewKind.Text,
    /** The note to self: always on top and never archived, titled "Note to self", the own photo with a note badge. */
    val isNoteToSelf: Boolean = false,
) {
    /** The long-press actions, in display order; the note to self stays on top, so only muting applies. */
    val actions: List<ChatListAction>
        get() = listOfNotNull(
            if (muted) ChatListAction.UNMUTE else ChatListAction.MUTE,
            (if (pinned) ChatListAction.UNPIN else ChatListAction.PIN).takeUnless { isNoteToSelf },
            (if (archived) ChatListAction.UNARCHIVE else ChatListAction.ARCHIVE).takeUnless { isNoteToSelf },
        )
}

@Immutable
sealed interface PreviewSender {
    /** The last message is the signed-in user's own. */
    data object You : PreviewSender

    /** Another member of a group chat. */
    data class Member(val firstName: String) : PreviewSender
}

/** Row time, formatted for the clock and locale by the view model and turned into text by the row. */
@Immutable
sealed interface ChatTimeLabel {
    /** Under a minute ago. */
    data object JustNow : ChatTimeLabel

    /** Under an hour ago, today. */
    data class Minutes(val minutes: Int) : ChatTimeLabel

    /** Localized clock time: earlier today, or a future time from clock skew. */
    data class Clock(val text: String) : ChatTimeLabel

    data object Yesterday : ChatTimeLabel

    /** Localized short weekday for the previous six days. */
    data class Weekday(val text: String) : ChatTimeLabel

    /** Localized day and month this year, or a short date for older years. */
    data class Date(val text: String) : ChatTimeLabel
}

enum class ConnectionBannerUi { WAITING_FOR_NETWORK, CONNECTING, UPDATING }
