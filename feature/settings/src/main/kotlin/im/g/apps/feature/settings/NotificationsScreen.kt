package im.g.apps.feature.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import im.g.apps.core.designsystem.component.GimPreviews
import im.g.apps.core.designsystem.theme.GimTheme

/** A notification channel of the app, as listed on [NotificationsScreen]. */
data class NotificationChannelItem(val id: String, val label: String)

/** [NotificationsScreen] opening the phone's settings; every channel in [channels] must already exist. */
@Composable
fun NotificationsRoute(channels: List<NotificationChannelItem>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    NotificationsScreen(
        channels = channels,
        onBack = onBack,
        onOpenChannel = { context.startActivity(channelNotificationSettingsIntent(context.packageName, it)) },
        onOpenAll = { context.startActivity(appNotificationSettingsIntent(context.packageName)) },
        modifier = modifier,
    )
}

/** One row per channel plus "All notifications"; the phone's settings choose sounds and alerts. */
@Composable
fun NotificationsScreen(
    channels: List<NotificationChannelItem>,
    onBack: () -> Unit,
    onOpenChannel: (channelId: String) -> Unit,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackScaffold(title = stringResource(R.string.notifications_title), onBack = onBack, modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.notifications_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            channels.forEach { channel ->
                SettingsRow(title = channel.label, onClick = { onOpenChannel(channel.id) })
            }
            HorizontalDivider()
            SettingsRow(title = stringResource(R.string.notifications_all), onClick = onOpenAll)
        }
    }
}

internal fun channelNotificationSettingsIntent(packageName: String, channelId: String): Intent =
    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)

internal fun appNotificationSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

@GimPreviews
@Composable
private fun NotificationsScreenPreview() {
    GimTheme {
        NotificationsScreen(
            channels = listOf(NotificationChannelItem("messages", "Messages"), NotificationChannelItem("reactions", "Reactions")),
            onBack = {},
            onOpenChannel = {},
            onOpenAll = {},
        )
    }
}
