package im.g.apps.interop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail

/**
 * Handshake with scripts/e2e-interop.sh, which drives the CLI side.
 *
 * - test -> script: [mark] logs `STEP <name>` under logcat tag [TAG]; the script follows that tag.
 * - script -> test: `adb shell am broadcast -a im.g.apps.interop.GO -p im.g.apps --es step <name>`;
 *   [await] blocks until that step arrived (signals sent before the wait are kept).
 */
internal class Rendezvous(private val context: Context) : AutoCloseable {
    private val latches = ConcurrentHashMap<String, CountDownLatch>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(EXTRA_STEP)?.let { latch(it).countDown() }
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_GO), ContextCompat.RECEIVER_EXPORTED)
    }

    fun mark(step: String) {
        Log.i(TAG, "STEP $step")
    }

    fun await(step: String, timeoutMs: Long) {
        if (!latch(step).await(timeoutMs, TimeUnit.MILLISECONDS)) fail("no '$step' signal from the script")
    }

    override fun close() {
        context.unregisterReceiver(receiver)
    }

    private fun latch(step: String): CountDownLatch = latches.computeIfAbsent(step) { CountDownLatch(1) }

    companion object {
        const val TAG = "GimInterop"
        const val ACTION_GO = "im.g.apps.interop.GO"
        const val EXTRA_STEP = "step"
    }
}
