package im.g.apps.feature.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.component.AvatarTestTags
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val state = SettingsUiState(displayName = "Sofia Costa", username = "sofia", accountId = "u-7f3a9c21")

    private fun show(state: SettingsUiState = this.state, actions: SettingsActions = SettingsActions()) =
        rule.setContent { GimTheme { SettingsScreen(state, actions) } }

    private fun openSignOutDialog() {
        rule.onNodeWithTag(SettingsTestTags.SIGN_OUT).performScrollTo().performClick()
        rule.onNodeWithText("Sign out?").assertExists()
    }

    @Test
    fun header_shows_the_name_and_username() {
        show()

        rule.onNodeWithText("Settings").assertExists()
        rule.onNodeWithText("Sofia Costa").assertExists()
        rule.onNodeWithText("@sofia").assertExists()
    }

    @Test
    fun header_reads_the_name_once_without_the_initials() {
        show(actions = SettingsActions(onOpenProfile = {}))

        val header = rule.onNodeWithTag(SettingsTestTags.HEADER).fetchSemanticsNode()
        assertEquals(listOf("Sofia Costa", "@sofia"), header.config[SemanticsProperties.Text].map { it.text })
        assertEquals(null, header.config.getOrNull(SemanticsProperties.ContentDescription))
        rule.onAllNodesWithText("SC").assertCountEquals(0)
    }

    @Test
    fun header_shows_the_photo_when_there_is_one() {
        show(state.copy(photoPath = "/cache/gim/avatars/u-7f3a9c21"))

        rule.onNode(hasTestTag(AvatarTestTags.PHOTO) and hasAnyAncestor(hasTestTag(SettingsTestTags.HEADER)), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun header_without_a_photo_shows_only_initials() {
        show()

        rule.onNodeWithTag(AvatarTestTags.PHOTO, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("SC", useUnmergedTree = true).assertExists()
    }

    @Test
    fun header_without_username_or_profile_is_not_clickable() {
        show(state.copy(username = null))

        rule.onNodeWithText("@sofia").assertDoesNotExist()
        rule.onNode(hasClickAction() and hasTestTag(SettingsTestTags.HEADER)).assertDoesNotExist()
    }

    @Test
    fun header_opens_the_profile_when_given() {
        var profile = 0
        show(actions = SettingsActions(onOpenProfile = { profile++ }))

        rule.onNodeWithTag(SettingsTestTags.HEADER).performClick()

        assertEquals(1, profile)
    }

    @Test
    fun rows_call_their_callbacks() {
        val calls = mutableListOf<String>()
        show(
            actions = SettingsActions(
                onBack = { calls += "back" },
                onDevices = { calls += "devices" },
                onLinkDevice = { calls += "link" },
                onServers = { calls += "servers" },
                onAppearance = { calls += "appearance" },
                onLanguage = { calls += "language" },
                onHelp = { calls += "help" },
            ),
        )

        rule.onNodeWithText("Account and devices").assertExists()
        rule.onNodeWithText("Linked devices").performScrollTo().performClick()
        rule.onNodeWithText("Link a new device").performScrollTo().performClick()
        rule.onNodeWithText("Servers").performScrollTo().performClick()
        rule.onNodeWithTag(SettingsTestTags.APPEARANCE).performScrollTo().performClick()
        rule.onNodeWithTag(SettingsTestTags.LANGUAGE).performScrollTo().performClick()
        rule.onNodeWithTag(SettingsTestTags.HELP).performScrollTo().performClick()
        rule.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf("devices", "link", "servers", "appearance", "language", "help", "back"), calls)
    }

    @Test
    fun recovery_key_and_notifications_rows_call_their_callbacks() {
        val calls = mutableListOf<String>()
        show(actions = SettingsActions(onRecoveryKey = { calls += "recovery" }, onNotifications = { calls += "notifications" }))

        rule.onNodeWithTag(SettingsTestTags.RECOVERY_KEY).performScrollTo().assertTextContains("Your recovery key").performClick()
        rule.onNodeWithTag(SettingsTestTags.NOTIFICATIONS).performScrollTo().assertTextContains("Notifications").performClick()

        assertEquals(listOf("recovery", "notifications"), calls)
    }

    @Test
    fun new_recovery_key_and_delete_account_rows_call_their_callbacks() {
        val calls = mutableListOf<String>()
        show(actions = SettingsActions(onNewRecoveryKey = { calls += "new key" }, onDeleteAccount = { calls += "delete" }))

        rule.onNodeWithTag(SettingsTestTags.NEW_RECOVERY_KEY).performScrollTo()
            .assertTextContains("Create a new recovery key").performClick()
        rule.onNodeWithTag(SettingsTestTags.DELETE_ACCOUNT).performScrollTo().assertTextContains("Delete account").performClick()

        assertEquals(listOf("new key", "delete"), calls)
    }

    @Test
    fun privacy_switches_show_the_current_values_and_explain_themselves() {
        show(state.copy(readReceipts = false, typingIndicator = true))

        rule.onNodeWithText("Privacy").performScrollTo().assertExists()
        rule.onNodeWithTag(SettingsTestTags.READ_RECEIPTS).performScrollTo().assertIsOff()
        rule.onNodeWithTag(SettingsTestTags.TYPING_INDICATOR).performScrollTo().assertIsOn()
        rule.onNodeWithText("and you can't see theirs", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("and you can't see when they are", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("on all your devices", substring = true).performScrollTo().assertExists()
    }

    @Test
    fun privacy_switches_and_blocked_people_call_their_callbacks() {
        val calls = mutableListOf<String>()
        show(
            actions = SettingsActions(
                onReadReceiptsChange = { calls += "receipts $it" },
                onTypingIndicatorChange = { calls += "typing $it" },
                onBlockedPeople = { calls += "blocked" },
            ),
        )

        rule.onNodeWithTag(SettingsTestTags.READ_RECEIPTS).performScrollTo().performClick()
        rule.onNodeWithTag(SettingsTestTags.TYPING_INDICATOR).performScrollTo().performClick()
        rule.onNodeWithTag(SettingsTestTags.BLOCKED_PEOPLE).performScrollTo().assertTextContains("Blocked people").performClick()

        assertEquals(listOf("receipts false", "typing false", "blocked"), calls)
    }

    @Test
    fun look_and_language_rows_show_the_current_values() {
        rule.setContent { GimTheme { SettingsScreen(state, SettingsActions(), themeMode = ThemeMode.DARK, languageTag = "en") } }

        rule.onNodeWithText("Look and language").performScrollTo().assertExists()
        rule.onNodeWithText("Dark").performScrollTo().assertExists()
        rule.onNodeWithText("English").performScrollTo().assertExists()
    }

    @Test
    fun look_and_language_rows_default_to_the_phone_settings() {
        show()

        rule.onNodeWithText("Use phone setting").performScrollTo().assertExists()
        rule.onNodeWithText("Use phone language").performScrollTo().assertExists()
    }

    @Test
    fun keep_server_row_shows_the_server_and_opens_its_screen() {
        var opened = 0
        rule.setContent {
            GimTheme { SettingsScreen(state, SettingsActions(onKeepServer = { opened++ }), keepServer = "https://keep.g.im") }
        }

        rule.onNodeWithTag(SettingsTestTags.KEEP_SERVER).performScrollTo().assertTextContains("Keep server")
        rule.onNodeWithTag(SettingsTestTags.KEEP_SERVER).assertTextContains("https://keep.g.im")
        rule.onNodeWithTag(SettingsTestTags.KEEP_SERVER).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun keep_server_row_says_off_without_a_server() {
        show()

        rule.onNodeWithTag(SettingsTestTags.KEEP_SERVER).performScrollTo().assertTextContains("Off")
    }

    @Test
    fun sign_out_asks_first_and_mentions_the_recovery_kit() {
        var signOut = 0
        show(actions = SettingsActions(onSignOut = { signOut++ }))

        openSignOutDialog()

        rule.onNodeWithText("recovery kit", substring = true).assertExists()
        assertEquals(0, signOut)
    }

    @Test
    fun confirming_signs_out_once_and_disables_the_buttons() {
        var signOut = 0
        show(actions = SettingsActions(onSignOut = { signOut++ }))

        openSignOutDialog()
        rule.onNodeWithTag(SettingsTestTags.SIGN_OUT_CONFIRM).performClick()

        assertEquals(1, signOut)
        // The dialog stays up with its actions disabled until sign-out navigates away.
        rule.onNodeWithTag(SettingsTestTags.SIGN_OUT_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun cancelling_the_dialog_does_not_sign_out() {
        var signOut = 0
        show(actions = SettingsActions(onSignOut = { signOut++ }))

        openSignOutDialog()
        rule.onNodeWithText("Cancel").performClick()

        rule.onNodeWithText("Sign out?").assertDoesNotExist()
        assertEquals(0, signOut)
    }
}
