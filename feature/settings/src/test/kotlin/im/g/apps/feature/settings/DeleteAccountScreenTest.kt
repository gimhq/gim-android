package im.g.apps.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class DeleteAccountScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(state: DeleteAccountUiState, actions: DeleteAccountActions = DeleteAccountActions()) =
        rule.setContent { GimTheme { DeleteAccountScreen(state, actions) } }

    @Test
    fun explains_what_happens_and_needs_the_box_ticked() {
        val calls = mutableListOf<String>()
        show(
            DeleteAccountUiState(),
            DeleteAccountActions(onUnderstoodChange = { calls += "understood $it" }, onDelete = { calls += "delete" }),
        )

        rule.onNodeWithText("can't be undone", substring = true).assertExists()
        rule.onNodeWithText("Your recovery key stops working", substring = true).assertExists()
        rule.onNodeWithText("You leave all your chats", substring = true).assertExists()
        rule.onNodeWithText("messages on this phone are deleted", substring = true).assertExists()
        rule.onNodeWithTag(DeleteAccountTestTags.DELETE).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(DeleteAccountTestTags.UNDERSTOOD).performScrollTo().performClick()

        assertEquals(listOf("understood true"), calls)
    }

    @Test
    fun the_ticked_box_enables_the_delete_button() {
        var delete = 0
        show(DeleteAccountUiState(understood = true), DeleteAccountActions(onDelete = { delete++ }))

        rule.onNodeWithTag(DeleteAccountTestTags.DELETE).performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, delete)
    }

    @Test
    fun deleting_shows_progress_without_a_way_back() {
        show(DeleteAccountUiState(understood = true, deleting = true))

        rule.onNodeWithTag(DeleteAccountTestTags.PROGRESS).assertExists()
        rule.onNodeWithText("Deleting your account", substring = true).assertExists()
        rule.onNodeWithContentDescription("Go back").assertDoesNotExist()
        rule.onNodeWithTag(DeleteAccountTestTags.DELETE).assertDoesNotExist()
    }

    @Test
    fun a_failed_delete_says_nothing_was_deleted() {
        show(DeleteAccountUiState(understood = true, error = R.string.delete_account_error_network))

        rule.onNodeWithText("Check your internet and try again", substring = true).assertExists()
        rule.onNodeWithText("Nothing was deleted", substring = true).assertExists()
        rule.onNodeWithTag(DeleteAccountTestTags.DELETE).performScrollTo().assertIsEnabled()
    }

    @Test
    fun unreachable_servers_are_named_before_moving_on() {
        var deleted = 0
        show(
            DeleteAccountUiState(deleted = true, unreachable = ServerList(listOf("https://two.example"), 1)),
            DeleteAccountActions(onDeleted = { deleted++ }),
        )

        rule.onNodeWithText("Your account is deleted").assertExists()
        rule.onNodeWithText("https://two.example", substring = true).assertExists()
        rule.onNodeWithText("OK").performClick()

        assertEquals(1, deleted)
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun unreachable_servers_are_joined_for_the_locale() {
        show(DeleteAccountUiState(deleted = true, unreachable = ServerList(listOf("a.example", "b.example", "c.example"), 3)))

        rule.onNodeWithText("a.example、b.example和c.example", substring = true).assertExists()
    }

    @Test
    fun unreachable_servers_without_urls_are_counted() {
        show(DeleteAccountUiState(deleted = true, unreachable = ServerList(emptyList(), 3)))

        rule.onNodeWithText("3 servers couldn't be reached", substring = true).assertExists()
    }

    @Test
    fun back_calls_the_action() {
        var back = 0
        show(DeleteAccountUiState(), DeleteAccountActions(onBack = { back++ }))

        rule.onNodeWithContentDescription("Go back").performClick()

        assertEquals(1, back)
    }
}
