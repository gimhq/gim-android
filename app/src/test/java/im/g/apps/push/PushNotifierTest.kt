package im.g.apps.push

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.MainActivity
import im.g.apps.core.bridge.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class PushNotifierTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notifier = AndroidPushNotifier(context)

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun spec_uses_a_channel_per_kind_the_chat_as_tag_and_the_collapse_id_as_id() {
        val group = checkNotNull(pushNotificationSpec(notificationContent()))
        assertEquals(PushChannel.MESSAGES, group.channel)
        assertEquals("chat-1", group.tag)
        assertEquals("m-1".hashCode(), group.id)
        assertEquals("Aegean crew", group.title)
        assertEquals("Peer: Anchored off Hydra", group.text)

        val direct = checkNotNull(pushNotificationSpec(notificationContent(chatTitle = "Peer")))
        assertEquals("Anchored off Hydra", direct.text)

        assertEquals(PushChannel.REACTIONS, pushNotificationSpec(notificationContent(kind = NotificationKind.REACTION))?.channel)
        assertEquals(PushChannel.INVITES, pushNotificationSpec(notificationContent(kind = NotificationKind.INVITE))?.channel)
        val generic = pushNotificationSpec(
            notificationContent(body = "New message", chatId = null, chatTitle = null, sender = null, collapseId = null, kind = NotificationKind.GENERIC),
        )
        assertEquals(PushChannel.OTHER, generic?.channel)
        assertNull(generic?.title)
        assertNull(pushNotificationSpec(notificationContent(kind = NotificationKind.MEMBERSHIP)))
        assertNull(pushNotificationSpec(notificationContent(shouldDisplay = false)))
    }

    @Test
    fun same_collapse_id_replaces_and_other_messages_stack() {
        notifier.show(notificationContent(body = "first", collapseId = "m-1"))
        notifier.show(notificationContent(body = "first again from hub B", collapseId = "m-1"))
        notifier.show(notificationContent(body = "second", collapseId = "m-2"))

        val active = manager.activeNotifications.sortedBy { it.id }
        assertEquals(2, active.size)
        assertEquals(setOf("chat-1"), active.map { it.tag }.toSet())
        val texts = active.map { it.notification.extras.getCharSequence("android.text").toString() }.toSet()
        assertEquals(setOf("Peer: first again from hub B", "Peer: second"), texts)
        assertEquals("messages", active.first().notification.channelId)
    }

    @Test
    fun tap_opens_the_chat_and_sign_out_clears_everything() {
        notifier.show(notificationContent())
        val intent = shadowOf(manager.activeNotifications.single().notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("chat-1", intent.getStringExtra(MainActivity.EXTRA_CHAT_ID))

        notifier.cancelAll()
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun nothing_is_shown_without_the_notification_permission() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.show(notificationContent())
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun ensure_channels_creates_every_channel_with_its_label_without_a_notification() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        ensurePushChannels(context)

        PushChannel.entries.forEach {
            assertEquals(context.getString(it.label), manager.getNotificationChannel(it.id)?.name)
        }
        assertEquals(0, manager.activeNotifications.size)
    }
}
