package im.g.apps.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.g.apps.core.designsystem.R
import im.g.apps.core.designsystem.theme.GimTheme

/** Quoted message preview above the composer, or inside a bubble when [onClose] is null. */
@Composable
fun ReplyPreviewBar(
    senderName: String,
    snippet: String,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.gim_reply_to, senderName, snippet)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent, RoundedCornerShape(2.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Text(
                text = senderName,
                color = accent,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = snippet,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClose != null) {
            val closeLabel = stringResource(R.string.gim_reply_close)
            IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                CloseGlyph(color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@GimPreviews
@Composable
private fun ReplyPreviewBarPreview() {
    GimTheme {
        Column {
            ReplyPreviewBar(senderName = "Nikos", snippet = "Anchored off Paxos, swim at noon?", onClose = {})
            ReplyPreviewBar(senderName = "Nikos", snippet = "Read-only quote", onClose = null)
        }
    }
}
