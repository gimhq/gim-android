package im.g.apps.navigation

import android.net.Uri
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.GimException

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT_LIST = "chatlist"
    const val CHAT_LIST_ARCHIVED = "chatlist/archived"
    const val NEW_CHAT = "newChat"
    const val NEW_GROUP = "newGroup"
    const val LINK_DEVICE = "linkDevice"
    const val CHAT_ID = "chatId"
    const val CHAT = "chat/{$CHAT_ID}"
    const val GROUP_INFO = "groupInfo/{$CHAT_ID}"
    const val USER_ID = "userId"
    const val MESSAGE_ID = "messageId"
    const val REPORT = "report/{$USER_ID}/{$CHAT_ID}?$MESSAGE_ID={$MESSAGE_ID}"
    const val DEVICES = "devices"
    const val HUBS = "hubs"
    const val SETTINGS = "settings"
    const val SETTINGS_PROFILE = "settings/profile"
    const val SETTINGS_BLOCKED = "settings/blocked"
    const val SETTINGS_RECOVERY_KEY = "settings/recovery-key"
    const val SETTINGS_NEW_RECOVERY_KEY = "settings/new-recovery-key"
    const val SETTINGS_DELETE_ACCOUNT = "settings/delete-account"
    const val SETTINGS_NOTIFICATIONS = "settings/notifications"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_LANGUAGE = "settings/language"
    const val SETTINGS_KEEP_SERVER = "settings/keep-server"
    const val SETTINGS_HELP = "settings/help"
    const val SETTINGS_LICENCES = "settings/licences"

    fun chat(chatId: String) = "chat/${Uri.encode(chatId)}"

    fun groupInfo(chatId: String) = "groupInfo/${Uri.encode(chatId)}"

    /** The report about [userId] with messages from [chatId]; [messageId] is picked to start with. */
    fun report(userId: String, chatId: String, messageId: String? = null) =
        "report/${Uri.encode(userId)}/${Uri.encode(chatId)}" + messageId?.let { "?$MESSAGE_ID=${Uri.encode(it)}" }.orEmpty()
}

/**
 * Restores the stored client: the chat list when it has a session, onboarding otherwise, including when gim-core
 * discarded the local state on open ([im.g.apps.core.bridge.GimClient.localStateReset]).
 */
suspend fun resolveStartDestination(manager: GimClientManager): String {
    val session = try {
        manager.restore()?.takeUnless { it.localStateReset() }?.session()
    } catch (_: GimException) {
        null
    }
    return if (session == null) Routes.ONBOARDING else Routes.CHAT_LIST
}
