package im.g.apps.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockedPeopleScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val lukas = BlockedPerson(LUKAS_ID, "Lukas Weber")
    private val noName = BlockedPerson(NO_NAME_ID, null)

    private fun show(state: BlockedPeopleUiState, actions: BlockedPeopleActions = BlockedPeopleActions()) =
        rule.setContent { GimTheme { BlockedPeopleScreen(state, actions) } }

    @Test
    fun nobody_blocked_shows_the_empty_state() {
        show(BlockedPeopleUiState(people = emptyList()))

        rule.onNodeWithText("Blocked people").assertExists()
        rule.onNodeWithTag(BlockedPeopleTestTags.EMPTY).assertExists()
        rule.onNodeWithText("You haven't blocked anyone.").assertExists()
    }

    @Test
    fun lists_names_and_account_ids() {
        show(BlockedPeopleUiState(people = listOf(lukas, noName)))

        rule.onNodeWithText("Lukas Weber").assertExists()
        rule.onNodeWithText(NO_NAME_ID).assertExists()
        rule.onNodeWithTag(BlockedPeopleTestTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun unblock_asks_first() {
        val calls = mutableListOf<String>()
        show(
            BlockedPeopleUiState(people = listOf(lukas)),
            BlockedPeopleActions(onAskUnblock = { calls += "ask ${it.userId}" }, onUnblock = { calls += "unblock" }),
        )

        rule.onNodeWithTag(BlockedPeopleTestTags.unblock(LUKAS_ID)).performClick()

        assertEquals(listOf("ask $LUKAS_ID"), calls)
    }

    @Test
    fun the_dialog_explains_and_confirms() {
        val calls = mutableListOf<String>()
        show(
            BlockedPeopleUiState(people = listOf(lukas), confirm = lukas),
            BlockedPeopleActions(onUnblock = { calls += "unblock" }, onCancelUnblock = { calls += "cancel" }),
        )

        rule.onNodeWithText("Unblock Lukas Weber?").assertExists()
        rule.onNodeWithText("They can send you messages again.", substring = true).assertExists()
        rule.onNodeWithTag(BlockedPeopleTestTags.UNBLOCK_CONFIRM).performClick()

        assertEquals(listOf("unblock"), calls)
    }

    @Test
    fun a_failed_load_offers_to_try_again() {
        var retry = 0
        show(BlockedPeopleUiState(loadFailed = true), BlockedPeopleActions(onRetry = { retry++ }))

        rule.onNodeWithText("Couldn't load blocked people.", substring = true).assertExists()
        rule.onNodeWithTag(BlockedPeopleTestTags.EMPTY).assertDoesNotExist()
        rule.onNodeWithText("Try again").performClick()

        assertEquals(1, retry)
    }

    @Test
    fun back_calls_the_action() {
        var back = 0
        show(BlockedPeopleUiState(people = emptyList()), BlockedPeopleActions(onBack = { back++ }))

        rule.onNodeWithContentDescription("Go back").performClick()

        assertEquals(1, back)
    }

    private companion object {
        const val LUKAS_ID = "u" + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + "a"
        const val NO_NAME_ID = "u" + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" + "a"
    }
}
