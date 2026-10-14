package im.g.apps.feature.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
    private var registered = 0

    @After
    fun tearDown() = fakeScope.cancel()

    private fun setContent() = rule.setContent {
        GimTheme { OnboardingRoute(viewModel = viewModel, onRegistered = { registered++ }) }
    }

    private fun fill(serverUrl: String) {
        rule.onNodeWithTag(OnboardingTestTags.SERVER_URL).performTextInput(serverUrl)
        rule.onNodeWithTag(OnboardingTestTags.HANDLE).performTextInput("sofia")
        rule.onNodeWithTag(OnboardingTestTags.DISPLAY_NAME).performTextInput("Sofia Costa")
    }

    @Test
    fun entering_valid_fields_enables_the_button() {
        setContent()
        rule.onNodeWithTag(OnboardingTestTags.SUBMIT).assertIsNotEnabled()
        fill("https://gim.example.eu")
        rule.onNodeWithTag(OnboardingTestTags.SUBMIT).assertIsEnabled()
    }

    @Test
    fun invalid_url_shows_error_and_keeps_button_disabled() {
        setContent()
        fill("gim.example.eu")
        rule.onNodeWithText("Enter an http:// or https:// address with a host name").assertExists()
        rule.onNodeWithTag(OnboardingTestTags.SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun submit_invokes_callback_on_success() {
        setContent()
        fill("https://gim.example.eu")
        rule.onNodeWithTag(OnboardingTestTags.SUBMIT).performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 5_000) { registered == 1 }
        rule.waitForIdle()
        assertEquals(1, registered)
        assertEquals("sofia", runBlocking { manager.client.value?.session()?.handle })
    }
}
