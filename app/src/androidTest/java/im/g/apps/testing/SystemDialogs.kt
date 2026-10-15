package im.g.apps.testing

import android.app.Instrumentation
import android.os.SystemClock
import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

/**
 * Waits up to [timeoutMs] for [appHasFocus], dismissing system "isn't responding" / "keeps stopping" dialogs on the
 * way: a slow software-rendered emulator often raises one for System UI or the launcher right after boot, and it
 * keeps the app window from getting focus. Prefers "Wait" over "Close app". Returns whether the app got focus; when
 * it did not, the window hierarchy is dumped to logcat under [TAG].
 */
fun dismissSystemDialogs(instrumentation: Instrumentation, timeoutMs: Long = 30_000, appHasFocus: () -> Boolean): Boolean {
    val device = UiDevice.getInstance(instrumentation)
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (true) {
        val button = dismissButton(device)
        if (button != null) {
            val message = device.findObject(By.text(SYSTEM_DIALOG_TEXT))?.text
            Log.w(TAG, "dismissing system dialog '$message' with '${button.text}'")
            button.click()
            SystemClock.sleep(POLL_MS)
            continue
        }
        if (appHasFocus()) return true
        if (SystemClock.elapsedRealtime() >= deadline) break
        SystemClock.sleep(POLL_MS)
    }
    Log.w(TAG, "app window has no focus after $timeoutMs ms; window hierarchy:")
    val dump = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString()
    dump.lines().forEach { Log.w(TAG, it) }
    return false
}

private fun dismissButton(device: UiDevice) =
    device.findObject(By.res("android", "aerr_wait"))
        ?: device.findObject(By.res("android", "aerr_close"))
        ?: if (device.hasObject(By.text(SYSTEM_DIALOG_TEXT))) {
            device.findObject(By.text(WAIT_TEXT)) ?: device.findObject(By.text(CLOSE_TEXT))
        } else {
            null
        }

private const val TAG = "SystemDialogs"
private const val POLL_MS = 500L
private val SYSTEM_DIALOG_TEXT = Pattern.compile(".*(isn.t responding|keeps stopping|has stopped).*", Pattern.CASE_INSENSITIVE)
private val WAIT_TEXT = Pattern.compile("wait", Pattern.CASE_INSENSITIVE)
private val CLOSE_TEXT = Pattern.compile("close( app)?", Pattern.CASE_INSENSITIVE)
