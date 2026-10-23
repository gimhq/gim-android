package im.g.apps.feature.chatlist

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.ChatState
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatListScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val rows = persistentListOf(
        ChatRowUi("chat-g", "Aegean Crew", null, PreviewSender.Member("Nikos"), "Table booked", ChatTimeLabel.Clock("09:58"), 3),
        ChatRowUi("chat-d", "Lukas Berger", null, PreviewSender.You, "See you then!", ChatTimeLabel.Weekday("Fri"), 0),
    )

    private fun show(
        state: ChatListUiState,
        onOpenChat: (String) -> Unit = {},
        onNewChat: () -> Unit = {},
        onNewGroup: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
    ) = rule.setContent {
        GimTheme { ChatListScreen(state, "Sofia Costa", onOpenChat, onNewChat, onNewGroup, onOpenSettings) }
    }

    @Test
    fun rows_render_title_preview_time_and_badge() {
        show(ChatListUiState(rows = rows))

        rule.onNodeWithText("Chats").assertExists()
        rule.onNodeWithText("Aegean Crew").assertExists()
        rule.onNodeWithText("Nikos: Table booked").assertExists()
        rule.onNodeWithText("09:58").assertExists()
        rule.onNodeWithText("3").assertExists()
        rule.onNodeWithText("Lukas Berger").assertExists()
        rule.onNodeWithText("You: See you then!").assertExists()
        rule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun leaving_and_left_rows_show_their_state_instead_of_the_preview() {
        show(
            ChatListUiState(
                rows = persistentListOf(
                    rows[0].copy(state = ChatState.LEAVING),
                    rows[1].copy(id = "chat-x", title = "Old Crew", state = ChatState.LEFT),
                ),
            ),
        )

        rule.onNodeWithText("Leaving…").assertExists()
        rule.onNodeWithText("You're no longer in this chat").assertExists()
        rule.onNodeWithText("Nikos: Table booked").assertDoesNotExist()
    }

    @Test
    fun clicking_a_row_opens_that_chat() {
        var opened: String? = null
        show(ChatListUiState(rows = rows), onOpenChat = { opened = it })

        rule.onNodeWithText("Lukas Berger").performClick()

        assertEquals("chat-d", opened)
    }

    @Test
    fun empty_state_offers_starting_a_chat() {
        var newChat = 0
        show(ChatListUiState(isEmpty = true), onNewChat = { newChat++ })

        rule.onNodeWithText("No chats yet").assertExists()
        rule.onNodeWithText("Start a chat").performClick()

        assertEquals(1, newChat)
    }

    @Test
    fun connection_banner_is_shown_when_not_connected() {
        show(ChatListUiState(rows = rows, connection = ConnectionBannerUi.WAITING_FOR_NETWORK))

        rule.onNodeWithText("Waiting for internet…").assertExists()
    }

    @Test
    fun fab_menu_offers_new_group() {
        var newGroup = 0
        show(ChatListUiState(rows = rows), onNewGroup = { newGroup++ })

        rule.onNodeWithContentDescription("New message").performClick()
        rule.onNodeWithText("New group").performClick()

        assertEquals(1, newGroup)
    }

    @Test
    fun own_avatar_opens_settings_and_there_is_no_overflow_menu() {
        var settings = 0
        show(ChatListUiState(rows = rows), onOpenSettings = { settings++ })

        rule.onNodeWithContentDescription("More options").assertDoesNotExist()
        rule.onNodeWithContentDescription("Settings").assertHasClickAction()
        rule.onNodeWithTag(ChatListTestTags.SETTINGS).performClick()

        assertEquals(1, settings)
    }
}
