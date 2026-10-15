package im.g.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Authority of the FileProvider declared in the manifest as `${applicationId}.files`. */
fun fileProviderAuthority(context: Context): String = "${context.packageName}.files"

/**
 * Opens [path] (under the media cache dir) with an ACTION_VIEW chooser-less intent and a read grant.
 * Returns false when no app can handle it or the file is outside the shared paths.
 */
fun openFile(context: Context, path: String, mime: String): Boolean {
    val uri = try {
        FileProvider.getUriForFile(context, fileProviderAuthority(context), File(path))
    } catch (_: IllegalArgumentException) {
        return false
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
