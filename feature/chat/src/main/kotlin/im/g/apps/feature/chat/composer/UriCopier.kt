package im.g.apps.feature.chat.composer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Result of [UriCopier.copy]. */
sealed interface CopyResult {
    /** [path] ends with the sanitized display name, so gim-core takes the file name from it. */
    data class Copied(val path: String, val mime: String) : CopyResult

    /** The content is larger than the cap; nothing was left on disk. */
    data object TooLarge : CopyResult
}

/** Copies picked content into app storage so it can be handed to gim-core by path. Blocking. */
interface UriCopier {
    /**
     * Copies [uri] to `outgoing/<uuid>/<display name>`. Returns [CopyResult.TooLarge] when the content
     * exceeds [maxBytes]; throws [IOException] or [SecurityException] when it cannot be read.
     */
    fun copy(uri: String, maxBytes: Long): CopyResult
}

/** [UriCopier] over a [android.content.ContentResolver], copying into `cacheDir/outgoing`. */
class AndroidUriCopier(context: Context) : UriCopier {
    private val resolver = context.applicationContext.contentResolver
    private val outgoingDir = File(context.cacheDir, "outgoing")

    override fun copy(uri: String, maxBytes: Long): CopyResult {
        val parsed = Uri.parse(uri)
        var name: String? = null
        var size: Long? = null
        resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        if ((size ?: 0) > maxBytes) return CopyResult.TooLarge
        val mime = resolver.getType(parsed) ?: DEFAULT_MIME
        val dir = File(outgoingDir, UUID.randomUUID().toString())
        val target = File(dir, sanitizeFileName(name ?: parsed.lastPathSegment))
        try {
            if (!dir.mkdirs()) throw IOException("cannot create $dir")
            val input = resolver.openInputStream(parsed) ?: throw IOException("cannot open $uri")
            val fits = input.use { source -> target.outputStream().use { copyCapped(source, it, maxBytes) } }
            if (!fits) {
                dir.deleteRecursively()
                return CopyResult.TooLarge
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        return CopyResult.Copied(target.path, mime)
    }

    private companion object {
        const val DEFAULT_MIME = "application/octet-stream"
    }
}

/** Copies [input] to [output]; returns false as soon as more than [maxBytes] were read. */
internal fun copyCapped(input: InputStream, output: OutputStream, maxBytes: Long): Boolean {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return true
        total += read
        if (total > maxBytes) return false
        output.write(buffer, 0, read)
    }
}

/** A single safe path segment derived from a provider's display name. */
internal fun sanitizeFileName(name: String?): String {
    val cleaned = name.orEmpty()
        .map { if (it == '/' || it == '\\' || it == ':' || it.isISOControl()) '_' else it }
        .joinToString("")
        .trim()
        .trimStart('.')
    if (cleaned.isEmpty()) return FALLBACK_FILE_NAME
    if (cleaned.length <= MAX_FILE_NAME_LENGTH) return cleaned
    val extension = cleaned.substringAfterLast('.', "").take(MAX_EXTENSION_LENGTH)
    val keep = MAX_FILE_NAME_LENGTH - if (extension.isEmpty()) 0 else extension.length + 1
    return cleaned.take(keep) + if (extension.isEmpty()) "" else ".$extension"
}

private const val FALLBACK_FILE_NAME = "file"
private const val MAX_FILE_NAME_LENGTH = 128
private const val MAX_EXTENSION_LENGTH = 16
