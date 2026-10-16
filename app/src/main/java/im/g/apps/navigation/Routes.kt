package im.g.apps.navigation

import android.net.Uri
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.GimException

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT_LIST = "chatlist"
    const val NEW_CHAT = "newChat"
    const val NEW_GROUP = "newGroup"
    const val LINK_DEVICE = "linkDevice"
    const val CHAT_ID = "chatId"
    const val CHAT = "chat/{$CHAT_ID}"
    const val GROUP_INFO = "groupInfo/{$CHAT_ID}"
    const val DEVICES = "devices"
    const val HUBS = "hubs"

    fun chat(chatId: String) = "chat/${Uri.encode(chatId)}"

    fun groupInfo(chatId: String) = "groupInfo/${Uri.encode(chatId)}"
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
