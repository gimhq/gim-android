package im.g.apps.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import im.g.apps.core.designsystem.theme.GimTheme

/**
 * Screen top bar. [leading] sits before the title (e.g. an avatar). With [onTitleClick] the leading
 * slot, title and subtitle form one button. The bar grows with the font scale instead of clipping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GimTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
) {
    val titleStyle = if (subtitle == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
    val subtitleStyle = MaterialTheme.typography.bodySmall
    // TopAppBar has a fixed height; size it to fit the text at the current font scale.
    val textHeight = with(LocalDensity.current) {
        titleStyle.lineHeight.toDp() + if (subtitle != null) subtitleStyle.lineHeight.toDp() else 0.dp
    }
    TopAppBar(
        title = {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .then(
                        if (onTitleClick != null) {
                            Modifier.clickable(role = Role.Button, onClick = onTitleClick)
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                leading?.invoke()
                Column {
                    Text(text = title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = subtitleStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        modifier = modifier,
        navigationIcon = { navigationIcon?.invoke() },
        actions = actions,
        expandedHeight = max(TopAppBarDefaults.TopAppBarExpandedHeight, textHeight + 16.dp),
    )
}

/** Top bar icon button with a 48 dp touch target and [contentDescription] for TalkBack. */
@Composable
fun GimTopBarIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        enabled = enabled,
        content = content,
    )
}

@GimPreviews
@Composable
private fun GimTopBarPreview() {
    GimTheme {
        GimTopBar(
            title = "Ada Lovelace",
            subtitle = "Online",
            navigationIcon = {
                GimTopBarIconButton(onClick = {}, contentDescription = "Back") {
                    CloseGlyph(LocalContentColor.current)
                }
            },
            leading = { Avatar(name = "Ada Lovelace", imagePath = null, size = 40.dp) },
            onTitleClick = {},
        )
    }
}
