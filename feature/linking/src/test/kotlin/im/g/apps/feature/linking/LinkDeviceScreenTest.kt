package im.g.apps.feature.linking

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.designsystem.theme.GimTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinkDeviceScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val callbacks = LinkDeviceCallbacks(
        onBack = { calls += "back" },
        onRequestCamera = { calls += "camera" },
        onOpenSettings = { calls += "settings" },
        onPastedCodeChange = { calls += "paste:$it" },
        onSubmitPastedCode = { calls += "submit" },
        onConfirm = { calls += "confirm" },
        onReject = { calls += "reject" },
        onDone = { calls += "done" },
    )

    private fun show(state: LinkDeviceUiState, camera: CameraAccess?) = rule.setContent {
        GimTheme {
            LinkDeviceScreen(state, camera, callbacks, scanner = { Box(it) })
        }
    }

    @Test
    fun granted_camera_shows_the_scanner() {
        show(LinkDeviceUiState(), CameraAccess.GRANTED)
        rule.onNodeWithTag(LinkDeviceTestTags.SCANNER).assertExists()
        rule.onNodeWithTag(LinkDeviceTestTags.ALLOW_CAMERA).assertDoesNotExist()
    }

    @Test
    fun camera_not_requested_explains_and_asks() {
        show(LinkDeviceUiState(), CameraAccess.NOT_REQUESTED)
        rule.onNodeWithText("gim needs the camera to scan the code.", substring = true).assertExists()
        rule.onNodeWithTag(LinkDeviceTestTags.SCANNER).assertDoesNotExist()
        rule.onNodeWithTag(LinkDeviceTestTags.ALLOW_CAMERA).performClick()
        assertEquals(listOf("camera"), calls)
    }

    @Test
    fun camera_rationale_asks_again() {
        show(LinkDeviceUiState(), CameraAccess.RATIONALE)
        rule.onNodeWithText("Without the camera, gim cannot scan the code.", substring = true).assertExists()
        rule.onNodeWithTag(LinkDeviceTestTags.ALLOW_CAMERA).performClick()
        assertEquals(listOf("camera"), calls)
    }

    @Test
    fun camera_denied_links_to_settings_and_keeps_the_paste_fallback() {
        show(LinkDeviceUiState(), CameraAccess.DENIED)
        rule.onNodeWithText("Camera access is turned off for gim.", substring = true).assertExists()
        rule.onNodeWithTag(LinkDeviceTestTags.ALLOW_CAMERA).assertDoesNotExist()
        rule.onNodeWithTag(LinkDeviceTestTags.OPEN_SETTINGS).performClick()
        rule.onNodeWithTag(LinkDeviceTestTags.SUBMIT_CODE).assertIsNotEnabled()
        rule.onNodeWithTag(LinkDeviceTestTags.PASTE_CODE).performTextInput("gimlink:abc")
        assertEquals(listOf("settings", "paste:gimlink:abc"), calls)
    }

    @Test
    fun no_camera_offers_only_the_paste_fallback() {
        show(LinkDeviceUiState(pastedCode = "gimlink:abc"), camera = null)
        rule.onNodeWithText("No camera found. Paste the code below instead.").assertExists()
        rule.onNodeWithTag(LinkDeviceTestTags.SUBMIT_CODE).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("submit"), calls)
    }

    @Test
    fun errors_are_shown_on_the_scan_step() {
        show(LinkDeviceUiState(error = LinkDeviceError.TryAgainLater(30)), CameraAccess.GRANTED)
        rule.onNodeWithTag(LinkDeviceTestTags.ERROR).assertTextEquals("The server is busy. Try again in 30 seconds.")
    }

    @Test
    fun invalid_code_error_is_shown() {
        show(LinkDeviceUiState(error = LinkDeviceError.InvalidCode), CameraAccess.GRANTED)
        rule.onNodeWithTag(LinkDeviceTestTags.ERROR).assertTextEquals("This code doesn’t work. Make a new code on your other device and scan it again.")
    }

    @Test
    fun confirm_step_shows_the_sas_and_device_and_both_choices() {
        show(LinkDeviceUiState(step = LinkDeviceStep.CONFIRM, sas = "482 913", deviceId = "d-7"), CameraAccess.GRANTED)
        rule.onNodeWithTag(LinkDeviceTestTags.SAS).assertTextEquals("482 913")
        rule.onNodeWithTag(LinkDeviceTestTags.DEVICE_ID).assertTextEquals("New device: d-7")
        rule.onNodeWithTag(LinkDeviceTestTags.SCANNER).assertDoesNotExist()
        rule.onNodeWithTag(LinkDeviceTestTags.CONFIRM).performClick()
        rule.onNodeWithTag(LinkDeviceTestTags.REJECT).performClick()
        rule.onNodeWithContentDescription("Go back").performClick()
        assertEquals(listOf("confirm", "reject", "back"), calls)
    }

    @Test
    fun confirming_disables_both_choices() {
        show(LinkDeviceUiState(step = LinkDeviceStep.CONFIRMING, sas = "482 913", deviceId = "d-7"), CameraAccess.GRANTED)
        rule.onNodeWithTag(LinkDeviceTestTags.CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(LinkDeviceTestTags.REJECT).assertIsNotEnabled()
        rule.onNodeWithContentDescription("Adding device").assertExists()
    }

    @Test
    fun done_reports_the_outcome() {
        show(LinkDeviceUiState(step = LinkDeviceStep.DONE, linked = true), CameraAccess.GRANTED)
        rule.onNodeWithTag(LinkDeviceTestTags.DONE).assertTextEquals(
            "Your new device is linked. Your chats will show up on it soon.",
        )
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf("done"), calls)
    }
}
