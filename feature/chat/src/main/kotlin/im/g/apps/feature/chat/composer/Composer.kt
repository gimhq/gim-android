package im.g.apps.feature.chat.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.g.apps.core.designsystem.component.ReplyPreviewBar
import im.g.apps.feature.chat.R
import im.g.apps.feature.chat.timeline.asString

internal const val COMPOSER_INPUT_TEST_TAG = "composer-input"
private const val MAX_INPUT_LINES = 6

/**
 * Message input bar: reply or edit bar, attach menu, multiline field and send button. Stateless; see
 * [ComposerRoute] for the ViewModel-bound version.
 */
@Composable
fun Composer(
    state: ComposerUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancelMode: () -> Unit,
    onAttachPhoto: () -> Unit,
    onAttachFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Column {
            when (val mode = state.mode) {
                is ComposerMode.Replying -> ReplyPreviewBar(
                    senderName = mode.senderName,
                    snippet = mode.preview.asString(),
                    onClose = onCancelMode,
                )
                is ComposerMode.Editing -> EditingBar(mode.originalText, onCancelMode)
                ComposerMode.Normal -> Unit
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                if (state.mode !is ComposerMode.Editing) {
                    AttachButton(enabled = state.canAttach, onAttachPhoto = onAttachPhoto, onAttachFile = onAttachFile)
                }
                TextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text(stringResource(R.string.chat_composer_placeholder)) },
                    maxLines = MAX_INPUT_LINES,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(COMPOSER_INPUT_TEST_TAG),
                )
                val sendLabel = stringResource(
                    if (state.mode is ComposerMode.Editing) R.string.composer_save else R.string.composer_send,
                )
                IconButton(
                    onClick = onSend,
                    enabled = state.canSend,
                    modifier = Modifier.semantics { contentDescription = sendLabel },
                ) {
                    SendGlyph(LocalContentColor.current)
                }
            }
        }
    }
}

@Composable
private fun EditingBar(originalText: String, onClose: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
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
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(stringResource(R.string.composer_editing), color = accent, style = MaterialTheme.typography.labelLarge)
            Text(
                text = originalText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val closeLabel = stringResource(R.string.composer_cancel_edit)
        IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = closeLabel }) {
            CloseGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AttachButton(enabled: Boolean, onAttachPhoto: () -> Unit, onAttachFile: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.composer_attach)
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            AttachGlyph(LocalContentColor.current)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.composer_attach_photo)) },
                onClick = {
                    expanded = false
                    onAttachPhoto()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.composer_attach_file)) },
                onClick = {
                    expanded = false
                    onAttachFile()
                },
            )
        }
    }
}

@Composable
private fun SendGlyph(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.12f)
            lineTo(size.width * 0.95f, size.height * 0.5f)
            lineTo(size.width * 0.08f, size.height * 0.88f)
            lineTo(size.width * 0.22f, size.height * 0.5f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun AttachGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = size.minDimension * 0.1f
        val mid = size.width / 2
        drawLine(color, Offset(mid, size.height * 0.1f), Offset(mid, size.height * 0.9f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.1f, size.height / 2), Offset(size.width * 0.9f, size.height / 2), stroke, StrokeCap.Round)
    }
}

@Composable
private fun CloseGlyph(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val stroke = size.minDimension * 0.14f
        drawLine(color, Offset.Zero, Offset(size.width, size.height), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
    }
}
