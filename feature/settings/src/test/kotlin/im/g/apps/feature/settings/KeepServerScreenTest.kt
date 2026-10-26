package im.g.apps.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
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
class KeepServerScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val onDefault = KeepServerUiState(
        choice = KeepServerChoice.Default,
        current = BUILT_IN,
        buildDefault = BUILT_IN,
        input = BUILT_IN,
    )

    private fun show(state: KeepServerUiState, actions: KeepServerActions = KeepServerActions()) =
        rule.setContent { GimTheme { KeepServerScreen(state, actions) } }

    @Test
    fun default_shows_the_server_what_it_does_and_turn_off_only() {
        show(onDefault)

        rule.onNodeWithText("Keep server").assertExists()
        rule.onNodeWithTag(KeepServerTestTags.CURRENT).assertTextContains(BUILT_IN)
        rule.onNodeWithText(
            "Backs up your privacy settings and receives the reports you send. Your backup is encrypted.",
        ).assertExists()
        rule.onNodeWithTag(KeepServerTestTags.TURN_OFF).assertExists()
        rule.onNodeWithTag(KeepServerTestTags.RESET).assertDoesNotExist()
        rule.onNodeWithTag(KeepServerTestTags.RESTART).assertDoesNotExist()
        rule.onNodeWithTag(KeepServerTestTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun off_explains_what_stops_and_offers_the_default() {
        show(onDefault.copy(choice = KeepServerChoice.Off, current = null, input = ""))

        rule.onNodeWithTag(KeepServerTestTags.CURRENT).assertTextContains("Off")
        rule.onNodeWithText(
            "While it's off, your privacy settings are not backed up and you can't report people.",
        ).assertExists()
        rule.onNodeWithTag(KeepServerTestTags.RESET).performScrollTo().assertExists()
        rule.onNodeWithTag(KeepServerTestTags.TURN_OFF).assertDoesNotExist()
    }

    @Test
    fun typing_and_saving_and_the_other_buttons_call_their_callbacks() {
        val calls = mutableListOf<String>()
        show(
            onDefault.copy(choice = KeepServerChoice.Custom(CUSTOM), current = CUSTOM, input = "https://keep.other.eu"),
            KeepServerActions(
                onInputChange = { calls += "input $it" },
                onSave = { calls += "save" },
                onReset = { calls += "reset" },
                onTurnOff = { calls += "off" },
            ),
        )

        rule.onNodeWithTag(KeepServerTestTags.INPUT).performTextReplacement("https://keep.new.eu")
        rule.onNodeWithTag(KeepServerTestTags.SAVE).assertIsEnabled().performClick()
        rule.onNodeWithTag(KeepServerTestTags.RESET).performScrollTo().performClick()
        rule.onNodeWithTag(KeepServerTestTags.TURN_OFF).performScrollTo().performClick()

        assertEquals("input https://keep.new.eu", calls.first())
        assertEquals(listOf("save", "reset", "off"), calls.filterNot { it.startsWith("input ") })
    }

    @Test
    fun shows_why_an_address_was_refused() {
        show(onDefault.copy(input = "http://keep.example.eu", error = KeepServerUrlError.INSECURE))

        rule.onNodeWithText("Use https://. Addresses with http:// only work on your own network.").assertExists()
    }

    @Test
    fun a_change_asks_for_a_restart() {
        show(onDefault.copy(choice = KeepServerChoice.Off, current = null, input = "", restartNeeded = true))

        rule.onNodeWithTag(KeepServerTestTags.RESTART)
            .assertTextContains("Close the app and open it again to use this change.")
    }

    private companion object {
        const val BUILT_IN = "https://keep.g.im"
        const val CUSTOM = "https://keep.example.eu"
    }
}
