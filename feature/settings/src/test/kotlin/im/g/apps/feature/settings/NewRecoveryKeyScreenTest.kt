package im.g.apps.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewRecoveryKeyScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val kit = NewRecoveryKeyUiState(step = NewRecoveryKeyStep.KIT, accountId = ACCOUNT_ID, recoveryCode = CODE)

    private fun show(state: NewRecoveryKeyUiState, actions: NewRecoveryKeyActions = NewRecoveryKeyActions()) =
        rule.setContent { GimTheme { NewRecoveryKeyScreen(state, actions) } }

    @Test
    fun the_warning_explains_and_creates() {
        var create = 0
        show(NewRecoveryKeyUiState(), NewRecoveryKeyActions(onCreate = { create++ }))

        rule.onNodeWithText("Your old key stops working", substring = true).assertExists()
        rule.onNodeWithText("only once", substring = true).assertExists()
        rule.onNodeWithText("Save it right away", substring = true).assertExists()
        rule.onNodeWithTag(NewRecoveryKeyTestTags.CREATE).performScrollTo().performClick()

        assertEquals(1, create)
    }

    @Test
    fun creating_shows_progress_and_disables_the_button() {
        show(NewRecoveryKeyUiState(creating = true))

        rule.onNodeWithTag(NewRecoveryKeyTestTags.PROGRESS).assertExists()
        rule.onNodeWithTag(NewRecoveryKeyTestTags.CREATE).assertIsNotEnabled()
    }

    @Test
    fun a_failed_rotation_says_what_to_try() {
        show(NewRecoveryKeyUiState(error = R.string.new_key_error_network))

        rule.onNodeWithText("Check your internet and try again", substring = true).assertExists()
        rule.onNodeWithText("Your old key still works", substring = true).assertExists()
        rule.onNodeWithTag(NewRecoveryKeyTestTags.CREATE).assertIsEnabled()
    }

    @Test
    fun the_kit_shows_the_account_id_and_key_and_copies_and_shares_the_kit_text() {
        val calls = mutableListOf<String>()
        show(
            kit,
            NewRecoveryKeyActions(
                formatKit = { "${it.title}|${it.accountId}|${it.recoveryKey}" },
                onCopyKit = { calls += "copy $it" },
                onShareKit = { calls += "share $it" },
                onContinueFromKit = { calls += "saved" },
            ),
        )

        rule.onNodeWithText(ACCOUNT_ID).assertExists()
        rule.onNodeWithText(CODE).assertExists()
        rule.onNodeWithTag(NewRecoveryKeyTestTags.COPY).performScrollTo().performClick()
        rule.onNodeWithText("Copied").assertExists()
        rule.onNodeWithTag(NewRecoveryKeyTestTags.SHARE).performScrollTo().performClick()
        rule.onNodeWithTag(NewRecoveryKeyTestTags.SAVED).performScrollTo().performClick()

        val text = "gim recovery kit|Account ID|Recovery key"
        assertEquals(listOf("copy $text", "share $text", "saved"), calls)
    }

    @Test
    fun the_kit_names_unsynced_servers() {
        show(kit.copy(unsynced = ServerList(listOf("https://two.example"), 1)))

        rule.onNodeWithText("https://two.example", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("old key still works there", substring = true).assertExists()
    }

    @Test
    fun the_kit_counts_unsynced_servers_without_urls() {
        show(kit.copy(unsynced = ServerList(emptyList(), 2)))

        rule.onNodeWithText("2 servers couldn't be updated yet", substring = true).performScrollTo().assertExists()
    }

    @Test
    fun the_check_enables_confirm_for_four_characters_and_shows_a_mismatch() {
        val calls = mutableListOf<String>()
        show(
            kit.copy(step = NewRecoveryKeyStep.CHECK, check = "AB", checkMismatch = true),
            NewRecoveryKeyActions(onCheckChange = { calls += "change $it" }, onConfirmCheck = { calls += "confirm" }),
        )

        rule.onNodeWithText("Type the last 4 characters", substring = true).assertExists()
        rule.onNodeWithText("That doesn't match", substring = true).assertExists()
        rule.onNodeWithTag(NewRecoveryKeyTestTags.CHECK_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(NewRecoveryKeyTestTags.CHECK).performTextReplacement("ABC")

        assertEquals(listOf("change ABC"), calls)
    }

    @Test
    fun a_full_check_confirms() {
        var confirm = 0
        show(kit.copy(step = NewRecoveryKeyStep.CHECK, check = "W8NE"), NewRecoveryKeyActions(onConfirmCheck = { confirm++ }))

        rule.onNodeWithTag(NewRecoveryKeyTestTags.CHECK_CONFIRM).assertIsEnabled().performClick()

        assertEquals(1, confirm)
    }

    @Test
    fun leaving_before_the_check_asks_first() {
        val calls = mutableListOf<String>()
        show(
            kit.copy(confirmLeave = true),
            NewRecoveryKeyActions(onLeave = { calls += "leave" }, onStay = { calls += "stay" }),
        )

        rule.onNodeWithText("Leave without checking?").assertExists()
        rule.onNodeWithText("Stay").performClick()
        rule.onNodeWithText("Leave").performClick()

        assertEquals(listOf("stay", "leave"), calls)
    }

    @Test
    fun done_says_so_and_finishes() {
        var done = 0
        show(NewRecoveryKeyUiState(step = NewRecoveryKeyStep.DONE), NewRecoveryKeyActions(onDone = { done++ }))

        rule.onNodeWithText("Your new recovery key is ready", substring = true).assertExists()
        rule.onNodeWithTag(NewRecoveryKeyTestTags.DONE).performClick()

        assertEquals(1, done)
    }

    @Test
    fun back_calls_the_action() {
        var back = 0
        show(kit, NewRecoveryKeyActions(onBack = { back++ }))

        rule.onNodeWithContentDescription("Go back").performClick()

        assertEquals(1, back)
    }

    private companion object {
        const val ACCOUNT_ID = "u" + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + "a"
        const val CODE = "7BQ2-K9MX-4TVA-0HZR-PC63-W8NE-1SJD-5GFY-QK2M-V7A"
    }
}
