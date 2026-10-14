package im.g.apps.feature.chat.timeline

import im.g.apps.core.bridge.MediaRef

/** Chooses where gim-core writes decrypted attachments. The app supplies the Android implementation. */
interface MediaPathProvider {
    /** Destination for the thumbnail of [media]. */
    fun thumbnailPath(media: MediaRef): String

    /** Destination for the full attachment; [fileName] (if known) keeps the extension for viewer apps. */
    fun mediaPath(media: MediaRef, fileName: String?): String
}
