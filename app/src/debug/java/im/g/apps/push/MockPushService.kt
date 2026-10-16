package im.g.apps.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import im.g.apps.R

/**
 * Debug only: a foreground service with nothing to do but exist while [MockPushProvider] polls, so the backgrounded
 * process is neither frozen nor killed as a cached app. It is started while the app is in the foreground (sign-in);
 * a start the system refuses only logs, and polling goes on while the process lives.
 */
class MockPushService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_MIN)
                .setName(getString(R.string.push_mock_channel))
                .build(),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.push_mock_polling))
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot run in the foreground; polling while the process lives", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "MockPushService"
        private const val CHANNEL = "push_mock"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                context.startService(Intent(context, MockPushService::class.java))
            } catch (e: IllegalStateException) {
                // Background start restrictions: the app was not in the foreground.
                Log.w(TAG, "Cannot start the push mock service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MockPushService::class.java))
        }
    }
}
