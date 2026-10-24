package im.g.apps.feature.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
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
class ProfileScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val state = ProfileUiState(displayName = "Sofia Costa", username = "sofia", accountId = ACCOUNT_ID)

    private fun show(state: ProfileUiState = this.state, actions: ProfileActions = ProfileActions()) =
        rule.setContent { GimTheme { ProfileScreen(state, actions) } }

    @Test
    fun shows_the_name_username_and_account_id() {
        show()

        rule.onNodeWithText("Profile").assertExists()
        rule.onNodeWithText("Sofia Costa").assertExists()
        rule.onNodeWithText("@sofia").performScrollTo().assertExists()
        rule.onNodeWithText(ACCOUNT_ID).performScrollTo().assertExists()
    }

    @Test
    fun without_a_username_offers_to_add_one() {
        var add = 0
        show(state.copy(username = null), ProfileActions(onEditUsername = { add++ }))

        rule.onNodeWithText("Friends can find you with it.", substring = true).performScrollTo().assertExists()
        rule.onNodeWithTag(ProfileTestTags.USERNAME_ADD).performScrollTo().performClick()

        assertEquals(1, add)
    }

    @Test
    fun copy_calls_the_action() {
        var copy = 0
        show(actions = ProfileActions(onCopyAccountId = { copy++ }))

        rule.onNodeWithTag(ProfileTestTags.COPY_ACCOUNT_ID).performScrollTo().performClick()

        assertEquals(1, copy)
    }

    @Test
    fun qr_code_encodes_exactly_the_account_id() {
        show()

        val node = rule.onNodeWithTag(ProfileTestTags.QR_CODE).performScrollTo()
        assertEquals(ACCOUNT_ID, node.fetchSemanticsNode().config[ProfileQrPayloadKey])
        rule.onNodeWithContentDescription("Your QR code").assertExists()
        rule.onNodeWithText("Friends can scan this to start a chat with you.").performScrollTo().assertExists()
    }

    @Test
    fun remove_asks_for_confirmation_first() {
        val calls = mutableListOf<String>()
        show(actions = ProfileActions(onAskRemoveUsername = { calls += "ask" }, onRemoveUsername = { calls += "remove" }))

        rule.onNodeWithTag(ProfileTestTags.USERNAME_REMOVE).performScrollTo().performClick()

        assertEquals(listOf("ask"), calls)
    }

    @Test
    fun remove_dialog_explains_and_confirms() {
        val calls = mutableListOf<String>()
        show(
            state.copy(editor = ProfileEditor.REMOVE_USERNAME),
            ProfileActions(onRemoveUsername = { calls += "remove" }, onCancelEdit = { calls += "cancel" }),
        )

        rule.onNodeWithText("Friends can't find you by username anymore. Your chats stay.").assertExists()
        rule.onNodeWithTag(ProfileTestTags.USERNAME_REMOVE_CONFIRM).performClick()

        assertEquals(listOf("remove"), calls)
    }

    @Test
    fun name_dialog_saves_what_was_typed_and_shows_the_error() {
        val saved = mutableListOf<String>()
        show(
            state.copy(editor = ProfileEditor.NAME, nameError = ProfileFieldError.NAME_INVALID),
            ProfileActions(onSaveName = { saved += it }),
        )

        rule.onNodeWithText("Enter a name of 1 to 64 characters.").assertExists()
        rule.onNodeWithTag(ProfileTestTags.NAME_FIELD).performTextReplacement("Sofia C.")
        rule.onNodeWithTag(ProfileTestTags.NAME_SAVE).performClick()

        assertEquals(listOf("Sofia C."), saved)
    }

    @Test
    fun username_dialog_claims_what_was_typed_and_shows_taken() {
        val saved = mutableListOf<String>()
        show(
            state.copy(editor = ProfileEditor.USERNAME, usernameError = ProfileFieldError.USERNAME_TAKEN),
            ProfileActions(onSaveUsername = { saved += it }),
        )

        rule.onNodeWithText("That username is taken. Try another one.").assertExists()
        rule.onNodeWithTag(ProfileTestTags.USERNAME_FIELD).performTextReplacement("sofia_2")
        rule.onNodeWithTag(ProfileTestTags.USERNAME_CLAIM).performClick()

        assertEquals(listOf("sofia_2"), saved)
    }

    @Test
    fun change_and_name_rows_open_their_editors() {
        val calls = mutableListOf<String>()
        show(actions = ProfileActions(onEditName = { calls += "name" }, onEditUsername = { calls += "username" }))

        rule.onNodeWithTag(ProfileTestTags.NAME).performScrollTo().performClick()
        rule.onNodeWithTag(ProfileTestTags.USERNAME_CHANGE).performScrollTo().performClick()

        assertEquals(listOf("name", "username"), calls)
    }

    @Test
    fun without_a_photo_offers_to_change_it_but_not_to_remove_it() {
        var change = 0
        show(actions = ProfileActions(onChangePhoto = { change++ }))

        rule.onNodeWithTag(ProfileTestTags.PHOTO).assertExists()
        rule.onNodeWithTag(ProfileTestTags.PHOTO_CHANGE).assertTextContains("Change photo").performClick()
        rule.onNodeWithTag(ProfileTestTags.PHOTO_REMOVE).assertDoesNotExist()

        assertEquals(1, change)
    }

    @Test
    fun with_a_photo_offers_to_remove_it() {
        var remove = 0
        show(state.copy(hasPhoto = true), ProfileActions(onRemovePhoto = { remove++ }))

        rule.onNodeWithTag(ProfileTestTags.PHOTO_REMOVE).assertTextContains("Remove photo").performClick()

        assertEquals(1, remove)
    }

    @Test
    fun while_saving_a_photo_the_buttons_are_disabled() {
        show(state.copy(hasPhoto = true, photoSaving = true))

        rule.onNodeWithTag(ProfileTestTags.PHOTO_CHANGE).assertIsNotEnabled()
        rule.onNodeWithTag(ProfileTestTags.PHOTO_REMOVE).assertIsNotEnabled()
        rule.onNodeWithTag(ProfileTestTags.PHOTO_PROGRESS).assertExists()
    }

    companion object {
        const val ACCOUNT_ID = "u" + "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuv" + "q"
    }
}
