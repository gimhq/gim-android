package im.g.apps.feature.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
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

    private fun setContent() = rule.setContent {
        GimTheme { OnboardingRoute(viewModel = viewModel, onFinished = { finished++ }) }
    }

    @Test
    fun welcome_enables_create_and_link_for_a_valid_url_and_shows_restore_as_unavailable() {
        setContent()
        rule.onNodeWithTag(OnboardingTestTags.CREATE_ACCOUNT).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.LINK_DEVICE).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.SERVER_URL).performTextInput("gim.example.eu")
        rule.onNodeWithText("Enter an http:// or https:// address with a host name").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.CREATE_ACCOUNT).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.LINK_DEVICE).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.RESTORE).assertIsNotEnabled()
        assertEquals(1, rule.onAllNodes(hasText("Available soon")).fetchSemanticsNodes().size)
        rule.onNodeWithTag(OnboardingTestTags.SERVER_URL).performTextReplacement("https://gim.example.eu")
        rule.onNodeWithTag(OnboardingTestTags.LINK_DEVICE).assertIsEnabled()
    }

    @Test
    fun link_flow_shows_the_qr_code_then_the_sas_and_finishes_on_confirm() {
        val gate = CompletableDeferred<Unit>()
        val manager = gatedManager(gate)
        val vm = OnboardingViewModel(manager)
        rule.setContent { GimTheme { OnboardingRoute(viewModel = vm, onFinished = { finished++ }) } }

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
        rule.setContent { GimTheme { OnboardingRoute(viewModel = vm, onFinished = { finished++ }) } }
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
                    OnboardingCallbacks({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { finished++ }, {}, {}, {}),
                )
            }
        }
        rule.onNodeWithText("No device approved the link in time. Show a new code to try again.").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.LINK_QR).assertDoesNotExist()
        rule.onNodeWithTag(OnboardingTestTags.LINK_RETRY).assertIsEnabled().performClick()
        assertEquals(1, finished)
    }

    @Test
    fun create_account_flow_shows_the_recovery_code_and_finishes_with_a_handle() {
        setContent()
        rule.onNodeWithTag(OnboardingTestTags.SERVER_URL).performTextInput("https://gim.example.eu")
        rule.onNodeWithTag(OnboardingTestTags.CREATE_ACCOUNT).assertIsEnabled().performClick()
        rule.onNodeWithTag(OnboardingTestTags.SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.DISPLAY_NAME).performTextInput("Sofia Costa")
        rule.onNodeWithTag(OnboardingTestTags.SUBMIT).performScrollTo().performClick()

        rule.waitUntil(timeoutMillis = 5_000) { viewModel.state.value.step == OnboardingStep.RECOVERY_CODE }
        rule.onNodeWithText(FakeGimClient.fakeRecoveryCode(1)).assertExists()
        rule.onNodeWithTag(OnboardingTestTags.COPY_CODE).performClick()
        rule.onNodeWithText("Copied").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.CODE_CONTINUE).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.CODE_SAVED).performClick()
        rule.onNodeWithTag(OnboardingTestTags.CODE_CONTINUE).assertIsEnabled().performClick()

        rule.onNodeWithTag(OnboardingTestTags.CLAIM_HANDLE).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.HANDLE).performTextInput("sofia")
        rule.onNodeWithTag(OnboardingTestTags.CLAIM_HANDLE).performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 5_000) { finished == 1 }
        rule.waitForIdle()
        assertEquals(1, finished)
        assertEquals("sofia", runBlocking { manager.client.value?.session()?.handle })
    }
}
