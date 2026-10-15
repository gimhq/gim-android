package im.g.apps.feature.chatlist

import androidx.compose.runtime.Immutable
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
    val time: String,
    val unreadCount: Int,
)

@Immutable
sealed interface PreviewSender {
    /** The last message is the signed-in user's own. */
    data object You : PreviewSender

    /** Another member of a group chat. */
    data class Member(val firstName: String) : PreviewSender
}

enum class ConnectionBannerUi { WAITING_FOR_NETWORK, CONNECTING, UPDATING }
