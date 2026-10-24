package im.g.apps.feature.chatlist

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.PreviewKind
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Chat list rows render [PreviewKind] in the app's language, never gim-core's English `text`. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "zh-rCN")
class ChatPreviewKindTest {
    @get:Rule
    val rule = createComposeRule()

    private fun row(id: String, sender: PreviewSender?, preview: String, kind: PreviewKind) =
        ChatRowUi(id, "Chat $id", null, sender, preview, ChatTimeLabel.Clock("09:58"), 0, previewKind = kind)

    @Test
    fun rows_render_each_kind_in_chinese() {
        val rows = persistentListOf(
            row("a", null, "Photo", PreviewKind.Photo),
            row("b", PreviewSender.You, "File: report.pdf", PreviewKind.File("report.pdf")),
            row("c", PreviewSender.Member("Nikos"), "File", PreviewKind.File(null)),
            row("d", null, "Message deleted", PreviewKind.Deleted),
            row("e", PreviewSender.You, "Table booked", PreviewKind.Text),
        )
        rule.setContent {
            GimTheme { ChatListScreen(ChatListUiState(rows = rows), "Sofia Costa", {}, {}, {}, {}) }
        }

        rule.onNodeWithText("照片").assertExists()
        rule.onNodeWithText("你：文件：report.pdf").assertExists()
        rule.onNodeWithText("Nikos：文件").assertExists()
        rule.onNodeWithText("消息已删除").assertExists()
        rule.onNodeWithText("你：Table booked").assertExists()
        rule.onNodeWithText("Photo").assertDoesNotExist()
        rule.onNodeWithText("你：File: report.pdf").assertDoesNotExist()
    }
}
