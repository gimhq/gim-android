package im.g.apps.feature.settings

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class NotificationsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val channels = listOf(NotificationChannelItem("messages", "Messages"), NotificationChannelItem("reactions", "Reactions"))

    private fun show(onBack: () -> Unit = {}) =
        rule.setContent { GimTheme { NotificationsRoute(channels = channels, onBack = onBack) } }

    @Test
    fun lists_each_channel_and_all_notifications() {
        show()

        rule.onNodeWithText("Choose sounds and alerts", substring = true).assertExists()
        rule.onNodeWithText("Messages").assertExists()
        rule.onNodeWithText("Reactions").assertExists()
        rule.onNodeWithText("All notifications").assertExists()
    }

    @Test
    fun a_channel_row_opens_its_system_settings() {
        show()

        rule.onNodeWithText("Reactions").performClick()

        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals("reactions", intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun all_notifications_opens_the_app_settings() {
        show()

        rule.onNodeWithText("All notifications").performClick()

        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertFalse(intent.hasExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun back_calls_its_callback() {
        var back = 0
        show(onBack = { back++ })

        rule.onNodeWithContentDescription("Go back").performClick()

        assertEquals(1, back)
    }
}
