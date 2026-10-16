package im.g.apps.push

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import im.g.apps.MainActivity
import im.g.apps.R
import im.g.apps.core.bridge.NotificationContent
import im.g.apps.core.bridge.NotificationKind

/** Shows the notifications [PushController] decides on. */
interface PushNotifier {
    fun show(content: NotificationContent)

    /** Removes every notification shown for a push (sign-out). */
    fun cancelAll()
}

/** One notification channel per displayed [NotificationKind]; [NotificationKind.MEMBERSHIP] is never displayed. */
enum class PushChannel(val id: String, @StringRes val label: Int, val importance: Int) {
    MESSAGES("messages", R.string.push_channel_messages, NotificationManagerCompat.IMPORTANCE_HIGH),
    REACTIONS("reactions", R.string.push_channel_reactions, NotificationManagerCompat.IMPORTANCE_DEFAULT),
    INVITES("invites", R.string.push_channel_invites, NotificationManagerCompat.IMPORTANCE_HIGH),
    OTHER("other", R.string.push_channel_other, NotificationManagerCompat.IMPORTANCE_DEFAULT),
}

/**
 * What to post for [NotificationContent]. Android identifies a notification by (tag, id): the tag is the chat, the id
 * the collapse id, so the same message pushed by several hubs (same collapse id) replaces one notification while
 * different messages of a chat stack.
 */
data class PushNotificationSpec(
    val channel: PushChannel,
    val tag: String,
    val id: Int,
    val title: String?,
    val text: String,
    val chatId: String?,
    val timestamp: Long,
)

/** The notification for [content]; null when it must not be displayed. */
fun pushNotificationSpec(content: NotificationContent): PushNotificationSpec? {
    if (!content.shouldDisplay) return null
    val channel = when (content.kind) {
        NotificationKind.MESSAGE -> PushChannel.MESSAGES
        NotificationKind.REACTION -> PushChannel.REACTIONS
        NotificationKind.INVITE -> PushChannel.INVITES
        NotificationKind.GENERIC -> PushChannel.OTHER
        NotificationKind.MEMBERSHIP -> return null
    }
    val sender = content.senderDisplayName?.takeIf { it.isNotBlank() && it != content.chatTitle }
    // A group shows "Sender: body" under the group name; a direct chat's title already is the sender.
    val text = if (sender != null && content.chatTitle != null) "$sender: ${content.body}" else content.body
    return PushNotificationSpec(
        channel = channel,
        tag = content.chatId ?: NO_CHAT_TAG,
        id = (content.collapseId ?: content.messageId)?.hashCode() ?: 0,
        title = content.chatTitle ?: sender,
        text = text,
        chatId = content.chatId,
        timestamp = content.timestamp,
    )
}

private const val NO_CHAT_TAG = "gim"

/** Posts push notifications; tapping one opens its chat in [MainActivity] ([MainActivity.EXTRA_CHAT_ID]). */
class AndroidPushNotifier(context: Context) : PushNotifier {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    override fun show(content: NotificationContent) {
        val spec = pushNotificationSpec(content) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notification permission not granted; push not shown")
            return
        }
        manager.createNotificationChannelsCompat(
            PushChannel.entries.map {
                NotificationChannelCompat.Builder(it.id, it.importance).setName(context.getString(it.label)).build()
            },
        )
        val notification = NotificationCompat.Builder(context, spec.channel.id)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(spec.title ?: context.getString(R.string.app_name))
            .setContentText(spec.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.text))
            .setWhen(spec.timestamp)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent(spec))
            .build()
        manager.notify(spec.tag, spec.id, notification)
    }

    override fun cancelAll() {
        manager.activeNotifications.filter { it.tag != null }.forEach { manager.cancel(it.tag, it.id) }
    }

    private fun openIntent(spec: PushNotificationSpec): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { spec.chatId?.let { putExtra(MainActivity.EXTRA_CHAT_ID, it) } }
        return PendingIntent.getActivity(
            context,
            spec.tag.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val TAG = "PushNotifier"
    }
}
