package im.g.apps.testing

import android.app.Instrumentation
import android.os.SystemClock
import android.util.Log
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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

/**
 * Answers the system runtime-permission dialog with "While using the app" (or "Allow") when it shows within
 * [timeoutMs]; returns whether a dialog was answered. A permission granted earlier shows no dialog.
 */
fun allowPermissionDialog(instrumentation: Instrumentation, timeoutMs: Long = 10_000): Boolean {
    val button = UiDevice.getInstance(instrumentation).wait(Until.findObject(By.res(ALLOW_BUTTON)), timeoutMs) ?: return false
    Log.i(TAG, "allowing permission with '${button.text}'")
    button.click()
    return true
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
// English and Simplified Chinese (a zh-CN HyperOS phone) wording.
private val SYSTEM_DIALOG_TEXT =
    Pattern.compile(".*(isn.t responding|keeps stopping|has stopped|无响应|已停止|停止运行).*", Pattern.CASE_INSENSITIVE)
private val WAIT_TEXT = Pattern.compile("wait|等待", Pattern.CASE_INSENSITIVE)
// The permission controller's buttons keep these ids on AOSP and Google builds (whose package name differs).
private val ALLOW_BUTTON = Pattern.compile(".*:id/permission_allow(_foreground_only)?_button")
private val CLOSE_TEXT = Pattern.compile("close( app)?|关闭应用", Pattern.CASE_INSENSITIVE)
