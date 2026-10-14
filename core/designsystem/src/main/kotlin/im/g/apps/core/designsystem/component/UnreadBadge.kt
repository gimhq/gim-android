package im.g.apps.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import im.g.apps.core.designsystem.R
import im.g.apps.core.designsystem.theme.GimTheme

internal const val UNREAD_BADGE_CAP = 99

@Composable
fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val colors = GimTheme.colors
    val overflow = count > UNREAD_BADGE_CAP
    val label = if (overflow) "$UNREAD_BADGE_CAP+" else count.toString()
    val countDescription = if (overflow) {
        stringResource(R.string.gim_unread_overflow)
    } else {
        pluralStringResource(R.plurals.gim_unread_count, count, count)
    }
    val description = if (muted) stringResource(R.string.gim_unread_count_muted, countDescription) else countDescription
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = description }
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(if (muted) colors.unreadBadgeMuted else colors.unreadBadge, CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (muted) colors.onUnreadBadgeMuted else colors.onUnreadBadge,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@GimPreviews
@Composable
private fun UnreadBadgePreview() {
    GimTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
            UnreadBadge(count = 3)
            UnreadBadge(count = 42, muted = true)
            UnreadBadge(count = 250)
        }
    }
}
