package im.g.apps.feature.chatlist

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class ChatRowsTest {
    @get:Rule
    val rule = createComposeRule()

    private val crew = ChatRowUi(
        "chat-g",
        "Aegean Crew",
        null,
        PreviewSender.Member("Nikos"),
        "Table booked",
        ChatTimeLabel.Minutes(2),
        3,
    )

    private fun show(state: ChatListUiState, onNewChat: () -> Unit = {}) = rule.setContent {
        GimTheme { ChatListScreen(state, "Sofia Costa", {}, onNewChat, {}, {}) }
    }

    private fun row(title: String) = rule.onNode(hasClickAction() and hasText(title))

    @Test
    fun unread_row_reads_title_unread_count_preview_and_time_in_order() {
        show(ChatListUiState(rows = persistentListOf(crew)))

        row("Aegean Crew")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Aegean Crew, 3 unread messages, Nikos: Table booked, 2 min"),
                ),
            )
            .assert(SemanticsMatcher("onClickLabel is Open chat") {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open chat"
            })
            .assertHeightIsAtLeast(72.dp)
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun row_description_uses_the_locale_separator() {
        show(ChatListUiState(rows = persistentListOf(crew)))

        row("Aegean Crew").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Aegean Crew，3 条未读消息，Nikos：Table booked，2 分钟"),
            ),
        )
    }

    @Test
    fun read_row_skips_the_unread_count_and_labels_render() {
        show(
            ChatListUiState(
                rows = persistentListOf(
                    crew.copy(id = "a", title = "Ana", unreadCount = 0, time = ChatTimeLabel.JustNow),
                    crew.copy(id = "b", title = "Ben", unreadCount = 0, time = ChatTimeLabel.Yesterday),
                    crew.copy(id = "c", title = "Cleo", unreadCount = 1, time = ChatTimeLabel.Weekday("Fri")),
                ),
            ),
        )

        row("Ana").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Ana, Nikos: Table booked, Now")),
        )
        row("Ben").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Ben, Nikos: Table booked, Yesterday")),
        )
        row("Cleo").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Cleo, 1 unread message, Nikos: Table booked, Fri")),
        )
    }

    @Test
    fun more_than_99_unread_shows_99_plus() {
        show(ChatListUiState(rows = persistentListOf(crew.copy(unreadCount = 150))))

        rule.onNodeWithText("99+").assertIsDisplayed()
        row("Aegean Crew").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Aegean Crew, 150 unread messages, Nikos: Table booked, 2 min"),
            ),
        )
    }

    @Test
    fun empty_state_start_a_chat_button_opens_new_chat() {
        var newChat = 0
        show(ChatListUiState(isEmpty = true), onNewChat = { newChat++ })

        rule.onNodeWithText("No chats yet").assertIsDisplayed()
        rule.onNode(hasClickAction() and hasText("Start a chat"))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, newChat)
    }

    @Test
    @Config(fontScale = 2.0f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun at_double_font_scale_the_time_keeps_its_width_and_the_title_ellipsizes() {
        val title = "Weekend hiking crew with a very long group name that cannot fit"
        val long = crew.copy(title = title, time = ChatTimeLabel.Yesterday, unreadCount = 12)
        show(ChatListUiState(rows = persistentListOf(long)))

        val rowBounds = row(title).getBoundsInRoot()
        val time = rule.onNodeWithText("Yesterday", useUnmergedTree = true).assertIsDisplayed()
        val timeBounds = time.getBoundsInRoot()
        val titleBounds = rule.onNodeWithText(title, useUnmergedTree = true).getBoundsInRoot()
        val badgeBounds = rule.onNodeWithText("12", useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()

        val layout = textLayout(time.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
        assertFalse("time clipped vertically: ${layout.size}", layout.didOverflowHeight)
        assertFalse("time ellipsized: ${layout.size}", layout.isLineEllipsized(0))
        assertTrue("title $titleBounds overlaps time $timeBounds", titleBounds.right <= timeBounds.left)
        for (child in listOf(timeBounds, titleBounds, badgeBounds)) {
            assertTrue("$child outside row $rowBounds", child.left >= rowBounds.left && child.right <= rowBounds.right)
            assertTrue("$child outside row $rowBounds", child.top >= rowBounds.top && child.bottom <= rowBounds.bottom)
        }
    }

    private fun textLayout(action: ((MutableList<TextLayoutResult>) -> Boolean)?): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        action?.invoke(layouts)
        return layouts.single()
    }
}
