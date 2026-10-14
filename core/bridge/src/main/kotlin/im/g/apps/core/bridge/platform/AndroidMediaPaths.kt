package im.g.apps.core.bridge.platform

import android.content.Context
import java.io.File

/** Destination paths for `fetchMedia` / `fetchThumbnail` under `cacheDir/gim/media`. */
class AndroidMediaPaths(context: Context) {
    val root: File = File(context.applicationContext.cacheDir, "gim/media")

    /** `media/<chat>/<message>[.thumb]`; the chat directory is created on demand. */
    fun destFor(chatId: String, messageId: String, thumbnail: Boolean): String {
        val chatDir = File(root, safeName(chatId)).apply { mkdirs() }
        val name = safeName(messageId) + if (thumbnail) ".thumb" else ""
        return File(chatDir, name).absolutePath
    }

    /**
     * Keeps `[A-Za-z0-9_-]` (so no path separators or dot segments); ids that had to be altered get
     * a hash suffix so two different ids never map to the same file.
     */
    private fun safeName(id: String): String {
        val cleaned = id.replace(UNSAFE, "_").take(MAX_NAME)
        return if (cleaned == id && cleaned.isNotEmpty()) cleaned else "$cleaned-${sha256Hex(id).take(16)}"
    }

    private companion object {
        val UNSAFE = Regex("[^A-Za-z0-9_-]")
        const val MAX_NAME = 64
    }
}
