package im.g.apps.feature.contacts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.displayLabel
import im.g.apps.core.bridge.shortUserId
import im.g.apps.core.designsystem.component.Avatar
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * Handle search used by new chat, new group and the add-member dialog: a text field plus the result of
 * the lookup, with its photo from [photos] (by user id). [resultAction] renders the trailing action of a found user
 * (e.g. "Start chat", "Add").
 */
@Composable
internal fun UserSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    state: UserSearchState,
    photos: ImmutableMap<String, String>,
    modifier: Modifier = Modifier,
    resultAction: @Composable (UserProfile) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.contacts_search_label)) },
            placeholder = { Text(stringResource(R.string.contacts_search_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Search,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        when (state) {
            UserSearchState.Idle -> SearchNote(stringResource(R.string.contacts_search_hint))
            UserSearchState.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                SearchNote(stringResource(R.string.contacts_search_searching))
            }
            is UserSearchState.Found -> UserRow(
                user = state.user,
                photoPath = photos[state.user.userId],
                trailing = { resultAction(state.user) },
            )
            is UserSearchState.NotFound -> SearchNote(stringResource(R.string.contacts_search_not_found, state.handle))
            is UserSearchState.Error -> Text(
                text = state.error.message(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SearchNote(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Avatar (the photo at [photoPath], else initials; decorative, the name is read instead), display name (with an
 * optional "You" marker) and the handle, or a short user id for users without one, plus a trailing slot.
 */
@Composable
internal fun UserRow(
    user: UserProfile,
    modifier: Modifier = Modifier,
    photoPath: String? = null,
    isSelf: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = user.displayLabel, imagePath = photoPath, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            val name = if (isSelf) "${user.displayLabel} (${stringResource(R.string.contacts_you)})" else user.displayLabel
            Text(text = name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            userSubtitle(user)?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/**
 * The handle of [user]; handles are optional (empty until claimed, and remote users may have none), so without one
 * the short user id, unless the name already shows it.
 */
@Composable
internal fun userSubtitle(user: UserProfile): String? = when {
    user.handle.isNotEmpty() -> stringResource(R.string.contacts_handle, user.handle)
    user.displayLabel == shortUserId(user.userId) -> null
    else -> shortUserId(user.userId)
}

@Composable
internal fun BackButton(onClick: () -> Unit) {
    val description = stringResource(R.string.contacts_back)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        BackGlyph(LocalContentColor.current)
    }
}

/** Icon button with a decorative "x"; [description] names the action for accessibility. */
@Composable
internal fun RemoveButton(description: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        CrossGlyph(LocalContentColor.current)
    }
}

@Composable
private fun BackGlyph(color: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val stroke = size.minDimension * 0.12f
        val midY = size.height / 2
        drawLine(color, Offset(size.width, midY), Offset(0f, midY), stroke, StrokeCap.Round)
        drawLine(color, Offset(0f, midY), Offset(size.width * 0.45f, 0f), stroke, StrokeCap.Round)
        drawLine(color, Offset(0f, midY), Offset(size.width * 0.45f, size.height), stroke, StrokeCap.Round)
    }
}

@Composable
internal fun CrossGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(12.dp)) {
        val stroke = size.minDimension * 0.16f
        drawLine(color, Offset.Zero, Offset(size.width, size.height), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
    }
}

@Preview
@Composable
private fun UserSearchFieldPreview() {
    GimTheme {
        Surface {
            UserSearchField(
                query = "ana",
                onQueryChange = {},
                state = UserSearchState.Found(UserProfile("u-ana", "ana", "Ana Kovač")),
                photos = persistentMapOf(),
                modifier = Modifier.padding(16.dp),
            ) { TextButton(onClick = {}) { Text(stringResource(R.string.contacts_start_chat)) } }
        }
    }
}
