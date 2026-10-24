package im.g.apps.feature.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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

    private val ana = UserProfile("u-ana", "ana", "Ana Kovač")

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

        rule.onNodeWithText("Foto").assertExists()
        rule.onNodeWithText("Archivo: informe.pdf").assertExists()
        rule.onNodeWithText("Archivo").assertExists()
        rule.onNodeWithText("Mensaje eliminado").assertExists()
        rule.onNodeWithText("Nos vemos a las nueve").assertExists()
        rule.onNodeWithText("Photo").assertDoesNotExist()
        rule.onNodeWithText("File: informe.pdf").assertDoesNotExist()
        rule.onNodeWithText("Message deleted").assertDoesNotExist()
    }
}
