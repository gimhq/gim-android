package im.g.apps.testing

import android.app.Instrumentation
import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * Saves a screenshot of the device to `<app cache>/test-screenshots/<name>.png` at the moment a test step fails
 * (before `@After` changes the screen); scripts/run-real-device-tests.sh pulls the directory with `run-as`. Never
 * throws: a missing screenshot must not mask the test failure.
 */
fun saveFailureScreenshot(instrumentation: Instrumentation, name: String) {
    runCatching {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.cacheDir, SCREENSHOT_DIR).apply { mkdirs() }
        val file = File(dir, name.replace(UNSAFE, "_").take(MAX_NAME) + ".png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.w(TAG, "failure screenshot: ${file.absolutePath}")
    }.onFailure { Log.w(TAG, "failure screenshot not saved", it) }
}

private const val TAG = "FailureScreenshot"
private const val SCREENSHOT_DIR = "test-screenshots"
private const val MAX_NAME = 100
private val UNSAFE = Regex("[^A-Za-z0-9._-]+")
