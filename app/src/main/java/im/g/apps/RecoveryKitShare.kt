package im.g.apps

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Shared through the FileProvider's `recovery` cache path; deleted once onboarding finishes. */
private fun recoveryKitDir(context: Context): File = File(context.cacheDir, "gim/recovery")

/** Writes [kitText] to `gim-recovery-kit.txt` and offers it to any app through the share sheet with a read grant. */
fun shareRecoveryKit(context: Context, kitText: String) {
    val file = File(recoveryKitDir(context).apply { mkdirs() }, "gim-recovery-kit.txt")
    file.writeText(kitText)
    val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

/** Removes kit files written by [shareRecoveryKit], so the key does not stay in the cache. */
fun deleteRecoveryKitFiles(context: Context) {
    recoveryKitDir(context).deleteRecursively()
}
