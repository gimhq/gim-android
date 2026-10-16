package im.g.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import im.g.apps.core.bridge.LocalTimeZone
import java.util.TimeZone

/**
 * The device time zone for gim-core's date separators. gim-core calls it on a Rust thread, so it only reads
 * [TimeZone.getDefault] (which Android resets on a zone change) and never touches the client.
 */
internal object SystemLocalTimeZone : LocalTimeZone {
    override fun utcOffsetSecs(atMs: Long): Int = TimeZone.getDefault().getOffset(atMs) / 1000
}

/** Calls [onChanged] on every `ACTION_TIMEZONE_CHANGED` for the life of the process. */
internal fun registerTimeZoneChangedReceiver(context: Context, onChanged: () -> Unit) {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) onChanged()
        }
    }
    ContextCompat.registerReceiver(
        context.applicationContext,
        receiver,
        IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
}
