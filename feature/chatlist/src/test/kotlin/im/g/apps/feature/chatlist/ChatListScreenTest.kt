package im.g.apps.feature.chatlist

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
        onSignOut: () -> Unit = {},
        onLinkDevice: () -> Unit = {},
    ) = rule.setContent {
        GimTheme { ChatListScreen(state, onOpenChat, onNewChat, onNewGroup, onSignOut, onLinkDevice) }
    }

    private fun openSignOutDialog() {
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Sign out").performClick()
        rule.onNodeWithText("Sign out?").assertExists()
    }

    private val confirmButton = hasText("Sign out") and hasClickAction()

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
    fun overflow_link_device_runs_the_callback() {
        var linkDevice = 0
        show(ChatListUiState(rows = rows), onLinkDevice = { linkDevice++ })

        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Link a new device").performClick()

        assertEquals(1, linkDevice)
        rule.onNodeWithText("Link a new device").assertDoesNotExist()
    }

    @Test
    fun overflow_sign_out_confirms_before_signing_out() {
        var signOut = 0
        show(ChatListUiState(rows = rows), onSignOut = { signOut++ })

        openSignOutDialog()
        assertEquals(0, signOut)
        rule.onNode(confirmButton).performClick()

        assertEquals(1, signOut)
        // The dialog stays up with its actions disabled until sign-out navigates away.
        rule.onNode(confirmButton).assertIsNotEnabled()
        rule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun cancelling_the_sign_out_dialog_does_not_sign_out() {
        var signOut = 0
        show(ChatListUiState(rows = rows), onSignOut = { signOut++ })

        openSignOutDialog()
        rule.onNodeWithText("Cancel").performClick()

        rule.onNodeWithText("Sign out?").assertDoesNotExist()
        assertEquals(0, signOut)
    }
}
