package im.g.apps.feature.chatlist

import androidx.compose.runtime.Immutable
import im.g.apps.core.bridge.ChatState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * @property isEmpty true once the first chat list snapshot arrived and it has no chats; false while
 *   loading so the empty state does not flash before the first snapshot.
 */
@Immutable
data class ChatListUiState(
    val rows: ImmutableList<ChatRowUi> = persistentListOf(),
    val connection: ConnectionBannerUi? = null,
    val isEmpty: Boolean = false,
)

@Immutable
data class ChatRowUi(
    val id: String,
    /** Group name, or the other user's display name for a direct chat. */
    val title: String,
    /** Local file of the avatar thumbnail; null shows initials. */
    val avatarPath: String?,
    /** Who sent the last message, shown before [preview]; null when no prefix is needed. */
    val sender: PreviewSender?,
    /** Last message as rendered by gim-core ("Photo", "File: name", "Message deleted", ...). */
    val preview: String,
    /** When the chat last changed, relative to now. */
    val time: ChatTimeLabel,
    val unreadCount: Int,
    /** Leaving or left chats show their state instead of the last message. */
    val state: ChatState = ChatState.ACTIVE,
)

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
