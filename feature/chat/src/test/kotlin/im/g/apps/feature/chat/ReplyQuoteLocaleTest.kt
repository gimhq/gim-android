package im.g.apps.feature.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.MessageContent
import im.g.apps.core.bridge.MessageItem
import im.g.apps.core.bridge.PreviewKind
import im.g.apps.core.bridge.ReplyPreview
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.designsystem.theme.GimTheme
import im.g.apps.feature.chat.timeline.ChatHeaderUi
import im.g.apps.feature.chat.timeline.ChatUiState
import im.g.apps.feature.chat.timeline.TimelineMapper
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.Locale

/** Reply quotes render [PreviewKind] in the app's language, never gim-core's English `text`. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es")
class ReplyQuoteLocaleTest {
    @get:Rule
    val rule = createComposeRule()

    private val ana = UserProfile("u-ana", "Ana Kovač")

    private fun reply(id: String, text: String, kind: PreviewKind) = TimelineItem.Message(
        MessageItem(
            messageId = id,
            chatId = "c1",
            sender = ana,
            isOwn = false,
            sentAtMs = 1_760_000_000_000L,
            content = MessageContent.Text("reply $id"),
            replyTo = ReplyPreview("q-$id", "Ana Kovač", text, kind),
            reactions = emptyList(),
            edited = false,
            sendState = null,
        ),
    )

    @Test
    fun replyQuotes_renderEachKindInSpanish() {
        val items = listOf(
            reply("m1", "Photo", PreviewKind.Photo),
            reply("m2", "File: informe.pdf", PreviewKind.File("informe.pdf")),
            reply("m3", "File", PreviewKind.File(null)),
            reply("m4", "Message deleted", PreviewKind.Deleted),
            reply("m5", "Nos vemos a las nueve", PreviewKind.Text),
        )
        val rows = TimelineMapper(ZoneId.of("UTC"), Locale("es"))
            .map(items, isGroup = false, emptyMap(), 1_760_000_100_000L, emptySet())
        rule.setContent {
            GimTheme {
                ChatScreen(
                    state = ChatUiState(header = ChatHeaderUi(title = "Ana Kovač"), items = rows.toImmutableList()),
                    composer = {},
                    onBack = {},
                    onOpenGroupInfo = {},
                    onLoadOlder = {},
                    onVisible = {},
                    onRetry = {},
                    onImageNeeded = {},
                    onOpenFile = {},
                    onMessageLongPress = {},
                    onRetryMedia = {},
                    onReply = {},
                    onToggleReaction = { _, _ -> },
                    onUnblock = {},
                )
            }
        }

        // Newest first; the small test screen fits two bubbles, so scroll each quote into view.
        val expected = listOf("Nos vemos a las nueve", "Mensaje eliminado", "Archivo", "Archivo: informe.pdf", "Foto")
        expected.forEachIndexed { index, text ->
            rule.onNodeWithTag(TIMELINE_TEST_TAG).performScrollToIndex(index)
            rule.onNodeWithText(text, useUnmergedTree = true).assertExists()
        }
        for (english in listOf("Photo", "File: informe.pdf", "File", "Message deleted")) {
            rule.onNodeWithText(english, useUnmergedTree = true).assertDoesNotExist()
        }
    }
}
