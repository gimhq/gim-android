package im.g.apps.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import im.g.apps.core.designsystem.component.Avatar
import im.g.apps.core.designsystem.component.DateSeparator
import im.g.apps.core.designsystem.component.MessageBubble
import im.g.apps.core.designsystem.component.ReactionChips
import im.g.apps.core.designsystem.component.ReplyPreviewBar
import im.g.apps.core.designsystem.component.StatusTicks
import im.g.apps.core.designsystem.component.TypingIndicator
import im.g.apps.feature.chat.timeline.ChatHeaderUi
import im.g.apps.feature.chat.timeline.ChatUiState
import im.g.apps.feature.chat.timeline.DateSeparatorRow
import im.g.apps.feature.chat.timeline.FileUi
import im.g.apps.feature.chat.timeline.ImageUi
import im.g.apps.feature.chat.timeline.MessageRow
import im.g.apps.feature.chat.timeline.SystemRow
import im.g.apps.feature.chat.timeline.asString
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

internal const val TIMELINE_TEST_TAG = "chat-timeline"
private const val LOADING_OLDER_KEY = "loading-older"
private const val LOAD_OLDER_THRESHOLD = 10
private const val MESSAGE_CONTENT_TYPE_PREFIX = "message-"

/**
 * Chat timeline. [state] rows are newest first and rendered with `reverseLayout`, so the newest message
 * sits at the bottom. [composer] is the slot for the message composer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    composer: @Composable () -> Unit,
    onBack: () -> Unit,
    onOpenGroupInfo: () -> Unit,
    onLoadOlder: () -> Unit,
    onVisible: (newestVisibleMessageId: String) -> Unit,
    onRetry: (messageId: String) -> Unit,
    onImageNeeded: (messageId: String) -> Unit,
    onOpenFile: (messageId: String) -> Unit,
    onMessageLongPress: (messageId: String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { ChatHeader(state.header) },
                navigationIcon = {
                    val description = stringResource(R.string.chat_back)
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = description }) {
                        BackGlyph(LocalContentColor.current)
                    }
                },
                actions = {
                    if (state.header.isGroup) {
                        val description = stringResource(R.string.chat_group_info)
                        IconButton(
                            onClick = onOpenGroupInfo,
                            modifier = Modifier.semantics { contentDescription = description },
                        ) {
                            InfoGlyph(LocalContentColor.current)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // The only IME inset in the chat screen; the Scaffold's bottom bar inset is part of it.
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Timeline(
                    state = state,
                    listState = listState,
                    onLoadOlder = onLoadOlder,
                    onVisible = onVisible,
                    onRetry = onRetry,
                    onImageNeeded = onImageNeeded,
                    onOpenFile = onOpenFile,
                    onMessageLongPress = onMessageLongPress,
                )
                JumpToBottom(
                    listState = listState,
                    unreadCount = state.unreadCount,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
            }
            state.typingLabel?.let {
                TypingIndicator(label = it.asString(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            // Composer slot: the composer task supplies the real input bar.
            composer()
        }
    }
}

/** Minimal stand-in for the composer slot until the composer task provides one. */
@Composable
fun ComposerPlaceholder(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Text(
            text = stringResource(R.string.chat_composer_placeholder),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun ChatHeader(header: ChatHeaderUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(name = header.title, imagePath = header.avatarPath, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(header.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            header.subtitle?.let {
                Text(
                    text = it.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Timeline(
    state: ChatUiState,
    listState: LazyListState,
    onLoadOlder: () -> Unit,
    onVisible: (String) -> Unit,
    onRetry: (String) -> Unit,
    onImageNeeded: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onMessageLongPress: (String) -> Unit,
) {
    val currentState by rememberUpdatedState(state)
    val currentOnLoadOlder by rememberUpdatedState(onLoadOlder)
    val currentOnVisible by rememberUpdatedState(onVisible)

    // Load older history when the oldest rows (the end of a reversed list) come within reach.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearEnd = info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - LOAD_OLDER_THRESHOLD
            nearEnd to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (nearEnd, _) ->
                if (nearEnd && currentState.hasMoreOlder && !currentState.isLoadingOlder) currentOnLoadOlder()
            }
    }

    // Report the newest visible message while resumed, for read receipts.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(listState, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { (it.contentType as? String)?.startsWith(MESSAGE_CONTENT_TYPE_PREFIX) == true }
                    ?.key as? String
            }
                .distinctUntilChanged()
                .collect { key -> if (key != null) currentOnVisible(key) }
        }
    }

    // Keep following new messages while the bottom is in view.
    val newestKey = state.items.firstOrNull()?.key
    LaunchedEffect(newestKey) {
        if (newestKey != null && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag(TIMELINE_TEST_TAG),
    ) {
        items(state.items, key = { it.key }, contentType = { it.contentType }) { row ->
            when (row) {
                is MessageRow -> MessageRowItem(
                    row = row,
                    onRetry = onRetry,
                    onImageNeeded = onImageNeeded,
                    onOpenFile = onOpenFile,
                    onLongPress = onMessageLongPress,
                )
                is DateSeparatorRow -> DateSeparator(label = row.label.asString())
                is SystemRow -> Text(
                    text = row.text.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }
        }
        if (state.isLoadingOlder) {
            item(key = LOADING_OLDER_KEY, contentType = LOADING_OLDER_KEY) {
                val description = stringResource(R.string.chat_loading_older)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .semantics { contentDescription = description },
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRowItem(
    row: MessageRow,
    onRetry: (String) -> Unit,
    onImageNeeded: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val clickLabel = row.file?.let { stringResource(R.string.chat_file_open, it.name) }
    MessageBubble(
        outgoing = row.outgoing,
        groupedWithPrevious = row.groupedWithPrevious,
        groupedWithNext = row.groupedWithNext,
        footer = { MessageFooter(row) },
        modifier = Modifier.combinedClickable(
            onClickLabel = clickLabel,
            onLongClick = { onLongPress(row.key) },
            onClick = {
                when {
                    row.failed -> onRetry(row.key)
                    row.file != null -> onOpenFile(row.key)
                }
            },
        ),
    ) {
        row.senderName?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        row.replyPreview?.let {
            ReplyPreviewBar(senderName = it.senderName, snippet = it.text, onClose = null)
        }
        row.image?.let { MessageImage(row.key, it, onImageNeeded) }
        row.file?.let { FileAttachment(it) }
        when {
            row.deleted -> Text(stringResource(R.string.chat_message_deleted), fontStyle = FontStyle.Italic)
            row.unsupported -> Text(stringResource(R.string.chat_message_unsupported), fontStyle = FontStyle.Italic)
            row.body != null -> Text(row.body)
        }
        if (row.failed) {
            Text(
                text = stringResource(R.string.chat_message_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (row.reactions.isNotEmpty()) {
            ReactionChips(reactions = row.reactions, onClick = {}, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun MessageFooter(row: MessageRow) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (row.edited) Text(stringResource(R.string.chat_message_edited), style = MaterialTheme.typography.labelSmall)
        Text(row.time, style = MaterialTheme.typography.labelSmall)
        row.tick?.let { StatusTicks(state = it, size = 14.dp) }
    }
}

/** Box sized from the known aspect ratio before the file arrives, so the list does not jump. */
@Composable
private fun MessageImage(messageId: String, image: ImageUi, onImageNeeded: (String) -> Unit) {
    val missing = image.path == null
    LaunchedEffect(messageId, missing) {
        if (missing) onImageNeeded(messageId)
    }
    Box(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .widthIn(max = 260.dp)
            .fillMaxWidth()
            .aspectRatio(image.aspectRatio)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        image.path?.let {
            AsyncImage(
                model = File(it),
                contentDescription = stringResource(R.string.chat_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun FileAttachment(file: FileUi) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(file.typeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(file.sizeLabel, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun JumpToBottom(listState: LazyListState, unreadCount: Int, modifier: Modifier = Modifier) {
    val scrolledAway by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    if (!scrolledAway) return
    val scope = rememberCoroutineScope()
    val description = stringResource(R.string.chat_jump_to_bottom)
    BadgedBox(
        modifier = modifier,
        badge = { if (unreadCount > 0) Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) } },
    ) {
        SmallFloatingActionButton(
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            ChevronDownGlyph(LocalContentColor.current)
        }
    }
}

@Composable
private fun BackGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = size.minDimension * 0.1f
        val midY = size.height / 2
        drawLine(color, Offset(size.width * 0.1f, midY), Offset(size.width * 0.9f, midY), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.1f, midY), Offset(size.width * 0.45f, size.height * 0.15f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.1f, midY), Offset(size.width * 0.45f, size.height * 0.85f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun InfoGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = size.minDimension * 0.1f
        drawCircle(color, radius = size.minDimension * 0.45f, style = Stroke(stroke))
        val x = size.width / 2
        drawLine(color, Offset(x, size.height * 0.45f), Offset(x, size.height * 0.72f), stroke, StrokeCap.Round)
        drawCircle(color, radius = stroke * 0.7f, center = Offset(x, size.height * 0.3f))
    }
}

@Composable
private fun ChevronDownGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = size.minDimension * 0.12f
        drawLine(color, Offset(size.width * 0.15f, size.height * 0.35f), Offset(size.width / 2, size.height * 0.7f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.85f, size.height * 0.35f), Offset(size.width / 2, size.height * 0.7f), stroke, StrokeCap.Round)
    }
}
