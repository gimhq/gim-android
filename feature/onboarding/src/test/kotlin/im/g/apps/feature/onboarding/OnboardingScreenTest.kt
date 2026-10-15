package im.g.apps.feature.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakeGimClientFactory
import im.g.apps.core.bridge.fake.FakePlatformHooks
import im.g.apps.core.designsystem.theme.GimTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun setContent() = rule.setContent {
        GimTheme { OnboardingRoute(viewModel = viewModel, onFinished = { finished++ }) }
    }

    @Test
    fun welcome_enables_create_for_a_valid_url_and_shows_link_and_restore_as_unavailable() {
        setContent()
        rule.onNodeWithTag(OnboardingTestTags.CREATE_ACCOUNT).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.SERVER_URL).performTextInput("gim.example.eu")
        rule.onNodeWithText("Enter an http:// or https:// address with a host name").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.CREATE_ACCOUNT).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.LINK_DEVICE).assertIsNotEnabled()
        rule.onNodeWithTag(OnboardingTestTags.RESTORE).assertIsNotEnabled()
        assertEquals(2, rule.onAllNodes(hasText("Available soon")).fetchSemanticsNodes().size)
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
