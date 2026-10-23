package im.g.apps.feature.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.LinkSession
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeGimClientFactory
import im.g.apps.core.bridge.fake.FakePlatformHooks
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val fakeScope = CoroutineScope(SupervisorJob())
    private val manager = GimClientManager(
        FakeGimClientFactory { _, _ -> FakeGimClient(fakeScope, outboxDelays = null) },
        FakePlatformHooks(),
        InMemoryServerUrlStore(),
    )
    private val viewModel = OnboardingViewModel(manager)
    private var finished = 0

    @After
    fun tearDown() = fakeScope.cancel()

    /** The fake's waitLink returns at once; this one waits for [gate] so the QR step can be inspected. */
    private fun gatedManager(gate: CompletableDeferred<Unit>) = GimClientManager(
        { _, _ ->
            val fake = FakeGimClient(fakeScope, outboxDelays = null)
            object : GimClient by fake {
                override suspend fun waitLink(link: LinkSession): String {
                    gate.await()
                    return fake.waitLink(link)
                }
            }
        },
        FakePlatformHooks(),
        InMemoryServerUrlStore(),
    )

    private fun setContent() {
        rule.setContent { GimTheme { OnboardingRoute(viewModel = viewModel, onFinished = { finished++ }, onShareRecoveryKit = {}) } }
        rule.onNodeWithTag(OnboardingTestTags.INTRO_SKIP).performClick()
    }

    @Test
    fun welcome_enables_create_link_and_restore_for_a_valid_url() {
        setContent()
        rule.onNodeWithTag(OnboardingTestTags.CREATE_ACCOUNT).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.LINK_DEVICE).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.SERVER_URL).performTextInput("gim.example.eu")
        rule.onNodeWithText("Enter a full address, like https://example.com").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.CREATE_ACCOUNT).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.LINK_DEVICE).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.RESTORE).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.SERVER_URL).performTextReplacement("https://gim.example.eu")
        rule.onNodeWithTag(OnboardingTestTags.LINK_DEVICE).assertIsEnabled()
        rule.onNodeWithTag(OnboardingTestTags.RESTORE).assertIsEnabled()
    }

    @Test
    fun link_flow_shows_the_qr_code_then_the_sas_and_finishes_on_confirm() {
        val gate = CompletableDeferred<Unit>()
        val manager = gatedManager(gate)
        val vm = OnboardingViewModel(manager)
        rule.setContent { GimTheme { OnboardingRoute(viewModel = vm, onFinished = { finished++ }, onShareRecoveryKit = {}) } }
        rule.onNodeWithTag(OnboardingTestTags.INTRO_NEXT).performClick()
        rule.onNodeWithTag(OnboardingTestTags.INTRO_NEXT).performClick()
        rule.onNodeWithTag(OnboardingTestTags.INTRO_START).performClick()

        rule.onNodeWithTag(OnboardingTestTags.SERVER_URL).performTextInput("https://gim.example.eu")
        rule.onNodeWithTag(OnboardingTestTags.LINK_DEVICE).performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.linkQr != null }
        val qr = rule.onNodeWithTag(OnboardingTestTags.LINK_QR).fetchSemanticsNode().config[QrPayloadKey]
        assertEquals(vm.state.value.linkQr, qr)
        rule.onNodeWithContentDescription("Link code").assertExists()
        rule.onNodeWithText("Waiting for your other device…").assertExists()

        gate.complete(Unit)
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.step == OnboardingStep.LINK_SAS }
        rule.onNodeWithTag(OnboardingTestTags.LINK_SAS).assertTextEquals(vm.state.value.linkSas!!)
        rule.onNodeWithTag(OnboardingTestTags.LINK_REJECT).assertIsEnabled()
        rule.onNodeWithTag(OnboardingTestTags.LINK_CONFIRM).performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 5_000) { finished == 1 }
    }

    @Test
    fun link_cancel_returns_to_welcome() {
        val gate = CompletableDeferred<Unit>()
        val manager = gatedManager(gate)
        val vm = OnboardingViewModel(manager)
        rule.setContent { GimTheme { OnboardingRoute(viewModel = vm, onFinished = { finished++ }, onShareRecoveryKit = {}) } }
        rule.onNodeWithTag(OnboardingTestTags.INTRO_SKIP).performClick()
        rule.onNodeWithTag(OnboardingTestTags.SERVER_URL).performTextInput("https://gim.example.eu")
        rule.onNodeWithTag(OnboardingTestTags.LINK_DEVICE).performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.linkQr != null }

        rule.onNodeWithTag(OnboardingTestTags.LINK_CANCEL).performClick()

        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.step == OnboardingStep.WELCOME && !vm.state.value.isLoading }
        rule.onNodeWithText("Welcome to gim").assertExists()
        assertNull(manager.client.value)
    }

    @Test
    fun link_error_offers_a_new_code() {
        rule.setContent {
            GimTheme {
                OnboardingScreen(
                    OnboardingUiState(step = OnboardingStep.LINK_QR, error = OnboardingError.LinkTimedOut),
                    testCallbacks(onRetryLink = { finished++ }),
                )
            }
        }
        rule.onNodeWithText("No device answered in time. Show a new code to try again.").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.LINK_QR).assertDoesNotExist()
        rule.onNodeWithTag(OnboardingTestTags.LINK_RETRY).assertIsEnabled().performClick()
        assertEquals(1, finished)
    }

    @Test
    fun create_account_flow_shows_the_recovery_kit_checks_it_and_finishes_with_a_handle() {
        setContent()
        rule.onNodeWithTag(OnboardingTestTags.SERVER_URL).performTextInput("https://gim.example.eu")
        rule.onNodeWithTag(OnboardingTestTags.CREATE_ACCOUNT).assertIsEnabled().performClick()
        rule.onNodeWithTag(OnboardingTestTags.SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.DISPLAY_NAME).performTextInput("Sofia Costa")
        rule.onNodeWithTag(OnboardingTestTags.SUBMIT).performScrollTo().performClick()

        rule.waitUntil(timeoutMillis = 5_000) { viewModel.state.value.step == OnboardingStep.RECOVERY_KIT }
        val code = FakeGimClient.fakeRecoveryCode(1)
        rule.onNodeWithText(code).assertExists()
        rule.onNodeWithTag(OnboardingTestTags.KIT_ACCOUNT_ID).assertTextContains("u-1", substring = true)
        rule.onNodeWithTag(OnboardingTestTags.KIT_COPY).performScrollTo().performClick()
        rule.onNodeWithText("Copied").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.CODE_CONTINUE).performScrollTo().performClick()

        rule.onNodeWithTag(OnboardingTestTags.KIT_CHECK_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.KIT_CHECK).performTextInput("0000")
        rule.onNodeWithTag(OnboardingTestTags.KIT_CHECK_CONFIRM).performScrollTo().performClick()
        rule.onNodeWithText("That doesn’t match. Look at your recovery key and try again.").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.KIT_CHECK).performTextReplacement(code.replace("-", "").takeLast(4))
        rule.onNodeWithTag(OnboardingTestTags.KIT_CHECK_CONFIRM).performScrollTo().assertIsEnabled().performClick()

        rule.onNodeWithTag(OnboardingTestTags.CLAIM_HANDLE).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.HANDLE).performTextInput("sofia")
        rule.onNodeWithTag(OnboardingTestTags.CLAIM_HANDLE).performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 5_000) { finished == 1 }
        rule.waitForIdle()
        assertEquals(1, finished)
        assertEquals("sofia", runBlocking { manager.client.value?.session()?.handle })
    }

    @Test
    fun restore_flow_groups_the_pasted_code_and_finishes() {
        val manager = GimClientManager(
            FakeGimClientFactory { _, _ ->
                FakeGimClient(fakeScope, outboxDelays = null).apply {
                    addRecoverableUser(UserProfile(USER_ID, "", "Sofia Costa"), RECOVERY_CODE)
                }
            },
            FakePlatformHooks(),
            InMemoryServerUrlStore(),
        )
        val vm = OnboardingViewModel(manager)
        rule.setContent { GimTheme { OnboardingRoute(viewModel = vm, onFinished = { finished++ }, onShareRecoveryKit = {}) } }
        rule.onNodeWithTag(OnboardingTestTags.INTRO_SKIP).performClick()

        rule.onNodeWithTag(OnboardingTestTags.SERVER_URL).performTextInput("https://gim.example.eu")
        rule.onNodeWithTag(OnboardingTestTags.RESTORE).performScrollTo().performClick()
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_KIT).performTextInput("not a kit")
        rule.onNodeWithText(
            "We couldn’t find an Account ID or recovery key here. Paste the whole kit, or type them below.",
        ).assertExists()
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_USER_ID).performTextInput(" $USER_ID")
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_CODE).performTextInput("7bq2 k9mx")
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_CODE).assertTextContains("7BQ2-K9MX")
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_CODE).performTextReplacement(RECOVERY_CODE.lowercase())
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_CODE).assertTextContains(RECOVERY_CODE)
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_REVOKE_OTHERS).assertIsOn()
        rule.onNodeWithTag(OnboardingTestTags.RESTORE_SUBMIT).performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(timeoutMillis = 5_000) { finished == 1 }
        assertEquals(USER_ID, manager.client.value?.userId())
    }

    private companion object {
        const val USER_ID = "u" + "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuv" + "q"
        const val RECOVERY_CODE = "7BQ2-K9MX-4TVA-0HZR-PC63-W8NE-1SJD-5GFY-QK2M-V7R"
    }
}
