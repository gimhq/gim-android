package im.g.apps

import android.webkit.MimeTypeMap
import im.g.apps.core.bridge.MediaRef
import im.g.apps.core.bridge.platform.AndroidMediaPaths
import im.g.apps.feature.chat.timeline.MediaPathProvider

/**
 * Attachment destinations derived from chat and message ids only. The remote file name is untrusted: it
 * contributes a sanitized suffix (so viewer apps see a sensible name and extension), never a directory.
 */
class AppMediaPathProvider(private val paths: AndroidMediaPaths) : MediaPathProvider {
    override fun thumbnailPath(media: MediaRef): String = paths.destFor(media.chatId, media.messageId, thumbnail = true)

    override fun mediaPath(media: MediaRef, fileName: String?): String {
        val base = paths.destFor(media.chatId, media.messageId, thumbnail = false)
        val name = sanitizeDisplayName(fileName)
        val ext = if (name.contains('.')) null else extensionFor(media.mime)
        return buildString {
            append(base)
            if (name.isNotEmpty()) append('_').append(name)
            if (ext != null) append('.').append(ext)
        }
    }

    private fun extensionFor(mime: String): String? =
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { SAFE_EXTENSION.matches(it) }

    internal companion object {
        private const val MAX_NAME = 64
        private const val MAX_EXTENSION = 10
        private val UNSAFE = Regex("[^\\p{L}\\p{N}._ -]")
        private val SAFE_EXTENSION = Regex("[A-Za-z0-9]{1,$MAX_EXTENSION}")

        /**
         * Last path segment of [fileName] with control characters dropped, other unsafe characters
         * replaced, no `..` and no leading dots, capped at [MAX_NAME] characters (keeping a short extension).
         */
        fun sanitizeDisplayName(fileName: String?): String {
            if (fileName == null) return ""
            var name = fileName.substringAfterLast('/').substringAfterLast('\\')
                .filterNot { it.isISOControl() }
                .replace(UNSAFE, "_")
            while (".." in name) name = name.replace("..", ".")
            name = name.trim().trimStart('.').trim()
            if (name.length <= MAX_NAME) return name
            val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION }
            return if (ext == null) name.take(MAX_NAME) else name.take(MAX_NAME - ext.length - 1) + "." + ext
        }
    }
}
