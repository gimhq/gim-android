package im.g.apps.testing

import android.app.Activity
import android.app.Instrumentation
import android.view.WindowManager

/**
 * Keeps the screen on while [activity]'s window is visible, so a phone's screen-off timeout (60 s on the test
 * phone) cannot lock the screen while a test waits on the network; nothing changes once the window is gone. A window
 * flag of the app under test, not a device setting.
 */
fun keepScreenOn(instrumentation: Instrumentation, activity: Activity) {
    instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
}
