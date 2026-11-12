package im.g.apps.feature.settings

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeRecoveryKitCodec
import im.g.apps.core.designsystem.explainer.AccountExplainerPoint
import im.g.apps.core.designsystem.explainer.AccountExplainerTestTags
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryKeyScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun show(state: RecoveryKeyUiState, actions: RecoveryKeyActions = RecoveryKeyActions()) =
        rule.setContent { GimTheme { RecoveryKeyScreen(state, actions) } }

    private fun secure(): Boolean =
        rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun locked_explains_the_key_and_asks_to_confirm_first() {
        var back = 0
        var show = 0
        show(
            RecoveryKeyUiState(status = RecoveryKeyStatus.LOCKED, authError = R.string.recovery_key_no_lock),
            RecoveryKeyActions(onBack = { back++ }, onShow = { show++ }),
        )

        rule.onNodeWithText("Your recovery key").assertExists()
        rule.onNodeWithTag(AccountExplainerTestTags.point(AccountExplainerPoint.RECOVERY_KEY)).assertExists()
        rule.onNodeWithText("confirm it's you", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("Set a screen lock", substring = true).performScrollTo().assertExists()
        rule.onNodeWithTag(RecoveryKeyTestTags.RECOVERY_KEY).assertDoesNotExist()
        assertFalse(secure())
        rule.onNodeWithTag(RecoveryKeyTestTags.SHOW).performScrollTo().performClick()
        rule.onNodeWithContentDescription("Go back").performClick()
        assertEquals(1, show)
        assertEquals(1, back)
    }

    @Test
    fun shown_key_blocks_screenshots_and_offers_copy_save_and_qr() {
        val copied = mutableListOf<String>()
        val saved = mutableListOf<String>()
        var qr = 0
        show(
            RecoveryKeyUiState(status = RecoveryKeyStatus.SHOWN, accountId = ACCOUNT_ID, recoveryCode = CODE, showQr = true),
            RecoveryKeyActions(
                formatKit = { "kit:${it.title}" },
                onCopyKit = { copied += it },
                onShareKit = { saved += it },
                onToggleQr = { qr++ },
            ),
        )

        assertTrue(secure())
        rule.onNodeWithTag(RecoveryKeyTestTags.ACCOUNT_ID).assertExists()
        rule.onNodeWithText(CODE).assertExists()
        rule.onNodeWithTag(RecoveryKeyTestTags.COPY).performScrollTo().performClick()
        rule.onNodeWithTag(RecoveryKeyTestTags.SAVE).performScrollTo().performClick()
        val qrNode = rule.onNodeWithTag(RecoveryKeyTestTags.QR).performScrollTo().fetchSemanticsNode()
        assertEquals("kit:gim recovery kit", qrNode.config[ProfileQrPayloadKey])
        rule.onNodeWithTag(RecoveryKeyTestTags.QR_TOGGLE).performScrollTo().performClick()
        assertEquals(listOf("kit:gim recovery kit"), copied)
        assertEquals(listOf("kit:gim recovery kit"), saved)
        assertEquals(1, qr)
    }

    @Test
    fun missing_key_offers_a_new_one() {
        var create = 0
        show(RecoveryKeyUiState(status = RecoveryKeyStatus.MISSING), RecoveryKeyActions(onCreateNewKey = { create++ }))

        rule.onNodeWithText("This device doesn't have your recovery key").assertExists()
        rule.onNodeWithTag(RecoveryKeyTestTags.SHOW).assertDoesNotExist()
        rule.onNodeWithTag(RecoveryKeyTestTags.CREATE_NEW).performScrollTo().performClick()
        assertEquals(1, create)
    }

    @Test
    fun route_reveals_after_authentication_and_hides_when_the_screen_stops() {
        val client = FakeGimClient(scope, outboxDelays = null)
        val code = runBlocking { client.createIdentity("Sofia Costa") }
        val vm = RecoveryKeyViewModel(client, FakeRecoveryKitCodec, ioDispatcher = Dispatchers.Unconfined)
        var prompts = 0
        rule.setContent {
            GimTheme {
                RecoveryKeyRoute(
                    viewModel = vm,
                    authenticator = { _, _, onResult ->
                        prompts++
                        onResult(DeviceAuthResult.SUCCESS)
                    },
                    onShareKit = {},
                    onCreateNewKey = {},
                    onBack = {},
                )
            }
        }

        rule.onNodeWithTag(RecoveryKeyTestTags.SHOW).performScrollTo().performClick()
        rule.onNodeWithText(code).assertExists()
        assertTrue(secure())
        assertEquals(1, prompts)

        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithText(code).assertDoesNotExist()
        rule.onNodeWithTag(RecoveryKeyTestTags.SHOW).assertExists()
        assertFalse(secure())
    }

    private companion object {
        const val ACCOUNT_ID = "uabcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvq"
        const val CODE = "7BQ2-K9MX-4TVA-0HZR-PC63-W8NE-1SJD-5GFY-QK2M-V7A"
    }
}
