package im.g.apps.feature.contacts

import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.UserProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The [ContactPhotos] key of a chat's avatar; a user's photo is keyed by the user id. */
internal fun chatPhotoKey(chatId: String, messageId: String): String = "chat-$chatId-$messageId"

/**
 * Downloads photos for the contacts screens into [avatarDir], the cache the chat list uses, each file at most once
 * and on [dispatcher]. The key doubles as the file name; [onPhoto] gets it with the local path when a download
 * finishes. Users without a photo, and failed downloads, keep their initials.
 */
internal class ContactPhotos(
    private val client: GimClient,
    private val avatarDir: File,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val onPhoto: (key: String, path: String) -> Unit,
) {
    private val requested = ConcurrentHashMap.newKeySet<String>()

    fun requestUser(user: UserProfile) {
        if (user.hasAvatar) request(user.userId) { path -> client.fetchUserAvatar(user.userId, path) }
    }

    fun requestChat(chatId: String, messageId: String) {
        request(chatPhotoKey(chatId, messageId)) { path -> client.fetchChatAvatar(chatId, path) }
    }

    private fun request(key: String, fetch: suspend (destPath: String) -> String) {
        if (!requested.add(key)) return
        scope.launch(dispatcher) {
            val path = try {
                fetch(File(avatarDir, key).path)
            } catch (_: GimException) {
                null
            }
            if (path != null) onPhoto(key, path)
        }
    }
}
